import unittest

from astro_nav.config import SolverConfig
from astro_nav.latitude import LatitudeSolver


class LatitudeSolverTest(unittest.TestCase):
    def test_south_mode_returns_negative_latitude(self) -> None:
        solver = LatitudeSolver(SolverConfig(vertical_fov_deg=60.0, horizontal_fov_deg=70.0, camera_pitch_deg=30.0))
        alt = solver.pole_altitude_from_pixel(x=640.0, y=360.0, image_w=1280, image_h=720)
        lat = solver.latitude_from_altitude(alt, "south")
        self.assertLess(lat, 0.0)

    def test_north_mode_returns_positive_latitude(self) -> None:
        solver = LatitudeSolver(SolverConfig(vertical_fov_deg=60.0, horizontal_fov_deg=70.0, camera_pitch_deg=42.0))
        alt = solver.pole_altitude_from_pixel(x=640.0, y=360.0, image_w=1280, image_h=720)
        lat = solver.latitude_from_altitude(alt, "north")
        self.assertGreater(lat, 0.0)


if __name__ == "__main__":
    unittest.main()

