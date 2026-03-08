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


def calculate_latitude_from_polaris(polaris_pixel_y, image_height, vertical_fov,
                                     camera_tilt=0.0, hemisphere='north'):
    """
    Kuzey: Polaris yüksekliği = enlem
    Güney: Sigma Octantis yüksekliği = -(90 - |deklinasyon|) = negatif enlem
    """
    center_y = image_height / 2.0
    pixel_offset = center_y - polaris_pixel_y
    angle_from_center = pixel_to_degrees(pixel_offset, image_height, vertical_fov)
    altitude = camera_tilt + angle_from_center

    if hemisphere == 'north':
        polar_distance = 90.0 - POLARIS_DECLINATION  # ~0.736°
        latitude = altitude
    else:
        # Güney yarımkürede enlem negatif
        polar_distance = 90.0 - abs(SIGMA_OCTANTIS_DECLINATION)  # ~1.43°
        latitude = -altitude  # Negatif enlem

    return latitude, altitude


def calculate_latitude_with_error_bounds(polaris_pixel_y, image_height, vertical_fov,
                                          fov_uncertainty=2, calibration_error=1,
                                          camera_tilt=0.0, hemisphere='north'):

    latitude, altitude = calculate_latitude_from_polaris(
        polaris_pixel_y, image_height, vertical_fov, camera_tilt, hemisphere
    )

    lat_low, _ = calculate_latitude_from_polaris(
        polaris_pixel_y, image_height, vertical_fov - fov_uncertainty,
        camera_tilt, hemisphere
    )
    lat_high, _ = calculate_latitude_from_polaris(
        polaris_pixel_y, image_height, vertical_fov + fov_uncertainty,
        camera_tilt, hemisphere
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