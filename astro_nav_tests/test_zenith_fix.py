import math
import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from astro_nav.zenith_fix import (
    combine_fixes,
    great_circle_km,
    greenwich_apparent_sidereal_time,
    julian_date_utc,
    monte_carlo_error,
    precession_nutation_matrix,
    rotation_from_radec_roll,
    solve_position,
)


def _simulate(lat, lon, when, az, alt, roll_deg):
    """Bagimsiz ileri model: bilinen konumdan (yonelim matrisi, up_cam) uret."""
    jd = julian_date_utc(when)
    lst = greenwich_apparent_sidereal_time(jd) + math.radians(lon)
    p, l = math.radians(lat), lst
    east = np.array([-math.sin(l), math.cos(l), 0.0])
    north = np.array([-math.sin(p) * math.cos(l), -math.sin(p) * math.sin(l), math.cos(p)])
    up = np.array([math.cos(p) * math.cos(l), math.cos(p) * math.sin(l), math.sin(p)])
    a, h = math.radians(az), math.radians(alt)
    f = math.cos(h) * math.sin(a) * east + math.cos(h) * math.cos(a) * north + math.sin(h) * up
    u0 = up - np.dot(up, f) * f
    u0 /= np.linalg.norm(u0)                       # goruntu-yukari (roll=0)
    r = math.radians(roll_deg)
    u = math.cos(r) * u0 + math.sin(r) * np.cross(f, u0)
    x = np.cross(f, u)                              # sag
    cam_date = np.column_stack([x, -u, f])          # kamera -> tarih ekvatoru
    cam_icrs = precession_nutation_matrix(jd).T @ cam_date
    up_cam = cam_date.T @ up
    return cam_icrs, up_cam


class AstronomyReferenceTest(unittest.TestCase):
    def test_julian_date_j2000(self) -> None:
        self.assertAlmostEqual(julian_date_utc(datetime(2000, 1, 1, 12, tzinfo=timezone.utc)), 2451545.0, places=6)
        self.assertAlmostEqual(julian_date_utc(datetime(2026, 11, 20, 0, tzinfo=timezone.utc)), 2461364.5, places=6)

    def test_gmst_known_value(self) -> None:
        # 2000-01-01 12:00 UT1: GMST = 18h41m50.5484s = 280.46062 deg
        jd = julian_date_utc(datetime(2000, 1, 1, 12, tzinfo=timezone.utc))
        deg = math.degrees(greenwich_apparent_sidereal_time(jd))
        self.assertAlmostEqual(deg, 280.4606 + (-0.0043) , delta=0.01)   # +/- nutasyon (~ -1.1 s/15)
        # 2000-01-01 00:00 UT: GMST ~ 6h39m51.6s = 99.965 deg
        jd0 = julian_date_utc(datetime(2000, 1, 1, tzinfo=timezone.utc))
        self.assertAlmostEqual(math.degrees(greenwich_apparent_sidereal_time(jd0)), 99.965, delta=0.01)

    def test_precession_rates_match_textbook(self) -> None:
        # RA=0, Dec=0 yildizi: dDec/yil = +20.04" cos(RA), dRA/yil = 46.1" (+ 20.04" sinRA tanDec)
        when = datetime(2026, 11, 20, tzinfo=timezone.utc)
        jd = julian_date_utc(when)
        years = (jd - 2451545.0) / 365.25
        v = precession_nutation_matrix(jd) @ np.array([1.0, 0.0, 0.0])
        dec = math.degrees(math.asin(v[2]))
        ra = math.degrees(math.atan2(v[1], v[0]))
        self.assertAlmostEqual(dec, 20.04 * years / 3600.0, delta=0.004)
        self.assertAlmostEqual(ra, 46.1 * years / 3600.0, delta=0.004)

    def test_matrix_is_rotation(self) -> None:
        m = precession_nutation_matrix(2461363.5)
        np.testing.assert_allclose(m @ m.T, np.eye(3), atol=1e-12)
        self.assertAlmostEqual(np.linalg.det(m), 1.0, places=12)


class WorldwideRoundTripTest(unittest.TestCase):
    def test_random_locations_worldwide(self) -> None:
        rng = np.random.default_rng(7)
        worst = 0.0
        for _ in range(500):
            lat = math.degrees(math.asin(rng.uniform(-1, 1)))        # kureye homojen
            lon = rng.uniform(-180, 180)
            when = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=float(rng.uniform(0, 3.15e7)))
            r, up = _simulate(lat, lon, when, rng.uniform(0, 360), rng.uniform(10, 80), rng.uniform(-180, 180))
            fix = solve_position(r, up, when)
            worst = max(worst, great_circle_km(lat, lon, fix.latitude_deg, fix.longitude_deg))
        self.assertLess(worst, 0.01)      # < 10 m (saf geometrik tutarlilik)

    def test_special_places(self) -> None:
        when = datetime(2026, 6, 21, 21, 30, tzinfo=timezone.utc)
        places = {
            "Istanbul": (41.0082, 28.9784), "Sydney": (-33.8688, 151.2093),
            "Buenos Aires": (-34.6037, -58.3816), "Tarih cizgisi +": (0.0, 179.99),
            "Tarih cizgisi -": (-10.0, -179.99), "Greenwich": (51.4779, 0.0),
            "Kuzey kutbuna yakin": (89.5, 40.0), "Guney kutbuna yakin": (-89.5, -120.0),
            "Ekvator": (0.0, 0.0),
        }
        for name, (lat, lon) in places.items():
            r, up = _simulate(lat, lon, when, 123.0, 45.0, 17.0)
            fix = solve_position(r, up, when)
            self.assertLess(great_circle_km(lat, lon, fix.latitude_deg, fix.longitude_deg), 0.01, name)

    def test_one_second_time_error_is_small(self) -> None:
        when = datetime(2026, 3, 3, 1, 0, tzinfo=timezone.utc)
        r, up = _simulate(41.0, 29.0, when, 10.0, 40.0, 0.0)
        fix = solve_position(r, up, when + timedelta(seconds=1))
        km = great_circle_km(41.0, 29.0, fix.latitude_deg, fix.longitude_deg)
        # 1 sn = 15 yay-saniyesi boylam => 111.2 km/deg * cos(41) * (15/3600) deg ~ 0.35 km
        expected = 111.195 * math.cos(math.radians(41.0)) * (15.0 / 3600.0)
        self.assertAlmostEqual(km, expected, delta=0.05)


class RollConventionTest(unittest.TestCase):
    def test_roll_zero_has_north_up_and_east_left(self) -> None:
        r = rotation_from_radec_roll(100.0, 20.0, 0.0)
        np.testing.assert_allclose(r.T @ r, np.eye(3), atol=1e-12)
        self.assertAlmostEqual(np.linalg.det(r), 1.0, places=12)
        a, d = math.radians(100.0), math.radians(20.0)
        east = np.array([-math.sin(a), math.cos(a), 0.0])
        north = np.array([-math.sin(d) * math.cos(a), -math.sin(d) * math.sin(a), math.cos(d)])
        np.testing.assert_allclose(-r[:, 1], north, atol=1e-12)    # goruntu yukarisi = kuzey
        np.testing.assert_allclose(-r[:, 0], east, atol=1e-12)     # sag = bati => dogu solda


class ErrorBudgetTest(unittest.TestCase):
    def test_tilt_error_dominates(self) -> None:
        when = datetime(2026, 5, 5, 22, 0, tzinfo=timezone.utc)
        r, up = _simulate(41.0, 29.0, when, 0.0, 41.0, 0.0)
        good = monte_carlo_error(r, up, when, sigma_tilt_deg=0.1, n=600)
        bad = monte_carlo_error(r, up, when, sigma_tilt_deg=1.0, n=600)
        self.assertLess(good["p68_km"], bad["p68_km"])
        # 1 deg ~ 111 km; iki eksenli tilt icin p68 ~ 1.5 deg civari
        self.assertGreater(bad["p68_km"], 80.0)
        self.assertLess(bad["p68_km"], 250.0)
        self.assertAlmostEqual(good["nominal"].latitude_deg, 41.0, places=3)

    def test_combine_handles_dateline(self) -> None:
        from astro_nav.zenith_fix import PositionFix
        f = [PositionFix(0.0, 179.9, 0, 0), PositionFix(0.0, -179.9, 0, 0)]
        c = combine_fixes(f)
        self.assertAlmostEqual(abs(c.longitude_deg), 180.0, delta=0.01)


if __name__ == "__main__":
    unittest.main()


class InputHelpersTest(unittest.TestCase):
    def test_up_from_tilt_geometry(self) -> None:
        from astro_nav.zenith_fix import up_from_tilt, boresight_altitude_deg
        np.testing.assert_allclose(up_from_tilt(0, 0), [0, -1, 0], atol=1e-12)      # ufuk: zenit = goruntu yukarisi
        np.testing.assert_allclose(up_from_tilt(90, 0), [0, 0, 1], atol=1e-12)      # zenite bakis
        self.assertLess(up_from_tilt(30, 20)[0], 0)                                 # saat yonu roll: zenit sola kayar
        self.assertAlmostEqual(boresight_altitude_deg(np.eye(3), up_from_tilt(37, 11)), 37.0, places=6)

    def test_gravity_sign(self) -> None:
        from astro_nav.zenith_fix import up_from_gravity
        np.testing.assert_allclose(up_from_gravity([0, -9.81, 0]), [0, -1, 0])
        np.testing.assert_allclose(up_from_gravity([0, 9.81, 0], sensor_points_up=False), [0, -1, 0])

    def test_roll_sign_calibration_picks_right_sign(self) -> None:
        from astro_nav.zenith_fix import choose_roll_sign
        when = datetime(2026, 11, 20, 21, 5, tzinfo=timezone.utc)
        lat, lon = -33.87, 151.21
        r, up = _simulate(lat, lon, when, 200.0, 40.0, 25.0)
        # "solver" r'den RA/Dec/Roll cikarir; kural +1 ile uretildi
        f = r[:, 2]
        ra = math.degrees(math.atan2(f[1], f[0])) % 360
        dec = math.degrees(math.asin(f[2]))
        a, d = math.radians(ra), math.radians(dec)
        north = np.array([-math.sin(d) * math.cos(a), -math.sin(d) * math.sin(a), math.cos(d)])
        east = np.array([-math.sin(a), math.cos(a), 0.0])
        u = -r[:, 1]
        roll = math.degrees(math.atan2(np.dot(u, east), np.dot(u, north)))
        res = choose_roll_sign(ra, dec, roll, up, when, lat, lon)
        self.assertEqual(res["best_roll_sign"], 1.0)
        self.assertLess(res["error_km"], 0.05)
        self.assertTrue(res["decisive"])
        # tersine uretilmis cozum -1 secilmeli
        res2 = choose_roll_sign(ra, dec, -roll, up, when, lat, lon)
        self.assertEqual(res2["best_roll_sign"], -1.0)

    def test_warnings(self) -> None:
        from astro_nav.zenith_fix import quality_warnings, up_from_tilt
        when = datetime(2026, 1, 1, tzinfo=timezone.utc)
        w = quality_warnings(np.eye(3), up_from_tilt(-5, 0), when, when)
        self.assertTrue(any("ALTINA" in m for m in w))
        w = quality_warnings(np.eye(3), up_from_tilt(5, 0), when, when)
        self.assertTrue(any("ufka cok yakin" in m for m in w))
        self.assertEqual(quality_warnings(np.eye(3), up_from_tilt(45, 0), when, when), [])


class TiltCalibrationTest(unittest.TestCase):
    def _biased(self, lat, lon, when, az, alt, roll, bias_rot, rng, noise_deg):
        from astro_nav.zenith_fix import _small_rotation
        r, up = _simulate(lat, lon, when, az, alt, roll)
        up_meas = bias_rot @ up
        if noise_deg:
            up_meas = _small_rotation(rng, math.radians(noise_deg)) @ up_meas
        return r, up, up_meas

    def test_bias_removed_and_worldwide_error_drops(self) -> None:
        from astro_nav.zenith_fix import (apply_tilt_offset, calibrate_tilt_offset,
                                          great_circle_km)
        rng = np.random.default_rng(3)
        # Sabit sensor-kamera hizasizligi: ~0.9 derece
        w = np.radians([0.6, -0.5, 0.3]); k = np.array([[0, -w[2], w[1]], [w[2], 0, -w[0]], [-w[1], w[0], 0]])
        bias = np.eye(3) + k + k @ k / 2
        u, _, vt = np.linalg.svd(bias); bias = u @ vt

        t0 = datetime(2026, 11, 20, 21, 5, tzinfo=timezone.utc)
        r, _, meas = self._biased(41.0082, 28.9784, t0, 150.0, 45.0, 8.0, bias, rng, 0.0)
        from astro_nav.zenith_fix import true_up_cam
        cal = calibrate_tilt_offset([(r, meas, t0, 41.0082, 28.9784)])
        self.assertLess(cal["residual_deg"], 1e-6)
        self.assertAlmostEqual(cal["offset_deg"], math.degrees(math.acos((np.trace(bias) - 1) / 2)), delta=0.2)

        before, after = [], []
        for lat, lon in [(-33.87, 151.21), (64.14, -21.9), (-17.7, 179.9), (-0.18, -78.47), (35.0, 139.0)]:
            when = t0 + timedelta(hours=float(rng.uniform(0, 8)), days=float(rng.integers(0, 200)))
            r2, _, m2 = self._biased(lat, lon, when, rng.uniform(0, 360), rng.uniform(25, 75),
                                     rng.uniform(-40, 40), bias, rng, 0.0)
            a = solve_position(r2, m2, when)
            b = solve_position(r2, apply_tilt_offset(cal["Q"], m2), when)
            before.append(great_circle_km(lat, lon, a.latitude_deg, a.longitude_deg))
            after.append(great_circle_km(lat, lon, b.latitude_deg, b.longitude_deg))
        self.assertGreater(min(before), 40.0)          # kalibrasyonsuz: ~100 km
        # Tek kare, sapmanin zenit ekseni etrafindaki bileseni goremez (gozlenemez);
        # yine de ortalama hata kalibrasyonsuzun yarisindan azina iner.
        self.assertLess(float(np.mean(after)), 0.5 * float(np.mean(before)))

    def test_multi_frame_calibration_beats_single_noisy_frame(self) -> None:
        from astro_nav.zenith_fix import apply_tilt_offset, calibrate_tilt_offset, great_circle_km
        rng = np.random.default_rng(11)
        w = np.radians([0.4, 0.5, -0.2]); k = np.array([[0, -w[2], w[1]], [w[2], 0, -w[0]], [-w[1], w[0], 0]])
        bias = np.eye(3) + k + k @ k / 2
        u, _, vt = np.linalg.svd(bias); bias = u @ vt
        t0 = datetime(2026, 3, 3, 22, 0, tzinfo=timezone.utc)
        obs = []
        for i in range(12):                              # 12 gurultulu kalibrasyon karesi (0.3 deg)
            r, _, m = self._biased(41.0, 29.0, t0 + timedelta(minutes=5 * i), rng.uniform(0, 360),
                                   rng.uniform(25, 75), rng.uniform(-30, 30), bias, rng, 0.3)
            obs.append((r, m, t0 + timedelta(minutes=5 * i), 41.0, 29.0))
        cal12 = calibrate_tilt_offset(obs)
        cal1 = calibrate_tilt_offset(obs[:1])
        e1, e12 = [], []
        for _ in range(40):                              # baska konum, gurultusuz test
            lat, lon = math.degrees(math.asin(rng.uniform(-0.9, 0.9))), rng.uniform(-180, 180)
            when = t0 + timedelta(days=float(rng.uniform(1, 300)), hours=float(rng.uniform(0, 8)))
            r, _, m = self._biased(lat, lon, when, rng.uniform(0, 360), rng.uniform(25, 75),
                                   rng.uniform(-30, 30), bias, rng, 0.0)
            for cal, store in ((cal1, e1), (cal12, e12)):
                f = solve_position(r, apply_tilt_offset(cal["Q"], m), when)
                store.append(great_circle_km(lat, lon, f.latitude_deg, f.longitude_deg))
        self.assertLess(np.median(e12), np.median(e1))   # cok kare > tek gurultulu kare
        # Beklenen: 0.3 deg gurultu / sqrt(12) ~ 0.12 deg ~ 15-20 km
        self.assertLess(np.median(e12), 30.0)
