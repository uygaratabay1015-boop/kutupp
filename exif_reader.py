#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from PIL import Image
import PIL.ExifTags
from datetime import datetime

_EXIF_IFD = 0x8769
_GPS_IFD = 0x8825


def _load_exif(image_path):
    """Gorselin EXIF verisini tek bir sozluk olarak dondurur (Exif + GPS alt dizinleriyle).

    Public `getexif()` API'si kullanilir (JPEG disinda PNG/TIFF/WebP/HEIC icin de
    calisir) ve dosya tutacagi kapatilir. Basarisizsa None doner.
    """
    try:
        with Image.open(image_path) as img:
            exif = img.getexif()
            if not exif:
                return None
            data = dict(exif)
            try:
                data.update(dict(exif.get_ifd(_EXIF_IFD)))
            except Exception:
                pass
            try:
                gps = dict(exif.get_ifd(_GPS_IFD))
                if gps:
                    data[_GPS_IFD] = gps
            except Exception:
                pass
            return data
    except Exception:
        return None


def _gps_tags(exif_data):
    gps_info_raw = exif_data.get(_GPS_IFD) if exif_data else None
    if not gps_info_raw:
        return None
    return {
        PIL.ExifTags.GPSTAGS.get(k, k): v
        for k, v in gps_info_raw.items()
    }


def read_azimuth_from_exif(image_path):
    try:
        gps_tags = _gps_tags(_load_exif(image_path))
        if gps_tags is None:
            return None, 'none'

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
        gps_tags = _gps_tags(_load_exif(image_path))
        if gps_tags is None:
            return None, None

        def _ref(value, default):
            if isinstance(value, bytes):
                value = value.decode(errors='ignore')
            return str(value).strip() if value else default

        def dms_to_decimal(dms, ref):
            d = float(dms[0])
            m = float(dms[1])
            s = float(dms[2])
            decimal = d + m / 60 + s / 3600
            if ref.upper() in ('S', 'W'):
                decimal = -decimal
            return decimal

        if 'GPSLatitude' in gps_tags and 'GPSLongitude' in gps_tags:
            lat = dms_to_decimal(gps_tags['GPSLatitude'], _ref(gps_tags.get('GPSLatitudeRef'), 'N'))
            lon = dms_to_decimal(gps_tags['GPSLongitude'], _ref(gps_tags.get('GPSLongitudeRef'), 'E'))
            return lat, lon

        return None, None

    except Exception:
        return None, None


def _parse_exif_datetime(value):
    if not value:
        return None
    try:
        if isinstance(value, bytes):
            value = value.decode(errors='ignore')
        return datetime.strptime(str(value).strip().rstrip('\x00'), "%Y:%m:%d %H:%M:%S")
    except Exception:
        return None


def _parse_exif_offset(value):
    if not value:
        return None
    if isinstance(value, bytes):
        value = value.decode(errors='ignore')
    text = str(value).strip().rstrip('\x00')
    if len(text) != 6 or text[0] not in ("+", "-") or text[3] != ":":
        return None
    return text


def read_datetime_from_exif(image_path):
    try:
        exif_data = _load_exif(image_path)
        if exif_data is None:
            return None, None

        datetime_original = exif_data.get(36867)
        datetime_value = exif_data.get(306)
        offset_original = exif_data.get(36881)
        offset_datetime = exif_data.get(36880)

        dt = _parse_exif_datetime(datetime_original) or _parse_exif_datetime(datetime_value)
        offset = _parse_exif_offset(offset_original) or _parse_exif_offset(offset_datetime)
        return dt, offset
    except Exception:
        return None, None
