from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from .config import NorthConfig
from .geometry import dist, weighted_centroid
from .types import DetectedStar, PatternDetection


@dataclass(frozen=True)
class NorthSolution:
    polaris_xy: tuple[float, float]
    confidence: float
    patterns: List[PatternDetection]
    warnings: List[str]


class NorthPoleFinder:
    """
    Polaris finder with conservative confidence.
    Strategy:
    - Select brightest stars.
    - Favor upper-half stars (common framing for north-sky shots).
    - Prefer stars with medium isolation (avoid clustered noise and hot pixels).
    """

    def __init__(self, config: NorthConfig) -> None:
        self.config = config

    def solve(self, stars: List[DetectedStar], image_w: int, image_h: int) -> Optional[NorthSolution]:
        warnings: List[str] = []
        if len(stars) < self.config.min_candidates:
            warnings.append("Kuzey cozumunde yetersiz yildiz adayi.")
            return None

        top = stars[: min(25, len(stars))]
        scored: List[tuple[DetectedStar, float]] = []
        for s in top:
            y_norm = s.y / max(1.0, float(image_h))
            vertical = max(0.0, min(1.0, 1.0 - y_norm))
            bright = max(0.0, min(1.0, s.brightness / 255.0))
            dists = sorted(dist((s.x, s.y), (o.x, o.y)) for o in top if o is not s)
            neigh = dists[:4] if len(dists) >= 4 else dists
            isolation = 0.0
            if neigh:
                avg_d = sum(neigh) / len(neigh)
                isolation = max(0.0, min(1.0, avg_d / max(25.0, 0.08 * image_h)))
            score = 0.42 * vertical + 0.33 * bright + 0.25 * isolation
            scored.append((s, score))

        scored.sort(key=lambda x: x[1], reverse=True)
        top_scored = scored[:3]
        centroid = weighted_centroid([((s.x, s.y), sc) for s, sc in top_scored])
        if centroid is None:
            return None

        conf = sum(sc for _, sc in top_scored) / len(top_scored)
        if conf < self.config.min_confidence:
            warnings.append("Polaris guveni dusuk, sonuc uydurulmadi.")
            return None

        pattern = PatternDetection(
            name="north_polar_region",
            confidence=float(conf),
            points=[(s.x, s.y) for s, _ in top_scored],
            metadata={"candidate_count": float(len(top_scored))},
        )
        return NorthSolution(
            polaris_xy=centroid,
            confidence=float(conf),
            patterns=[pattern],
            warnings=warnings,
        )

