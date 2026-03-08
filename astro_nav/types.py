from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional


@dataclass(frozen=True)
class DetectedStar:
    x: float
    y: float
    brightness: float
    radius_px: float


@dataclass(frozen=True)
class PatternDetection:
    name: str
    confidence: float
    points: List[tuple[float, float]]
    metadata: dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True)
class PoleEstimate:
    x: float
    y: float
    altitude_deg: float
    confidence: float
    method: str


@dataclass(frozen=True)
class LatitudeEstimate:
    latitude_deg: float
    error_margin_deg: float
    confidence: float


@dataclass
class ProcessingResult:
    success: bool
    hemisphere_mode: str
    stars: List[DetectedStar]
    detected_patterns: List[PatternDetection]
    north_polaris: Optional[PoleEstimate] = None
    south_scp: Optional[PoleEstimate] = None
    latitude: Optional[LatitudeEstimate] = None
    warnings: List[str] = field(default_factory=list)

