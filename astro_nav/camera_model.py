from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class CameraModel:
    vertical_fov_deg: float
    horizontal_fov_deg: float
    pitch_deg: float
    roll_deg: float

    def altitude_from_pixel(self, x: float, y: float, image_w: int, image_h: int) -> float:
        if image_w <= 0 or image_h <= 0:
            raise ValueError("image dimensions must be positive")

        cx = image_w / 2.0
        cy = image_h / 2.0
        vfov = max(5.0, min(170.0, self.vertical_fov_deg))
        hfov = max(5.0, min(170.0, self.horizontal_fov_deg))

        fy = (image_h / 2.0) / math.tan(math.radians(vfov / 2.0))
        fx = (image_w / 2.0) / math.tan(math.radians(hfov / 2.0))

        nx = (x - cx) / max(1e-9, fx)
        ny = (cy - y) / max(1e-9, fy)

        roll = math.radians(self.roll_deg)
        up_component = ny * math.cos(roll) - nx * math.sin(roll)

        apparent_alt = self.pitch_deg + math.degrees(math.atan(up_component))
        return apparent_alt - _atmospheric_refraction_deg(apparent_alt)


def _atmospheric_refraction_deg(apparent_alt: float) -> float:
    if apparent_alt < -1.0 or apparent_alt > 85.0:
        return 0.0
    denom = math.tan(math.radians(apparent_alt + 10.3 / (apparent_alt + 5.11)))
    if abs(denom) < 1e-9:
        return 0.0
    arc_min = 1.02 / denom
    return max(0.0, min(1.2, arc_min / 60.0))

