#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import math

POLARIS_DECLINATION = 89.264        # Kuzey kutup yıldızı
SIGMA_OCTANTIS_DECLINATION = -88.57 # Güney kutup yıldızı


def pixel_to_degrees(pixel_offset, image_height, vertical_fov):
    fov_rad = math.radians(vertical_fov)
    half_height = image_height / 2.0
    focal_length_px = half_height / math.tan(fov_rad / 2.0)
    angle_rad = math.atan2(pixel_offset, focal_length_px)
    return math.degrees(angle_rad)


def _altitude_from_pixel(polaris_pixel_y, image_height, vertical_fov,
                         camera_tilt=0.0, polaris_pixel_x=None, image_width=None):
    """
    Ufuk ustu yuksekligi (derece).

    Sadece dikey ofset verilirse (x/genislik yok) merkez dikeyi icin tam sonuc verir.
    x ve genislik de verilirse tam 3B donusum kullanilir: kare piksel varsayimiyla
    (fx = fy) kamera ekseni `camera_tilt` kadar yukari cevrilmis kabul edilir.
    Eski "tilt + atan(ny)" formulu yatay ofsette birkac derece hata veriyordu.
    """
    center_y = image_height / 2.0
    pixel_offset = center_y - polaris_pixel_y

    if polaris_pixel_x is None or not image_width:
        angle_from_center = pixel_to_degrees(pixel_offset, image_height, vertical_fov)
        return camera_tilt + angle_from_center

    fov_rad = math.radians(vertical_fov)
    focal_length_px = (image_height / 2.0) / math.tan(fov_rad / 2.0)
    nx = (polaris_pixel_x - image_width / 2.0) / focal_length_px
    ny = pixel_offset / focal_length_px
    tilt = math.radians(camera_tilt)
    norm = math.sqrt(nx * nx + ny * ny + 1.0)
    sin_alt = (ny * math.cos(tilt) + math.sin(tilt)) / norm
    sin_alt = max(-1.0, min(1.0, sin_alt))
    return math.degrees(math.asin(sin_alt))


def calculate_latitude_from_polaris(polaris_pixel_y, image_height, vertical_fov,
                                     camera_tilt=0.0, hemisphere='north',
                                     polaris_pixel_x=None, image_width=None):
    """
    Kuzey: Polaris yüksekliği = enlem
    Güney: Sigma Octantis yüksekliği = -(90 - |deklinasyon|) = negatif enlem
    """
    altitude = _altitude_from_pixel(
        polaris_pixel_y, image_height, vertical_fov, camera_tilt,
        polaris_pixel_x, image_width
    )

    if hemisphere == 'north':
        latitude = altitude
    else:
        # Güney yarımkürede enlem negatif
        latitude = -altitude  # Negatif enlem

    return latitude, altitude


def calculate_latitude_with_error_bounds(polaris_pixel_y, image_height, vertical_fov,
                                          fov_uncertainty=2, calibration_error=1,
                                          camera_tilt=0.0, hemisphere='north',
                                          polaris_pixel_x=None, image_width=None):

    latitude, altitude = calculate_latitude_from_polaris(
        polaris_pixel_y, image_height, vertical_fov, camera_tilt, hemisphere,
        polaris_pixel_x, image_width
    )

    lat_low, _ = calculate_latitude_from_polaris(
        polaris_pixel_y, image_height, vertical_fov - fov_uncertainty,
        camera_tilt, hemisphere, polaris_pixel_x, image_width
    )
    lat_high, _ = calculate_latitude_from_polaris(
        polaris_pixel_y, image_height, vertical_fov + fov_uncertainty,
        camera_tilt, hemisphere, polaris_pixel_x, image_width
    )

    fov_error = max(abs(lat_low - latitude), abs(lat_high - latitude))

    if hemisphere == 'north':
        polar_error = 90.0 - POLARIS_DECLINATION
    else:
        polar_error = 90.0 - abs(SIGMA_OCTANTIS_DECLINATION)  # Sigma daha sönük, hata büyük

    total_error = math.sqrt(fov_error**2 + calibration_error**2 + polar_error**2)

    result = {
        'latitude': round(latitude, 2),
        'lower_bound': round(latitude - total_error, 2),
        'upper_bound': round(latitude + total_error, 2),
        'error_margin': round(total_error, 2),
        'altitude': round(altitude, 2),
        'hemisphere': hemisphere
    }

    return result
