#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from PIL import Image
import PIL.ExifTags


def read_azimuth_from_exif(image_path):
    try:
        img = Image.open(image_path)
        exif_data = img._getexif()
        if exif_data is None:
            return None, 'none'

        gps_info_raw = exif_data.get(34853)
        if gps_info_raw is None:
            return None, 'none'

        gps_tags = {
            PIL.ExifTags.GPSTAGS.get(k, k): v
            for k, v in gps_info_raw.items()
        }

        if 'GPSImgDirection' in gps_tags:
            direction = gps_tags['GPSImgDirection']
            if isinstance(direction, tuple):
                azimuth = direction[0] / direction[1]
            else:
                azimuth = float(direction)
            return azimuth % 360, 'exif'

        return None, 'none'

    except Exception:
        return None, 'none'


def read_gps_location_from_exif(image_path):
    try:
        img = Image.open(image_path)
        exif_data = img._getexif()
        if exif_data is None:
            return None, None

        gps_info_raw = exif_data.get(34853)
        if gps_info_raw is None:
            return None, None

        gps_tags = {
            PIL.ExifTags.GPSTAGS.get(k, k): v
            for k, v in gps_info_raw.items()
        }

        def dms_to_decimal(dms, ref):
            d = float(dms[0])
            m = float(dms[1])
            s = float(dms[2])
            decimal = d + m / 60 + s / 3600
            if ref in ('S', 'W'):
                decimal = -decimal
            return decimal

        if 'GPSLatitude' in gps_tags and 'GPSLongitude' in gps_tags:
            lat = dms_to_decimal(gps_tags['GPSLatitude'], gps_tags.get('GPSLatitudeRef', 'N'))
            lon = dms_to_decimal(gps_tags['GPSLongitude'], gps_tags.get('GPSLongitudeRef', 'E'))
            return lat, lon

        return None, None

    except Exception:
        return None, None
