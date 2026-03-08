from __future__ import annotations

from typing import List

import cv2
import numpy as np

from .config import ProcessingConfig
from .detection import StarDetector
from .latitude import LatitudeSolver
from .north import NorthPoleFinder
from .south import SouthPoleFinder
from .types import LatitudeEstimate, PatternDetection, PoleEstimate, ProcessingResult


class LatitudeEstimator:
    def __init__(self, config: ProcessingConfig | None = None) -> None:
        self.config = config or ProcessingConfig()
        self.detector = StarDetector(self.config.detection)
        self.north_solver = NorthPoleFinder(self.config.north)
        self.south_solver = SouthPoleFinder(self.config.south)
        self.lat_solver = LatitudeSolver(self.config.solver)

    def process_file(self, image_path: str, hemisphere_mode: str) -> ProcessingResult:
        image = cv2.imread(image_path, cv2.IMREAD_COLOR)
        if image is None:
            return ProcessingResult(
                success=False,
                hemisphere_mode=hemisphere_mode,
                stars=[],
                detected_patterns=[],
                warnings=[f"Fotograf okunamadi: {image_path}"],
            )
        return self.process_image(image, hemisphere_mode)

    def process_image(self, image_bgr: np.ndarray, hemisphere_mode: str) -> ProcessingResult:
        mode = hemisphere_mode.lower().strip()
        if mode not in {"north", "south"}:
            return ProcessingResult(
                success=False,
                hemisphere_mode=hemisphere_mode,
                stars=[],
                detected_patterns=[],
                warnings=["Hemisphere mode north veya south olmalidir."],
            )

        stars = self.detector.detect(image_bgr)
        patterns: List[PatternDetection] = []
        warnings: List[str] = []
        if len(stars) < 4:
            return ProcessingResult(
                success=False,
                hemisphere_mode=mode,
                stars=stars,
                detected_patterns=[],
                warnings=["Yeterli yildiz tespit edilemedi."],
            )

        h, w = image_bgr.shape[:2]
        if mode == "north":
            north = self.north_solver.solve(stars, w, h)
            if north is None:
                return ProcessingResult(
                    success=False,
                    hemisphere_mode=mode,
                    stars=stars,
                    detected_patterns=[],
                    warnings=["Polaris guvenilir sekilde tespit edilemedi."],
                )
            patterns.extend(north.patterns)
            warnings.extend(north.warnings)
            alt = self.lat_solver.pole_altitude_from_pixel(north.polaris_xy[0], north.polaris_xy[1], w, h)
            lat = self.lat_solver.latitude_from_altitude(alt, mode)
            confidence = max(0.0, min(1.0, north.confidence))
            return ProcessingResult(
                success=True,
                hemisphere_mode=mode,
                stars=stars,
                detected_patterns=patterns,
                north_polaris=PoleEstimate(
                    x=north.polaris_xy[0],
                    y=north.polaris_xy[1],
                    altitude_deg=alt,
                    confidence=confidence,
                    method="polaris",
                ),
                latitude=LatitudeEstimate(
                    latitude_deg=lat,
                    error_margin_deg=max(0.8, 4.0 * (1.0 - confidence)),
                    confidence=confidence,
                ),
                warnings=warnings,
            )

        south = self.south_solver.solve(stars, w, h)
        if south is None:
            return ProcessingResult(
                success=False,
                hemisphere_mode=mode,
                stars=stars,
                detected_patterns=[],
                warnings=["SCP guvenilir sekilde hesaplanamadi (Crux + pointers)."],
            )
        patterns.extend(south.patterns)
        warnings.extend(south.warnings)
        if south.sigma_octantis_check is not None and south.sigma_octantis_check < 0.12:
            warnings.append("Sigma Octantis yardimci kontrolu cozumle uyumlu degil.")

        alt = self.lat_solver.pole_altitude_from_pixel(south.scp_xy[0], south.scp_xy[1], w, h)
        lat = self.lat_solver.latitude_from_altitude(alt, mode)
        confidence = max(0.0, min(1.0, south.confidence))
        return ProcessingResult(
            success=True,
            hemisphere_mode=mode,
            stars=stars,
            detected_patterns=patterns,
            south_scp=PoleEstimate(
                x=south.scp_xy[0],
                y=south.scp_xy[1],
                altitude_deg=alt,
                confidence=confidence,
                method="crux+alpha_beta_centauri",
            ),
            latitude=LatitudeEstimate(
                latitude_deg=lat,
                error_margin_deg=max(1.2, 5.5 * (1.0 - confidence)),
                confidence=confidence,
            ),
            warnings=warnings,
        )

