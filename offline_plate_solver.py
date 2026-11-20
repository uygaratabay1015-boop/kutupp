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


def _get_ci(result: Dict[str, Any], *names: str) -> Any:
    """Sozlukten anahtar degeri al (buyuk/kucuk harf duyarsiz).

    tetra3 'RA', 'Dec', 'Roll', 'FOV' anahtarlarini kullanir; eski kod kucuk harfle
    ('ra', 'dec') ariyordu ve cozum her zaman basarisiz sayiliyordu.
    """
    lowered = {str(k).lower(): v for k, v in result.items()}
    for name in names:
        value = lowered.get(name.lower())
        if value is not None:
            return value
    return None


def _to_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


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
        # Renkli (RGB/RGBA/palet) goruntuler yildiz cikarmada hata verebilir;
        # tek kanalli gri tona cevir ve dosya tutacagini kapat.
        with Image.open(image_path) as src:
            image = src.convert("L")
        kwargs = extract_kwargs or {}
        result: Optional[Dict[str, Any]] = self._solver.solve_from_image(
            image,
            fov_estimate=fov_estimate_deg,
            fov_max_error=fov_max_error_deg,
            **kwargs,
        )

        warnings: list[str] = []
        ra = _to_float(_get_ci(result, "ra")) if isinstance(result, dict) else None
        dec = _to_float(_get_ci(result, "dec")) if isinstance(result, dict) else None
        if ra is None or dec is None:
            warnings.append("Cozum basarisiz. FOV tahmini, veritabanini ve yildiz kalitesini kontrol et.")
            solve_time = _to_float(_get_ci(result, "t_solve")) if isinstance(result, dict) else None
            return PlateSolveResult(
                success=False,
                ra_deg=None,
                dec_deg=None,
                roll_deg=None,
                fov_deg=None,
                solve_time_ms=solve_time,
                warnings=warnings,
                raw=result if isinstance(result, dict) else None,
            )

        # tetra3 RA/Dec/Roll/FOV degerlerini zaten DERECE olarak dondurur. Eski kod
        # kucuk degerleri "radyan" sanip 57.3 ile carpiyordu (ornegin RA=5 deg bozuluyordu).
        return PlateSolveResult(
            success=True,
            ra_deg=ra,
            dec_deg=dec,
            roll_deg=_to_float(_get_ci(result, "roll")),
            fov_deg=_to_float(_get_ci(result, "fov")),
            solve_time_ms=_to_float(_get_ci(result, "t_solve")),
            warnings=warnings,
            raw=result,
        )
