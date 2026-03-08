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

        scp_alt = self.lat_solver.pole_altitude_from_pixel(south.scp_xy[0], south.scp_xy[1], w, h)
        lat = self.lat_solver.latitude_from_altitude(scp_alt, mode)
        confidence = max(0.0, min(1.0, south.confidence))

        crux_center_alt = self._crux_center_altitude(patterns, w, h)
        if crux_center_alt is not None and abs(crux_center_alt - scp_alt) < 0.8:
            warnings.append("SCP altitude ve Crux center altitude neredeyse ayni; geometriyi kontrol et.")

        expected = self.config.solver.expected_latitude_deg
        if expected is not None and abs(expected + 90.0) <= 2.0 and abs(scp_alt - 90.0) > 8.0:
            warnings.append("SCP detected but altitude inconsistent with South Pole geometry.")

        if self.config.solver.debug:
            crux_center = self._crux_center_point(patterns)
            print("Estimated SCP pixel:", south.scp_xy)
            print("SCP altitude:", scp_alt)
            print("Crux center pixel:", crux_center)
            print("Crux center altitude:", crux_center_alt)
            print("Camera pitch:", self.config.solver.camera_pitch_deg)

        return ProcessingResult(
            success=True,
            hemisphere_mode=mode,
            stars=stars,
            detected_patterns=patterns,
            south_scp=PoleEstimate(
                x=south.scp_xy[0],
                y=south.scp_xy[1],
                altitude_deg=scp_alt,
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

    def _crux_center_point(self, patterns: List[PatternDetection]) -> tuple[float, float] | None:
        crux_pattern = next((p for p in patterns if p.name == "crux"), None)
        if crux_pattern is None or not crux_pattern.points:
            return None
        xs = [p[0] for p in crux_pattern.points]
        ys = [p[1] for p in crux_pattern.points]
        return (sum(xs) / len(xs), sum(ys) / len(ys))

    def _crux_center_altitude(self, patterns: List[PatternDetection], image_w: int, image_h: int) -> float | None:
        center = self._crux_center_point(patterns)
        if center is None:
            return None
        return self.lat_solver.pole_altitude_from_pixel(center[0], center[1], image_w, image_h)
