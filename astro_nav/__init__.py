"""Astro navigation package for latitude estimation from night-sky images."""

from .config import ProcessingConfig
from .pipeline import LatitudeEstimator
from .types import ProcessingResult

__all__ = ["LatitudeEstimator", "ProcessingConfig", "ProcessingResult"]
