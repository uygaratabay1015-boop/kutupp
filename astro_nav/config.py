from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class DetectionConfig:
    min_blob_area_px: int = 3
    max_blob_area_px: int = 180
    min_threshold: int = 145
    top_star_limit: int = 80


@dataclass(frozen=True)
class NorthConfig:
    min_candidates: int = 5
    min_confidence: float = 0.34


@dataclass(frozen=True)
class SouthConfig:
    crux_long_short_ratio: float = 2.35
    crux_ratio_tolerance: float = 0.50
    min_crux_score: float = 0.45
    min_pointer_score: float = 0.40
    axis_extension_factor: float = 4.5
    pointer_extension_factor: float = 2.8
    min_confidence: float = 0.42


@dataclass(frozen=True)
class SolverConfig:
    vertical_fov_deg: float = 60.0
    horizontal_fov_deg: float = 70.0
    camera_pitch_deg: float = 0.0
    camera_roll_deg: float = 0.0


@dataclass(frozen=True)
class ProcessingConfig:
    detection: DetectionConfig = field(default_factory=DetectionConfig)
    north: NorthConfig = field(default_factory=NorthConfig)
    south: SouthConfig = field(default_factory=SouthConfig)
    solver: SolverConfig = field(default_factory=SolverConfig)
