import unittest

import cv2
import numpy as np

from astro_nav.config import DetectionConfig
from astro_nav.detection import StarDetector


class StarDetectorTest(unittest.TestCase):
    def test_detects_synthetic_stars(self) -> None:
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        points = [(100, 100), (220, 180), (320, 250), (500, 120), (580, 300)]
        for x, y in points:
            cv2.circle(img, (x, y), 2, (255, 255, 255), thickness=-1)

        detector = StarDetector(DetectionConfig(min_threshold=120))
        stars = detector.detect(img)
        self.assertGreaterEqual(len(stars), 4)


if __name__ == "__main__":
    unittest.main()

