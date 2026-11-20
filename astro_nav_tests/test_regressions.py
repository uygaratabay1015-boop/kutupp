import math
import os
import sys
import tempfile
import types
import unittest

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from astro_nav.camera_model import CameraModel
from astro_nav.config import DetectionConfig
from astro_nav.detection import StarDetector
from compass import CompassSensor
from latitude_solver import calculate_latitude_with_error_bounds
from map_viewer import calculate_longitude_from_time
from star_detection import get_star_brightness


def _elevation_by_rotation(nx, ny, pitch_deg):
    """Bagimsiz referans: isin vektorunu rotasyon matrisiyle cevirip yukseklik hesapla."""
    p = math.radians(pitch_deg)
    ray = np.array([nx, ny, 1.0])
    # x ekseni etrafinda pitch kadar yukari cevir (x: sag, y: yukari, z: ileri)
    rot = np.array([
        [1, 0, 0],
        [0, math.cos(p), math.sin(p)],
        [0, -math.sin(p), math.cos(p)],
    ])
    world = rot @ ray
    return math.degrees(math.asin(world[1] / np.linalg.norm(world)))


class CameraModelTest(unittest.TestCase):
    def test_off_axis_point_matches_3d_rotation(self) -> None:
        w, h = 1280, 720
        model = CameraModel(vertical_fov_deg=60.0, horizontal_fov_deg=90.0, pitch_deg=40.0, roll_deg=0.0)
        x, y = 1100.0, 200.0
        fx = (w / 2) / math.tan(math.radians(45.0))
        fy = (h / 2) / math.tan(math.radians(30.0))
        nx, ny = (x - w / 2) / fx, (h / 2 - y) / fy
        expected = _elevation_by_rotation(nx, ny, 40.0)
        got = model.altitude_from_pixel(x, y, w, h)
        # Kirilma duzeltmesi (en fazla ~0.3 deg) haric tam eslesmeli
        self.assertLess(abs(got - expected), 0.5)
        # Eski "pitch + atan(ny)" yaklasimi burada ~3 deg sapiyordu
        old = 40.0 + math.degrees(math.atan(ny))
        self.assertGreater(abs(old - expected), 1.5)

    def test_center_pixel_equals_pitch(self) -> None:
        model = CameraModel(60.0, 70.0, 35.0, 0.0)
        alt = model.altitude_from_pixel(640.0, 360.0, 1280, 720)
        self.assertAlmostEqual(alt, 35.0, delta=0.5)


class LatitudeSolverRootTest(unittest.TestCase):
    def test_x_offset_changes_altitude_consistently(self) -> None:
        res_center = calculate_latitude_with_error_bounds(
            polaris_pixel_y=200, image_height=720, vertical_fov=60, camera_tilt=40,
            polaris_pixel_x=640, image_width=1280,
        )
        res_old = calculate_latitude_with_error_bounds(
            polaris_pixel_y=200, image_height=720, vertical_fov=60, camera_tilt=40,
        )
        # Merkez dikeyinde yeni (x'li) ve eski (x'siz) hesap ayni olmali
        self.assertAlmostEqual(res_center['latitude'], res_old['latitude'], places=2)

        res_off = calculate_latitude_with_error_bounds(
            polaris_pixel_y=200, image_height=720, vertical_fov=60, camera_tilt=40,
            polaris_pixel_x=1200, image_width=1280,
        )
        self.assertLess(res_off['latitude'], res_center['latitude'])

    def test_south_latitude_negative(self) -> None:
        res = calculate_latitude_with_error_bounds(
            polaris_pixel_y=300, image_height=720, vertical_fov=60, camera_tilt=30,
            hemisphere='south', polaris_pixel_x=700, image_width=1280,
        )
        self.assertLess(res['latitude'], 0.0)


class CompassTest(unittest.TestCase):
    def test_negative_and_wrapped_azimuth_normalized(self) -> None:
        self.assertEqual(CompassSensor(azimuth=-30).get_cardinal_direction(), "KuzeyBatı")
        self.assertAlmostEqual(CompassSensor(azimuth=-30).get_azimuth(), 330.0)
        self.assertAlmostEqual(CompassSensor(azimuth=370).get_azimuth(), 10.0)
        self.assertAlmostEqual(CompassSensor(azimuth=-30).get_deviation_from_north(), -30.0)
        self.assertTrue(CompassSensor(azimuth=-10).is_facing_north())
        self.assertFalse(CompassSensor(azimuth=-30).is_facing_north())


class LongitudeTest(unittest.TestCase):
    def test_midnight_assumption_sign(self) -> None:
        # Gece yarisi (yerel) UTC 21:30 ise gozlemci DOGUDA olmali (+37.5)
        lon, err = calculate_longitude_from_time(21, 30)
        self.assertAlmostEqual(lon, 37.5)
        self.assertGreater(err, 2.0)
        # UTC 00:00 yerel gece yarisi -> Greenwich
        self.assertAlmostEqual(calculate_longitude_from_time(0, 0)[0], 0.0)
        # UTC 06:00 yerel gece yarisi -> 90 deg BATI
        self.assertAlmostEqual(calculate_longitude_from_time(6, 0)[0], -90.0)


class PlateSolverParsingTest(unittest.TestCase):
    def _run(self, solver_result):
        fake = types.ModuleType("tetra3")

        class _T3:
            def solve_from_image(self, image, **kwargs):
                assert image.mode == "L"
                return solver_result

            def load_database(self, path):
                pass

        fake.Tetra3 = _T3
        sys.modules["tetra3"] = fake
        try:
            from offline_plate_solver import OfflinePlateSolver
            with tempfile.TemporaryDirectory() as d:
                path = os.path.join(d, "x.png")
                cv2.imwrite(path, np.zeros((40, 40, 3), dtype=np.uint8))
                return OfflinePlateSolver().solve_image(path, fov_estimate_deg=20.0)
        finally:
            sys.modules.pop("tetra3", None)

    def test_uppercase_keys_and_degrees_preserved(self) -> None:
        res = self._run({"RA": 5.0, "Dec": 0.5, "Roll": 1.0, "FOV": 12.0, "T_solve": 7.0})
        self.assertTrue(res.success)
        self.assertAlmostEqual(res.ra_deg, 5.0)
        self.assertAlmostEqual(res.dec_deg, 0.5)
        self.assertAlmostEqual(res.roll_deg, 1.0)
        self.assertAlmostEqual(res.fov_deg, 12.0)
        self.assertAlmostEqual(res.solve_time_ms, 7.0)

    def test_failed_solution(self) -> None:
        res = self._run({"RA": None, "Dec": None, "T_solve": 3.0})
        self.assertFalse(res.success)
        res2 = self._run(None)
        self.assertFalse(res2.success)


class DetectionTest(unittest.TestCase):
    def test_high_resolution_large_stars_detected(self) -> None:
        # 12 MP benzeri goruntu: yildizlar ~10 px capinda (alan ~300 px) -> eski sabit
        # 180 px siniri bunlari eliyordu.
        img = np.zeros((3000, 4000, 3), dtype=np.uint8)
        pts = [(500, 400), (1500, 900), (2500, 1500), (3300, 700), (3600, 2400), (900, 2600)]
        for x, y in pts:
            cv2.circle(img, (x, y), 10, (255, 255, 255), thickness=-1)
        stars = StarDetector(DetectionConfig()).detect(img)
        self.assertGreaterEqual(len(stars), 5)

    def test_grayscale_input_supported(self) -> None:
        img = np.zeros((480, 640), dtype=np.uint8)
        for x, y in [(100, 100), (220, 180), (320, 250), (500, 120), (580, 300)]:
            cv2.circle(img, (x, y), 2, 255, thickness=-1)
        stars = StarDetector(DetectionConfig(min_threshold=120)).detect(img)
        self.assertGreaterEqual(len(stars), 4)


class StarBrightnessTest(unittest.TestCase):
    def test_roi_mask_matches_full_mask(self) -> None:
        img = np.zeros((200, 300), dtype=np.uint8)
        cv2.circle(img, (150, 100), 5, 200, thickness=-1)
        contours, _ = cv2.findContours((img > 0).astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cnt = contours[0]
        x, y, w, h = cv2.boundingRect(cnt)
        got = get_star_brightness(img, cnt, x, y, w, h)
        self.assertAlmostEqual(got, 200.0, delta=1.0)


if __name__ == "__main__":
    unittest.main()
