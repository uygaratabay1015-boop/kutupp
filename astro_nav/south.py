from __future__ import annotations

from dataclasses import dataclass
from itertools import combinations
from typing import List, Optional

from .config import SouthConfig
from .geometry import (
    add,
    angle_between,
    dist,
    midpoint,
    mul,
    normalize,
    point_line_distance,
    project_point_on_line,
    sub,
)
from .types import DetectedStar, PatternDetection


@dataclass(frozen=True)
class SouthSolution:
    scp_xy: tuple[float, float]
    confidence: float
    patterns: List[PatternDetection]
    warnings: List[str]
    sigma_octantis_check: Optional[float]


@dataclass(frozen=True)
class CruxCandidate:
    gacrux: DetectedStar
    acrux: DetectedStar
    side1: DetectedStar
    side2: DetectedStar
    score: float
    long_axis_len: float
    short_axis_len: float


class SouthPoleFinder:
    """
    Southern hemisphere SCP estimator.
    Primary method:
    1) Crux detect
    2) Long axis (Gacrux -> Acrux)
    3) 4.5x extension
    4) Alpha/Beta Centauri pointer stars
    5) Combine axis + pointers to refine SCP

    Sigma Octantis is optional post-check only.
    """

    def __init__(self, config: SouthConfig) -> None:
        self.config = config

    def solve(self, stars: List[DetectedStar], image_w: int, image_h: int) -> Optional[SouthSolution]:
        warnings: List[str] = []
        if len(stars) < 8:
            warnings.append("Guney cozumunde yetersiz yildiz adayi.")
            return None

        bright = stars[: min(40, len(stars))]
        crux = self._find_best_crux(bright, image_h)
        if crux is None or crux.score < self.config.min_crux_score:
            warnings.append("Crux guvenilir tespit edilemedi.")
            return None

        axis = normalize(sub((crux.acrux.x, crux.acrux.y), (crux.gacrux.x, crux.gacrux.y)))
        axis_len = crux.long_axis_len
        initial_scp = add(
            (crux.gacrux.x, crux.gacrux.y),
            mul(axis, self.config.axis_extension_factor * axis_len),
        )

        pointers = self._find_pointer_pair(bright, crux, initial_scp, image_h)
        if pointers is None:
            warnings.append("Alpha/Beta Centauri pointer cift guveni dusuk.")
            return None

        p1, p2, pointer_score = pointers
        pointer_mid = midpoint((p1.x, p1.y), (p2.x, p2.y))
        pvec = normalize(sub((p2.x, p2.y), (p1.x, p1.y)))
        p_dist = dist((p1.x, p1.y), (p2.x, p2.y))

        pointer_cand_a = add((p1.x, p1.y), mul(pvec, self.config.pointer_extension_factor * p_dist))
        pointer_cand_b = add((p2.x, p2.y), mul(mul(pvec, -1.0), self.config.pointer_extension_factor * p_dist))
        pointer_target = pointer_cand_a if dist(pointer_cand_a, initial_scp) < dist(pointer_cand_b, initial_scp) else pointer_cand_b

        axis_projection = project_point_on_line(initial_scp, (crux.gacrux.x, crux.gacrux.y), (crux.acrux.x, crux.acrux.y))
        refined = (
            0.60 * initial_scp[0] + 0.25 * pointer_target[0] + 0.15 * axis_projection[0],
            0.60 * initial_scp[1] + 0.25 * pointer_target[1] + 0.15 * axis_projection[1],
        )

        false_positive_penalty = self._false_positive_penalty(
            crux=crux,
            pointer_pair=(p1, p2),
            scp=refined,
            image_w=image_w,
            image_h=image_h,
        )
        confidence = max(
            0.0,
            min(
                1.0,
                (0.55 * crux.score + 0.35 * pointer_score + 0.10 * (1.0 - false_positive_penalty)),
            ),
        )
        if confidence < self.config.min_confidence:
            warnings.append("Guney cozum guveni dusuk, sonuc uydurulmadi.")
            return None

        sigma_check = self._sigma_octantis_optional_check(stars, refined, image_h)

        patterns = [
            PatternDetection(
                name="crux",
                confidence=float(crux.score),
                points=[
                    (crux.gacrux.x, crux.gacrux.y),
                    (crux.acrux.x, crux.acrux.y),
                    (crux.side1.x, crux.side1.y),
                    (crux.side2.x, crux.side2.y),
                ],
                metadata={
                    "long_short_ratio": crux.long_axis_len / max(1e-6, crux.short_axis_len),
                },
            ),
            PatternDetection(
                name="alpha_beta_centauri",
                confidence=float(pointer_score),
                points=[(p1.x, p1.y), (p2.x, p2.y)],
                metadata={"pair_distance_px": p_dist},
            ),
        ]

        return SouthSolution(
            scp_xy=refined,
            confidence=float(confidence),
            patterns=patterns,
            warnings=warnings,
            sigma_octantis_check=sigma_check,
        )

    def _find_best_crux(self, stars: List[DetectedStar], image_h: int) -> Optional[CruxCandidate]:
        best: Optional[CruxCandidate] = None
        for combo in combinations(stars, 4):
            pts = [(s.x, s.y) for s in combo]
            pairs = list(combinations(range(4), 2))
            pair_lengths = sorted(
                [(dist(pts[i], pts[j]), i, j) for i, j in pairs],
                key=lambda t: t[0],
                reverse=True,
            )
            long_len, a, b = pair_lengths[0]
            short_candidates = [
                (d, i, j)
                for d, i, j in pair_lengths[1:]
                if len({a, b, i, j}) == 4
            ]
            if not short_candidates:
                continue
            short_len, c, d = short_candidates[0]
            if short_len <= 1e-6:
                continue

            ratio = long_len / short_len
            ratio_err = abs(ratio - self.config.crux_long_short_ratio)
            if ratio_err > self.config.crux_ratio_tolerance:
                continue

            long_vec = sub(pts[b], pts[a])
            short_vec = sub(pts[d], pts[c])
            angle = angle_between(long_vec, short_vec)
            angle_score = max(0.0, 1.0 - abs(angle - 90.0) / 35.0)
            ratio_score = max(0.0, 1.0 - ratio_err / max(1e-6, self.config.crux_ratio_tolerance))

            mid_long = midpoint(pts[a], pts[b])
            mid_short = midpoint(pts[c], pts[d])
            center_dist = dist(mid_long, mid_short)
            center_score = max(0.0, 1.0 - center_dist / max(8.0, 0.12 * image_h))

            brightness_score = min(1.0, sum(s.brightness for s in combo) / (4.0 * 220.0))
            score = 0.35 * ratio_score + 0.35 * angle_score + 0.20 * center_score + 0.10 * brightness_score
            if best is None or score > best.score:
                s_a = combo[a]
                s_b = combo[b]
                # Heuristic: Gacrux is usually visually higher than Acrux in common framed photos.
                gacrux, acrux = (s_a, s_b) if s_a.y <= s_b.y else (s_b, s_a)
                best = CruxCandidate(
                    gacrux=gacrux,
                    acrux=acrux,
                    side1=combo[c],
                    side2=combo[d],
                    score=score,
                    long_axis_len=long_len,
                    short_axis_len=short_len,
                )
        return best

    def _find_pointer_pair(
        self,
        stars: List[DetectedStar],
        crux: CruxCandidate,
        initial_scp: tuple[float, float],
        image_h: int,
    ) -> Optional[tuple[DetectedStar, DetectedStar, float]]:
        crux_members = {id(crux.gacrux), id(crux.acrux), id(crux.side1), id(crux.side2)}
        candidates = [s for s in stars if id(s) not in crux_members][:20]

        best: Optional[tuple[DetectedStar, DetectedStar, float]] = None
        for s1, s2 in combinations(candidates, 2):
            pair_dist = dist((s1.x, s1.y), (s2.x, s2.y))
            if pair_dist < 0.04 * image_h or pair_dist > 0.45 * image_h:
                continue
            line_to_scp = point_line_distance(initial_scp, (s1.x, s1.y), (s2.x, s2.y))
            line_score = max(0.0, 1.0 - line_to_scp / max(10.0, 0.16 * image_h))

            brightness = min(1.0, (s1.brightness + s2.brightness) / (2.0 * 240.0))
            cross_to_crux = point_line_distance(
                midpoint((crux.gacrux.x, crux.gacrux.y), (crux.acrux.x, crux.acrux.y)),
                (s1.x, s1.y),
                (s2.x, s2.y),
            )
            separation_score = min(1.0, cross_to_crux / max(6.0, 0.05 * image_h))
            score = 0.45 * line_score + 0.30 * brightness + 0.25 * separation_score
            if best is None or score > best[2]:
                best = (s1, s2, score)

        if best is None or best[2] < self.config.min_pointer_score:
            return None
        return best

    def _false_positive_penalty(
        self,
        crux: CruxCandidate,
        pointer_pair: tuple[DetectedStar, DetectedStar],
        scp: tuple[float, float],
        image_w: int,
        image_h: int,
    ) -> float:
        p1, p2 = pointer_pair
        axis_line_dist = point_line_distance(scp, (crux.gacrux.x, crux.gacrux.y), (crux.acrux.x, crux.acrux.y))
        pointer_line_dist = point_line_distance(scp, (p1.x, p1.y), (p2.x, p2.y))
        off_axis = min(1.0, axis_line_dist / max(10.0, 0.20 * image_h))
        off_pointer = min(1.0, pointer_line_dist / max(10.0, 0.20 * image_h))

        margin_x = 0.50 * image_w
        margin_y = 0.50 * image_h
        out_of_frame = 0.0
        if scp[0] < -margin_x or scp[0] > image_w + margin_x:
            out_of_frame = 1.0
        if scp[1] < -margin_y or scp[1] > image_h + margin_y:
            out_of_frame = 1.0
        return 0.45 * off_axis + 0.45 * off_pointer + 0.10 * out_of_frame

    def _sigma_octantis_optional_check(
        self,
        stars: List[DetectedStar],
        scp_xy: tuple[float, float],
        image_h: int,
    ) -> Optional[float]:
        # Optional helper only: if a faint star exists near SCP, confidence slightly supports solution.
        faint_pool = [s for s in stars if 80.0 <= s.brightness <= 170.0]
        if not faint_pool:
            return None
        nearest = min(dist((s.x, s.y), scp_xy) for s in faint_pool)
        score = max(0.0, min(1.0, 1.0 - nearest / max(8.0, 0.10 * image_h)))
        return score

