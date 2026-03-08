#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import math

random_available = True
try:
    import random
except ImportError:
    random_available = False


class CompassSensor:

    def __init__(self, mode="mock", azimuth=0.0):
        self.mode = mode
        self.azimuth = azimuth
        self.noise = 0.0

    def get_azimuth(self, add_noise=False):
        if self.mode == "mock":
            az = self.azimuth
        else:
            az = self.azimuth

        if add_noise and random_available:
            noise = random.gauss(0, 2)
            az = (az + noise) % 360

        return az

    def set_azimuth(self, azimuth):
        self.azimuth = azimuth % 360

    def is_facing_north(self, tolerance=15):
        az = self.get_azimuth()
        north_min = 360 - tolerance
        north_max = tolerance
        return az >= north_min or az <= north_max

    def get_cardinal_direction(self):
        az = self.get_azimuth()
        directions = [
            "Kuzey",
            "KuzeyDoğu",
            "Doğu",
            "GüneyDoğu",
            "Güney",
            "GüneyBatı",
            "Batı",
            "KuzeyBatı"
        ]
        index = int((az + 22.5) / 45) % 8
        return directions[index]

    def get_deviation_from_north(self):
        az = self.get_azimuth()
        if az <= 180:
            return az
        else:
            return az - 360

    def get_correction_angle(self):
        return -self.get_deviation_from_north()

    def get_polaris_x_correction(self, image_width, horizontal_fov):
        deviation = self.get_deviation_from_north()
        fov_rad = math.radians(horizontal_fov)
        half_width = image_width / 2.0
        focal_length_px = half_width / math.tan(fov_rad / 2.0)
        offset_rad = math.radians(deviation)
        x_offset = focal_length_px * math.tan(offset_rad)
        return x_offset


class CompassCalibrator:

    def __init__(self):
        self.readings = []
        self.expected_value = 0.0

    def collect_reading(self, azimuth):
        self.readings.append(azimuth)

    def calibrate(self, expected_azimuth=0):
        if not self.readings:
            return 0.0
        average = sum(self.readings) / len(self.readings)
        offset = expected_azimuth - average
        self.readings = []
        return offset


def test_compass():
    print("\n" + "="*60)
    print("🧭 PUSULA SENSÖRÜ TESTİ")
    print("="*60 + "\n")

    test_cases = [
        (0,   "KUZEYE"),
        (90,  "DOĞUYA"),
        (180, "GÜNEYE"),
        (225, "GÜNEYBATIYA"),
        (315, "KUZEYBATIYA"),
    ]

    for azimuth, label in test_cases:
        c = CompassSensor(mode="mock", azimuth=azimuth)
        print(f"✓ Telefon {label} bakıyor:")
        print(f"  Azimuth:          {c.get_azimuth()}°")
        print(f"  Yön:              {c.get_cardinal_direction()}")
        print(f"  Kuzeye bakıyor:   {c.is_facing_north()}")
        print(f"  Sapma:            {c.get_deviation_from_north():.1f}°")
        x_off = c.get_polaris_x_correction(image_width=1080, horizontal_fov=60)
        print(f"  Polaris X kayma:  {x_off:.1f} piksel\n")

    print("="*60)
    print("✅ Pusula Sensörü Çalışıyor!")
    print("="*60 + "\n")


if __name__ == "__main__":
    test_compass()