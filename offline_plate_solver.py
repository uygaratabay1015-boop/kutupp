from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional

from PIL import Image


@dataclass
class PlateSolveResult:
    success: bool
    ra_deg: Optional[float]
    dec_deg: Optional[float]
    roll_deg: Optional[float]
    fov_deg: Optional[float]
    solve_time_ms: Optional[float]
    warnings: list[str]
    raw: Optional[Dict[str, Any]]


def _maybe_rad_to_deg(value: Optional[float], max_abs_rad: float) -> Optional[float]:
    if value is None:
        return None
    if abs(value) <= max_abs_rad + 1e-6:
        return value * 180.0 / 3.141592653589793
    return value


class OfflinePlateSolver:
    def __init__(self, database_path: Optional[str] = None) -> None:
        try:
            import tetra3  # type: ignore
        except Exception as exc:  # pragma: no cover - runtime dependency
            raise RuntimeError(
                "tetra3 bulunamadi. `pip install tetra3` ile yukleyin."
            ) from exc

        self._tetra3 = tetra3
        self._solver = tetra3.Tetra3()
        if database_path:
            self._solver.load_database(database_path)

    def solve_image(
        self,
        image_path: str,
        fov_estimate_deg: Optional[float] = None,
        fov_max_error_deg: Optional[float] = None,
        extract_kwargs: Optional[Dict[str, Any]] = None,
    ) -> PlateSolveResult:
        image = Image.open(image_path)
        kwargs = extract_kwargs or {}
        result: Dict[str, Any] = self._solver.solve_from_image(
            image,
            fov_estimate=fov_estimate_deg,
            fov_max_error=fov_max_error_deg,
            **kwargs,
        )

        warnings: list[str] = []
        if result is None or result.get("ra") is None or result.get("dec") is None:
            warnings.append("Cozum basarisiz. FOV tahmini, veritabanini ve yildiz kalitesini kontrol et.")
            return PlateSolveResult(
                success=False,
                ra_deg=None,
                dec_deg=None,
                roll_deg=None,
                fov_deg=None,
                solve_time_ms=result.get("T_solve") if isinstance(result, dict) else None,
                warnings=warnings,
                raw=result if isinstance(result, dict) else None,
            )

        ra = _maybe_rad_to_deg(result.get("ra"), max_abs_rad=6.283185307179586)
        dec = _maybe_rad_to_deg(result.get("dec"), max_abs_rad=1.5707963267948966)
        roll = _maybe_rad_to_deg(result.get("roll"), max_abs_rad=3.141592653589793)
        fov = _maybe_rad_to_deg(result.get("fov"), max_abs_rad=6.283185307179586)

        return PlateSolveResult(
            success=True,
            ra_deg=ra,
            dec_deg=dec,
            roll_deg=roll,
            fov_deg=fov,
            solve_time_ms=result.get("T_solve"),
            warnings=warnings,
            raw=result,
        )
