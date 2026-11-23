"""Zenit tabanli konum cozucu (dunyanin her yerinde calisir).

Fikir
-----
1. Plate solve, kameranin gokyuzundeki yonelimini verir: kamera -> ICRS (J2000) donusumu.
2. Telefonun yercekimi sensoru, yerel dikey (zenit) yonunu KAMERA eksenlerinde verir.
3. Zenit yonunu ICRS'e cevirip "tarih ekvatoruna" (presesyon + nutasyon) tasiyinca:
       enlem  = zenitin deklinasyonu
       boylam = zenitin sag acikligi - GAST (gorunur yildiz zamani)   [dogu +]
   Polaris'e, yildiz desenine veya yarimkureye BAGLI DEGILDIR.

Eksen kurali (OpenCV): kamera x = goruntu sagi, y = goruntu asagisi, z = bakis yonu (sag-el).
Kullanici girdileri: yonelim (3x3 matris veya RA/Dec/Roll), yukari-vektor (kamera ekseninde,
zenite dogru, birim), UTC zamani.

Hata kaynaklari (kabaca): sensor egimi (0.3-1 deg => 30-110 km) >> plate solve (~0.02 deg) >>
zaman (1 sn = 0.4 km). Bkz. `monte_carlo_error`.
Ihmal edilenler: sapma (aberasyon, <=20"), UT1-UTC (<=0.9 sn), dikey sapmasi (~10"),
atmosferik kirilma (plate solve'a katkisi kucuk).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Iterable, Optional, Sequence

import numpy as np

ARCSEC = math.pi / (180.0 * 3600.0)
EARTH_RADIUS_KM = 6371.0088


# ---------------------------------------------------------------- zaman / astronomi

def julian_date_utc(when: datetime) -> float:
    """UTC zamanindan Julian Date (UT1 ~ UTC kabul edilir)."""
    if when.tzinfo is None:
        raise ValueError("Zaman dilimi bilgisi (UTC) gerekli: datetime(..., tzinfo=timezone.utc)")
    when = when.astimezone(timezone.utc)
    y, m = when.year, when.month
    if m <= 2:
        y -= 1
        m += 12
    a = y // 100
    b = 2 - a + a // 4
    day_fraction = (when.hour + (when.minute + (when.second + when.microsecond / 1e6) / 60.0) / 60.0) / 24.0
    return (math.floor(365.25 * (y + 4716)) + math.floor(30.6001 * (m + 1))
            + when.day + day_fraction + b - 1524.5)


def _rx(a: float) -> np.ndarray:
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, s], [0, -s, c]])


def _rz(a: float) -> np.ndarray:
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, s, 0], [-s, c, 0], [0, 0, 1]])


def _poly(coeffs: Sequence[float], t: float) -> float:
    total = 0.0
    for c in reversed(coeffs):
        total = total * t + c
    return total


def nutation(t: float) -> tuple[float, float]:
    """(dpsi, deps) radyan; Meeus dusuk-duzey seri (~0.5 yay-saniyesi)."""
    omega = math.radians(125.04452 - 1934.136261 * t)
    l_sun = math.radians(280.4665 + 36000.7698 * t)
    l_moon = math.radians(218.3165 + 481267.8813 * t)
    dpsi = (-17.20 * math.sin(omega) - 1.32 * math.sin(2 * l_sun)
            - 0.23 * math.sin(2 * l_moon) + 0.21 * math.sin(2 * omega)) * ARCSEC
    deps = (9.20 * math.cos(omega) + 0.57 * math.cos(2 * l_sun)
            + 0.10 * math.cos(2 * l_moon) - 0.09 * math.cos(2 * omega)) * ARCSEC
    return dpsi, deps


def precession_nutation_matrix(jd: float) -> np.ndarray:
    """ICRS(J2000) -> gercek ekvator/ekinoks (tarih). IAU 2006 Fukushima-Williams + nutasyon."""
    t = (jd - 2451545.0) / 36525.0
    gamb = _poly([-0.052928, 10.556378, 0.4932044, -0.00031238, -0.000002788, 0.0000000260], t) * ARCSEC
    phib = _poly([84381.412819, -46.811016, 0.0511268, 0.00053289, -0.000000440, -0.0000000176], t) * ARCSEC
    psib = _poly([-0.041775, 5038.481484, 1.5584175, -0.00018522, -0.000026452, -0.0000000148], t) * ARCSEC
    epsa = _poly([84381.406, -46.836769, -0.0001831, 0.00200340, -0.000000576, -0.0000000434], t) * ARCSEC
    dpsi, deps = nutation(t)
    return _rx(-(epsa + deps)) @ _rz(-(psib + dpsi)) @ _rx(phib) @ _rz(gamb)


def greenwich_apparent_sidereal_time(jd: float) -> float:
    """GAST (radyan, [0, 2pi)). ERA + presesyon polinomu + nutasyon (esitlik of ekinokslar)."""
    tu = jd - 2451545.0
    t = tu / 36525.0
    era = 2 * math.pi * ((0.7790572732640 + 1.00273781191135448 * tu) % 1.0)
    gmst = era + _poly([0.014506, 4612.156534, 1.3915817, -0.00000044,
                        -0.000029956, -0.0000000368], t) * ARCSEC
    dpsi, _ = nutation(t)
    epsa = _poly([84381.406, -46.836769, -0.0001831, 0.00200340], t) * ARCSEC
    return (gmst + dpsi * math.cos(epsa)) % (2 * math.pi)


# ---------------------------------------------------------------- yonelim

def rotation_from_radec_roll(ra_deg: float, dec_deg: float, roll_deg: float,
                             roll_sign: float = 1.0) -> np.ndarray:
    """Kamera -> ICRS donusum matrisi (sutunlar: kamera x, y, z eksenleri, ICRS'te).

    Roll tanimi: goruntu-yukarisinin "konum acisi" (kuzeyden doguya dogru, derece).
    NOT: plate solver'in (tetra3 vb.) roll tanimi/isareti farkli olabilir; `roll_sign=-1`
    ile cevrilebilir. Gercek bir cozum uzerinde dogrulanmalidir (bkz. docs/ZENITH_FIX.md).
    """
    a, d = math.radians(ra_deg), math.radians(dec_deg)
    th = math.radians(roll_deg) * roll_sign
    f = np.array([math.cos(d) * math.cos(a), math.cos(d) * math.sin(a), math.sin(d)])
    north = np.array([-math.sin(d) * math.cos(a), -math.sin(d) * math.sin(a), math.cos(d)])
    east = np.array([-math.sin(a), math.cos(a), 0.0])
    up = math.cos(th) * north + math.sin(th) * east
    x = np.cross(f, up)          # goruntu sagi
    return np.column_stack([x, -up, f])


@dataclass(frozen=True)
class PositionFix:
    latitude_deg: float
    longitude_deg: float      # dogu +, [-180, 180)
    zenith_ra_deg: float
    zenith_dec_deg: float


def _unit(v: Sequence[float]) -> np.ndarray:
    v = np.asarray(v, dtype=float)
    n = np.linalg.norm(v)
    if n == 0 or not np.isfinite(n):
        raise ValueError("Gecersiz (sifir/NaN) vektor")
    return v / n


def solve_position(cel_from_cam: np.ndarray, up_cam: Sequence[float], when_utc: datetime) -> PositionFix:
    """Konumu bul. `up_cam`: zenit yonu, kamera eksenlerinde (yercekimi sensorunun tersi)."""
    r = np.asarray(cel_from_cam, dtype=float)
    if r.shape != (3, 3) or abs(np.linalg.det(r) - 1.0) > 1e-3:
        raise ValueError("Yonelim matrisi gecerli bir 3x3 dondurme matrisi olmali")
    jd = julian_date_utc(when_utc)
    zen_date = precession_nutation_matrix(jd) @ (r @ _unit(up_cam))
    lat = math.degrees(math.asin(max(-1.0, min(1.0, zen_date[2]))))
    ra = math.atan2(zen_date[1], zen_date[0])
    lon = (math.degrees(ra - greenwich_apparent_sidereal_time(jd)) + 180.0) % 360.0 - 180.0
    return PositionFix(lat, lon, math.degrees(ra) % 360.0,
                       math.degrees(math.asin(max(-1.0, min(1.0, zen_date[2])))))


# ---------------------------------------------------------------- yardimcilar

def great_circle_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(min(1.0, math.sqrt(h)))


def _geo_unit(lat: float, lon: float) -> np.ndarray:
    la, lo = math.radians(lat), math.radians(lon)
    return np.array([math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la)])


def combine_fixes(fixes: Iterable[PositionFix]) -> PositionFix:
    """Birden cok kareyi birlestir (kuresel ortalama; +/-180 sarmasindan etkilenmez)."""
    vs = [_geo_unit(f.latitude_deg, f.longitude_deg) for f in fixes]
    if not vs:
        raise ValueError("En az bir cozum gerekli")
    m = _unit(np.sum(vs, axis=0))
    lat = math.degrees(math.asin(m[2]))
    lon = math.degrees(math.atan2(m[1], m[0]))
    return PositionFix(lat, lon, float("nan"), float("nan"))


def _small_rotation(rng: np.random.Generator, sigma_rad: float) -> np.ndarray:
    w = rng.normal(0.0, sigma_rad, 3)
    k = np.array([[0, -w[2], w[1]], [w[2], 0, -w[0]], [-w[1], w[0], 0]])
    u, _, vt = np.linalg.svd(np.eye(3) + k)   # en yakin dondurme matrisi
    return u @ vt


def monte_carlo_error(cel_from_cam: np.ndarray, up_cam: Sequence[float], when_utc: datetime,
                      sigma_tilt_deg: float = 0.5, sigma_solve_deg: float = 0.03,
                      sigma_time_s: float = 1.0, n: int = 2000, seed: int = 0) -> dict:
    """Sensor/cozum/zaman belirsizligine gore konum hatasi dagilimi (km)."""
    rng = np.random.default_rng(seed)
    nominal = solve_position(cel_from_cam, up_cam, when_utc)
    up0 = _unit(up_cam)
    errs = []
    for _ in range(n):
        up = _small_rotation(rng, math.radians(sigma_tilt_deg)) @ up0
        r = _small_rotation(rng, math.radians(sigma_solve_deg)) @ cel_from_cam
        t = when_utc + timedelta(seconds=float(rng.normal(0.0, sigma_time_s)))
        f = solve_position(r, up, t)
        errs.append(great_circle_km(nominal.latitude_deg, nominal.longitude_deg,
                                    f.latitude_deg, f.longitude_deg))
    errs = np.asarray(errs)
    return {
        "median_km": float(np.median(errs)),
        "p68_km": float(np.percentile(errs, 68)),
        "p95_km": float(np.percentile(errs, 95)),
        "nominal": nominal,
    }


# ---------------------------------------------------------------- girdi yardimcilari

def up_from_tilt(pitch_deg: float, roll_deg: float = 0.0) -> np.ndarray:
    """Egim acilarindan zenit yonu (kamera ekseninde: x sag, y asagi, z bakis).

    pitch_deg: bakis yonunun ufuk ustu yuksekligi (ufka bakis 0, zenite bakis 90).
    roll_deg : kamera ekseni etrafinda donus; + = saat yonu (sag taraf asagi egik).
    """
    p, r = math.radians(pitch_deg), math.radians(roll_deg)
    return _unit([-math.sin(r) * math.cos(p), -math.cos(r) * math.cos(p), math.sin(p)])


def up_from_gravity(sensor_xyz: Sequence[float], sensor_points_up: bool = True) -> np.ndarray:
    """Ivmeolcer/yercekimi okumasindan zenit yonu (zaten kamera ekseninde olmali).

    Android'de duran cihazda TYPE_ACCELEROMETER / TYPE_GRAVITY yukari bakar
    (sensor_points_up=True). Asagi bakan sensorler icin False verin.
    """
    v = _unit(sensor_xyz)
    return v if sensor_points_up else -v


def boresight_altitude_deg(cel_from_cam: np.ndarray, up_cam: Sequence[float]) -> float:
    """Kameranin ufuk ustu yuksekligi: bakis ekseni (0,0,1) ile zenit arasindaki aciya gore."""
    u = _unit(up_cam)
    return math.degrees(math.asin(max(-1.0, min(1.0, float(u[2])))))


def quality_warnings(cel_from_cam: np.ndarray, up_cam: Sequence[float],
                     claimed_utc: datetime, now_utc: Optional[datetime] = None) -> list[str]:
    """Sonucu bozabilecek durumlar icin uyarilar (dunyanin her yerinde gecerli)."""
    warnings: list[str] = []
    alt = boresight_altitude_deg(cel_from_cam, up_cam)
    if alt < 0:
        warnings.append("Kamera ufkun ALTINA bakiyor: egim/isaret hatasi olabilir.")
    elif alt < 10:
        warnings.append("Kamera ufka cok yakin (<10 derece): kirilma plate solve'u bozabilir.")
    if abs(np.linalg.norm(np.asarray(up_cam, dtype=float)) - 1.0) > 0.05:
        warnings.append("Yerçekimi vektoru birim degil (cihaz ivmeleniyor olabilir); sabit tutun.")
    if now_utc is not None and claimed_utc > now_utc + timedelta(minutes=5):
        warnings.append("Gozlem zamani gelecekte: saat/zaman dilimi hatasi olabilir.")
    if claimed_utc.tzinfo is None:
        warnings.append("Zaman dilimi yok: UTC varsayildi.")
    return warnings


def choose_roll_sign(ra_deg: float, dec_deg: float, roll_deg: float, up_cam: Sequence[float],
                     when_utc: datetime, known_lat: float, known_lon: float) -> dict:
    """Bilinen konumdan cekilmis bir fotografla plate solver'in roll isaretini belirle.

    Iki isaret icin de konumu hesaplar; dogru konuma yakin olan dogru kuraldir.
    """
    out = {}
    for sign in (1.0, -1.0):
        fix = solve_position(rotation_from_radec_roll(ra_deg, dec_deg, roll_deg, sign), up_cam, when_utc)
        out[sign] = great_circle_km(known_lat, known_lon, fix.latitude_deg, fix.longitude_deg)
    best = min(out, key=out.get)
    return {"best_roll_sign": best, "error_km": out[best], "other_error_km": out[-best],
            "decisive": out[-best] > 5 * max(out[best], 1.0)}


# ---------------------------------------------------------------- sensor sapmasi kalibrasyonu

def true_up_cam(cel_from_cam: np.ndarray, when_utc: datetime, lat_deg: float, lon_deg: float) -> np.ndarray:
    """Bilinen konum icin zenit yonu (kamera ekseninde) - sensorun OLMASI gereken degeri."""
    jd = julian_date_utc(when_utc)
    p, l = math.radians(lat_deg), math.radians(lon_deg) + greenwich_apparent_sidereal_time(jd)
    zen_date = np.array([math.cos(p) * math.cos(l), math.cos(p) * math.sin(l), math.sin(p)])
    zen_icrs = precession_nutation_matrix(jd).T @ zen_date
    return np.asarray(cel_from_cam, dtype=float).T @ zen_icrs


def _minimal_rotation(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """a'yi b'ye ceviren en kucuk dondurme (Rodrigues)."""
    a, b = _unit(a), _unit(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if c < -1 + 1e-12:
        raise ValueError("Sensor 180 derece ters: isaret/eksen hatasi")
    k = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + k + k @ k / (1.0 + c)


def calibrate_tilt_offset(observations: Sequence[tuple]) -> dict:
    """Sensor-kamera hiza sapmasini (sabit dondurme Q) bilinen konumlu karelerden hesapla.

    observations: (cel_from_cam, olculen_up_cam, when_utc, gercek_lat, gercek_lon) listesi.
    Duzeltme: up_duzeltilmis = Q @ up_olculen. Tek kare yeterlidir (en kucuk dondurme);
    birden cok kare varsa Kabsch/SVD ile en iyi dondurme bulunur ve artik hata raporlanir.
    Sensor kameraya gore sabitse (telefon) Q her karede ve her konumda gecerlidir.

    ONEMLI: Tek kare, sapmanin zenit ekseni etrafindaki bilesenini goremez. Kalibrasyon
    karelerini FARKLI egim ve roll degerlerinde cekin. Olculen davranis (0.3 deg sensor
    gurultusu): 1 kare ~30 km, 12 kare ~15 km, 50 kare ~10 km artik hata.
    """
    meas, true = [], []
    for r, up, when, lat, lon in observations:
        meas.append(_unit(up))
        true.append(_unit(true_up_cam(r, when, lat, lon)))
    if not meas:
        raise ValueError("En az bir kalibrasyon karesi gerekli")
    if len(meas) == 1:
        q = _minimal_rotation(meas[0], true[0])
    else:
        h = sum(np.outer(m, t) for m, t in zip(meas, true))
        u, _, vt = np.linalg.svd(h)
        d = np.sign(np.linalg.det(vt.T @ u.T))
        q = vt.T @ np.diag([1.0, 1.0, d]) @ u.T
    residuals = [math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(q @ m, t)))))) for m, t in zip(meas, true)]
    return {"Q": q, "offset_deg": math.degrees(math.acos(max(-1.0, min(1.0, (np.trace(q) - 1) / 2)))),
            "residual_deg": float(max(residuals)), "frames": len(meas)}


def apply_tilt_offset(q: np.ndarray, up_cam: Sequence[float]) -> np.ndarray:
    return _unit(np.asarray(q, dtype=float) @ _unit(up_cam))
