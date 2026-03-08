from __future__ import annotations

from .camera_model import CameraModel
from .config import SolverConfig


class LatitudeSolver:
    def __init__(self, config: SolverConfig) -> None:
        self.config = config
        self.camera_model = CameraModel(
            vertical_fov_deg=config.vertical_fov_deg,
            horizontal_fov_deg=config.horizontal_fov_deg,
            pitch_deg=config.camera_pitch_deg,
            roll_deg=config.camera_roll_deg,
        )

    def pole_altitude_from_pixel(self, x: float, y: float, image_w: int, image_h: int) -> float:
        return self.camera_model.altitude_from_pixel(x, y, image_w, image_h)

    def latitude_from_altitude(self, altitude_deg: float, hemisphere_mode: str) -> float:
        alt = max(-90.0, min(90.0, altitude_deg))
        if hemisphere_mode.lower() == "south":
            return -abs(alt)
        return abs(alt)
