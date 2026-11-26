#!/usr/bin/env python3
"""Dunyanin her yerinde enlem + boylam: plate solve + yercekimi + UTC zamani.

Ornekler
--------
# Fotograftan (tetra3 + veritabani gerekir):
python run_zenith.py --image gece.jpg --utc 2026-11-20T21:05:00Z --pitch 42 --roll 0 --hfov 65

# Zaten cozulmus yonelimle (RA/Dec/Roll):
python run_zenith.py --ra 37.95 --dec 70.2 --solver-roll 12 --utc 2026-11-20T21:05:00Z --pitch 42

# Roll isaretini bilinen bir noktadan calibre et:
python run_zenith.py ... --known-lat 41.0 --known-lon 29.0
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone

import numpy as np

from astro_nav.zenith_fix import (
    apply_tilt_offset,
    calibrate_tilt_offset,
    choose_roll_sign,
    monte_carlo_error,
    quality_warnings,
    rotation_from_radec_roll,
    solve_position,
    up_from_gravity,
    up_from_tilt,
)


def parse_utc(text: str) -> datetime:
    value = text.strip().replace("Z", "+00:00")
    when = datetime.fromisoformat(value)
    if when.tzinfo is None:
        raise SystemExit("--utc zaman dilimi icermeli (ornek 2026-11-20T21:05:00Z veya +03:00)")
    return when.astimezone(timezone.utc)


def main() -> None:
    p = argparse.ArgumentParser(description="Zenit tabanli konum (enlem + boylam), tum dunya")
    p.add_argument("--utc", required=True, help="Cekim zamani, zaman dilimli ISO (ornek 2026-11-20T21:05:00Z)")
    src = p.add_argument_group("yonelim (biri gerekli)")
    src.add_argument("--image", help="Gece gokyuzu fotografi (tetra3 ile cozulur)")
    src.add_argument("--db", help="tetra3 veritabani yolu")
    src.add_argument("--hfov", type=float, help="Yatay gorus acisi tahmini (derece)")
    src.add_argument("--ra", type=float, help="Hazir cozum: RA (derece, J2000)")
    src.add_argument("--dec", type=float, help="Hazir cozum: Dec (derece, J2000)")
    src.add_argument("--solver-roll", type=float, default=0.0, help="Hazir cozum: roll (derece)")
    p.add_argument("--roll-sign", type=float, default=1.0, choices=[1.0, -1.0],
                   help="Plate solver roll isareti (--known-lat/--known-lon ile belirlenir)")
    tilt = p.add_argument_group("zenit yonu (biri gerekli)")
    tilt.add_argument("--pitch", type=float, help="Kameranin ufuk ustu yuksekligi (derece)")
    tilt.add_argument("--roll", type=float, default=0.0, help="Kamera roll (+ saat yonu), derece")
    tilt.add_argument("--gravity", type=float, nargs=3, metavar=("X", "Y", "Z"),
                      help="Yercekimi/ivmeolcer okumasi, KAMERA ekseninde (x sag, y asagi, z bakis)")
    tilt.add_argument("--gravity-points-down", action="store_true",
                      help="Sensor asagi yonu veriyorsa (varsayilan: yukari)")
    p.add_argument("--known-lat", type=float, help="Calibrasyon: gercek enlem")
    p.add_argument("--known-lon", type=float, help="Calibrasyon: gercek boylam")
    p.add_argument("--save-tilt-cal", help="Bilinen konumlu kareden sensor sapmasini hesapla ve bu JSON dosyasina kaydet "
                                           "(--known-lat/--known-lon gerekir; farkli egim/roll'larla cok kare icin dosyaya ekler)")
    p.add_argument("--tilt-cal", help="Kaydedilmis sensor sapmasi kalibrasyonunu uygula")
    p.add_argument("--tilt-sigma", type=float, default=0.5, help="Egim sensoru hatasi (derece, 1 sigma)")
    p.add_argument("--time-sigma", type=float, default=1.0, help="Saat hatasi (sn, 1 sigma)")
    args = p.parse_args()

    when = parse_utc(args.utc)
    if args.gravity:
        up = up_from_gravity(args.gravity, sensor_points_up=not args.gravity_points_down)
    elif args.pitch is not None:
        up = up_from_tilt(args.pitch, args.roll)
    else:
        raise SystemExit("--pitch veya --gravity gerekli")

    warnings: list[str] = []
    solve_info: dict = {}
    if args.image:
        from offline_plate_solver import OfflinePlateSolver
        try:
            solver = OfflinePlateSolver(database_path=args.db)
        except RuntimeError as exc:
            raise SystemExit(f"HATA: {exc}")
        res = solver.solve_image(args.image, fov_estimate_deg=args.hfov)
        if not res.success:
            print(json.dumps({"success": False, "warnings": res.warnings}, indent=2, ensure_ascii=False))
            sys.exit(2)
        ra, dec, roll = res.ra_deg, res.dec_deg, res.roll_deg or 0.0
        solve_info = {"ra_deg": ra, "dec_deg": dec, "roll_deg": roll, "fov_deg": res.fov_deg}
    elif args.ra is not None and args.dec is not None:
        ra, dec, roll = args.ra, args.dec, args.solver_roll
        solve_info = {"ra_deg": ra, "dec_deg": dec, "roll_deg": roll}
    else:
        raise SystemExit("--image veya (--ra ve --dec) gerekli")

    calibration = None
    sign = args.roll_sign
    if args.known_lat is not None and args.known_lon is not None:
        calibration = choose_roll_sign(ra, dec, roll, up, when, args.known_lat, args.known_lon)
        sign = calibration["best_roll_sign"]
        if not calibration["decisive"]:
            warnings.append("Roll isareti net ayirt edilemedi; baska bir fotografla tekrar deneyin.")

    cel_from_cam = rotation_from_radec_roll(ra, dec, roll, sign)

    tilt_cal_info = None
    if args.save_tilt_cal:
        if args.known_lat is None or args.known_lon is None:
            raise SystemExit("--save-tilt-cal icin --known-lat ve --known-lon gerekli")
        store = {"frames": []}
        if os.path.exists(args.save_tilt_cal):
            with open(args.save_tilt_cal, encoding="utf-8") as fh:
                store = json.load(fh)
        store["frames"].append({
            "cel_from_cam": cel_from_cam.tolist(), "up_measured": [float(v) for v in up],
            "utc": when.isoformat(), "lat": args.known_lat, "lon": args.known_lon})
        obs = [(np.array(f["cel_from_cam"]), np.array(f["up_measured"]), parse_utc(f["utc"]), f["lat"], f["lon"])
               for f in store["frames"]]
        cal = calibrate_tilt_offset(obs)
        store["Q"] = cal["Q"].tolist()
        with open(args.save_tilt_cal, "w", encoding="utf-8") as fh:
            json.dump(store, fh)
        tilt_cal_info = {"saved": args.save_tilt_cal, "frames": cal["frames"],
                         "offset_deg": round(cal["offset_deg"], 3), "residual_deg": round(cal["residual_deg"], 3)}
        if cal["frames"] < 8:
            warnings.append(f"Kalibrasyon {cal['frames']} kareyle yapildi; farkli egim/roll'larla en az ~10 kare onerilir "
                            "(1 kare ~30 km, 12 kare ~15 km artik hata).")
        up = apply_tilt_offset(cal["Q"], up)
    elif args.tilt_cal:
        with open(args.tilt_cal, encoding="utf-8") as fh:
            store = json.load(fh)
        up = apply_tilt_offset(np.array(store["Q"]), up)
        tilt_cal_info = {"applied": args.tilt_cal, "frames": len(store.get("frames", []))}
    else:
        warnings.append("Sensor sapmasi kalibre edilmedi: tipik telefonda ~50-100 km ek hata olabilir (--save-tilt-cal).")

    fix = solve_position(cel_from_cam, up, when)
    err = monte_carlo_error(cel_from_cam, up, when, sigma_tilt_deg=args.tilt_sigma,
                            sigma_time_s=args.time_sigma, n=800)
    warnings += quality_warnings(cel_from_cam, up, when, datetime.now(timezone.utc))

    print(json.dumps({
        "success": True,
        "latitude_deg": round(fix.latitude_deg, 4),
        "longitude_deg": round(fix.longitude_deg, 4),
        "hemisphere": "north" if fix.latitude_deg >= 0 else "south",
        "error_km_68": round(err["p68_km"], 1),
        "error_km_95": round(err["p95_km"], 1),
        "solve": solve_info,
        "roll_sign": sign,
        "calibration": calibration,
        "tilt_calibration": tilt_cal_info,
        "warnings": warnings,
    }, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
