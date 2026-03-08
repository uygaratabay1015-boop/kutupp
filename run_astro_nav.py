from __future__ import annotations

import argparse
import json
from dataclasses import asdict

from astro_nav import LatitudeEstimator, ProcessingConfig
from astro_nav.config import SolverConfig


def main() -> None:
    parser = argparse.ArgumentParser(description="Night-sky latitude estimation (north/south modes)")
    parser.add_argument("--image", required=True, help="Path to night-sky image")
    parser.add_argument("--mode", required=True, choices=["north", "south"], help="Hemisphere mode")
    parser.add_argument("--vfov", type=float, default=60.0, help="Vertical field-of-view in degrees")
    parser.add_argument("--hfov", type=float, default=70.0, help="Horizontal field-of-view in degrees")
    parser.add_argument("--pitch", type=float, default=0.0, help="Camera pitch in degrees")
    parser.add_argument("--roll", type=float, default=0.0, help="Camera roll in degrees")
    parser.add_argument("--expected-lat", type=float, default=None, help="Expected latitude hint for sanity checks")
    parser.add_argument("--debug", action="store_true", help="Print debug outputs for SCP/Crux altitude")
    args = parser.parse_args()

    cfg = ProcessingConfig(solver=SolverConfig(
        vertical_fov_deg=args.vfov,
        horizontal_fov_deg=args.hfov,
        camera_pitch_deg=args.pitch,
        camera_roll_deg=args.roll,
        expected_latitude_deg=args.expected_lat,
        debug=args.debug,
    ))
    estimator = LatitudeEstimator(cfg)
    result = estimator.process_file(args.image, args.mode)
    print(json.dumps(asdict(result), indent=2))


if __name__ == "__main__":
    main()
