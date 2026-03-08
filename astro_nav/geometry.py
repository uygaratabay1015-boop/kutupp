from __future__ import annotations

import math
from typing import Iterable, Optional


def dist(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def dot(a: tuple[float, float], b: tuple[float, float]) -> float:
    return a[0] * b[0] + a[1] * b[1]


def norm(v: tuple[float, float]) -> float:
    return math.hypot(v[0], v[1])


def normalize(v: tuple[float, float]) -> tuple[float, float]:
    n = norm(v)
    if n <= 1e-9:
        return (0.0, 0.0)
    return (v[0] / n, v[1] / n)


def add(a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float]:
    return (a[0] + b[0], a[1] + b[1])


def sub(a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float]:
    return (a[0] - b[0], a[1] - b[1])


def mul(v: tuple[float, float], scalar: float) -> tuple[float, float]:
    return (v[0] * scalar, v[1] * scalar)


def midpoint(a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float]:
    return ((a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5)


def angle_between(v1: tuple[float, float], v2: tuple[float, float]) -> float:
    n1 = norm(v1)
    n2 = norm(v2)
    if n1 < 1e-9 or n2 < 1e-9:
        return 180.0
    c = max(-1.0, min(1.0, dot(v1, v2) / (n1 * n2)))
    return math.degrees(math.acos(c))


def point_line_distance(
    p: tuple[float, float],
    a: tuple[float, float],
    b: tuple[float, float],
) -> float:
    ab = sub(b, a)
    ap = sub(p, a)
    denom = norm(ab)
    if denom <= 1e-9:
        return dist(p, a)
    area2 = abs(ab[0] * ap[1] - ab[1] * ap[0])
    return area2 / denom


def project_point_on_line(
    p: tuple[float, float],
    a: tuple[float, float],
    b: tuple[float, float],
) -> tuple[float, float]:
    ab = sub(b, a)
    denom = dot(ab, ab)
    if denom <= 1e-9:
        return a
    t = dot(sub(p, a), ab) / denom
    return add(a, mul(ab, t))


def weighted_centroid(points: Iterable[tuple[tuple[float, float], float]]) -> Optional[tuple[float, float]]:
    sx = 0.0
    sy = 0.0
    sw = 0.0
    for (x, y), w in points:
        if w <= 0:
            continue
        sx += x * w
        sy += y * w
        sw += w
    if sw <= 1e-9:
        return None
    return (sx / sw, sy / sw)

