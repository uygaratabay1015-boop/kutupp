from __future__ import annotations

from typing import List

import cv2
import numpy as np

from .config import DetectionConfig
from .types import DetectedStar


class StarDetector:
    def __init__(self, config: DetectionConfig) -> None:
        self.config = config

    def detect(self, image_bgr: np.ndarray) -> List[DetectedStar]:
        if image_bgr is None or image_bgr.size == 0:
            raise ValueError("image_bgr is empty")

        gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
        denoised = cv2.GaussianBlur(gray, (3, 3), 0)
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        enhanced = clahe.apply(denoised)

        _, binary = cv2.threshold(
            enhanced,
            self.config.min_threshold,
            255,
            cv2.THRESH_BINARY,
        )
        kernel = np.ones((2, 2), np.uint8)
        binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel, iterations=1)

        num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(binary, connectivity=8)
        stars: List[DetectedStar] = []
        for idx in range(1, num_labels):
            area = int(stats[idx, cv2.CC_STAT_AREA])
            if area < self.config.min_blob_area_px or area > self.config.max_blob_area_px:
                continue
            x = float(centroids[idx][0])
            y = float(centroids[idx][1])
            left = int(stats[idx, cv2.CC_STAT_LEFT])
            top = int(stats[idx, cv2.CC_STAT_TOP])
            w = int(stats[idx, cv2.CC_STAT_WIDTH])
            h = int(stats[idx, cv2.CC_STAT_HEIGHT])

            roi = gray[top : top + h, left : left + w]
            if roi.size == 0:
                continue
            brightness = float(np.max(roi))
            radius = float(np.sqrt(area / np.pi))
            stars.append(DetectedStar(x=x, y=y, brightness=brightness, radius_px=radius))

        stars.sort(key=lambda s: s.brightness, reverse=True)
        return stars[: self.config.top_star_limit]

