import unittest

from astro_nav.config import SouthConfig
from astro_nav.south import SouthPoleFinder
from astro_nav.types import DetectedStar


class SouthGeometryTest(unittest.TestCase):
    def test_crux_pointer_pipeline_finds_scp(self) -> None:
        # Synthetic layout (roughly Crux-like) for geometry validation.
        stars = [
            DetectedStar(x=420, y=180, brightness=240, radius_px=2.0),  # Gacrux
            DetectedStar(x=450, y=300, brightness=245, radius_px=2.3),  # Acrux
            DetectedStar(x=395, y=245, brightness=210, radius_px=2.0),
            DetectedStar(x=485, y=240, brightness=205, radius_px=2.0),
            DetectedStar(x=560, y=230, brightness=250, radius_px=2.2),  # Alpha Cen (approx)
            DetectedStar(x=610, y=270, brightness=235, radius_px=2.1),  # Beta Cen (approx)
            DetectedStar(x=300, y=120, brightness=160, radius_px=1.2),
            DetectedStar(x=700, y=100, brightness=150, radius_px=1.1),
            DetectedStar(x=760, y=310, brightness=140, radius_px=1.0),
        ]
        solver = SouthPoleFinder(SouthConfig())
        result = solver.solve(stars=stars, image_w=900, image_h=500)
        self.assertIsNotNone(result)
        assert result is not None
        self.assertGreater(result.confidence, 0.40)
        self.assertEqual(len(result.patterns), 2)


if __name__ == "__main__":
    unittest.main()

