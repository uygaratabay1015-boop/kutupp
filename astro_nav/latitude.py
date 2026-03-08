from __future__ import annotations

import math

from .config import SolverConfig


class LatitudeSolver:
    def __init__(self, config: SolverConfig) -> None:
        self.config = config

    def pole_altitude_from_pixel(self, x: float, y: float, image_w: int, image_h: int) -> float:
        if image_w <= 0 or image_h <= 0:
            raise ValueError("image dimensions must be positive")

        cx = image_w / 2.0
        cy = image_h / 2.0

        vfov = max(5.0, min(170.0, self.config.vertical_fov_deg))
        hfov = max(5.0, min(170.0, self.config.horizontal_fov_deg))
        fy = (image_h / 2.0) / math.tan(math.radians(vfov / 2.0))
        fx = (image_w / 2.0) / math.tan(math.radians(hfov / 2.0))

        nx = (x - cx) / max(1e-6, fx)
        ny = (cy - y) / max(1e-6, fy)

        roll_rad = math.radians(self.config.camera_roll_deg)
        up = ny * math.cos(roll_rad) - nx * math.sin(roll_rad)

        apparent_alt = self.config.camera_pitch_deg + math.degrees(math.atan(up))
        refr = self._atmospheric_refraction_deg(apparent_alt)
        return apparent_alt - refr

    def latitude_from_altitude(self, altitude_deg: float, hemisphere_mode: str) -> float:
        alt = max(-90.0, min(90.0, altitude_deg))
        if hemisphere_mode.lower() == "south":
            return -abs(alt)
        return abs(alt)

    @staticmethod
    def _atmospheric_refraction_deg(apparent_alt: float) -> float:
        if apparent_alt < -1.0 or apparent_alt > 85.0:
            return 0.0
        denom = math.tan(math.radians(apparent_alt + 10.3 / (apparent_alt + 5.11)))
        if abs(denom) < 1e-9:
            return 0.0
        arc_min = 1.02 / denom
        return max(0.0, min(1.2, arc_min / 60.0))

