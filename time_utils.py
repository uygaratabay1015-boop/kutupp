from __future__ import annotations

from datetime import datetime, timedelta, timezone


def parse_iso_utc(ts: str) -> datetime:
    value = ts.strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def parse_tz_offset(offset_str: str) -> timedelta:
    value = offset_str.strip()
    sign = 1
    if value.startswith("-"):
        sign = -1
        value = value[1:]
    elif value.startswith("+"):
        value = value[1:]

    parts = value.split(":")
    if len(parts) != 2:
        raise ValueError("TZ offset formati +HH:MM olmali.")
    hours = int(parts[0])
    minutes = int(parts[1])
    return timedelta(hours=sign * hours, minutes=sign * minutes)


def datetime_to_julian_date(dt_utc: datetime) -> float:
    if dt_utc.tzinfo is None:
        dt_utc = dt_utc.replace(tzinfo=timezone.utc)
    dt_utc = dt_utc.astimezone(timezone.utc)
    year = dt_utc.year
    month = dt_utc.month
    day = dt_utc.day
    hour = dt_utc.hour + dt_utc.minute / 60 + dt_utc.second / 3600 + dt_utc.microsecond / 3_600_000_000

    if month <= 2:
        year -= 1
        month += 12

    a = year // 100
    b = 2 - a + (a // 4)
    jd_day = int(365.25 * (year + 4716)) + int(30.6001 * (month + 1)) + day + b - 1524.5
    return jd_day + hour / 24.0


def gmst_degrees(dt_utc: datetime) -> float:
    jd = datetime_to_julian_date(dt_utc)
    t = (jd - 2451545.0) / 36525.0
    gmst = (
        280.46061837
        + 360.98564736629 * (jd - 2451545.0)
        + 0.000387933 * t * t
        - (t * t * t) / 38710000.0
    )
    return gmst % 360.0


def wrap_longitude_deg(lon: float) -> float:
    value = ((lon + 180.0) % 360.0) - 180.0
    if value == -180.0:
        return 180.0
    return value
