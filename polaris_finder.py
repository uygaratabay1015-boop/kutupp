#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import math
import numpy as np

# Güney yarımkürede kullanılan yıldız
# Sigma Octantis: çok sönük (~mag 5.4) ama tek güney kutup yıldızı
SIGMA_OCTANTIS_MAG = 5.4  # Sönük olduğu için izolasyon ve konum daha kritik


def calculate_isolation_score(star, all_stars, image_height, image_width):
    distances = []
    for other_star in all_stars:
        if star != other_star:
            dx = star[0] - other_star[0]
            dy = star[1] - other_star[1]
            d = math.hypot(dx, dy)
            distances.append(d)

    if not distances:
        return 0.5

    distances.sort()
    avg_dist = np.mean(distances[:5]) if len(distances) >= 5 else np.mean(distances)
    max_possible_dist = math.hypot(image_height, image_width)
    return min(avg_dist / max_possible_dist, 1.0)


def calculate_height_score(y, image_height, hemisphere='north'):
    """
    Kuzey: Polaris görüntünün üstünde → üste yakın = yüksek skor
    Güney: Sigma Octantis görüntünün üstünde → güney yarımkürede
           kamera güneye bakıyor, kutup yıldızı yine üstte olur
    """
    relative_y = y / image_height  # 0.0 = en üst, 1.0 = en alt

    if hemisphere == 'north':
        # Polaris üst %70'te olmalı
        if relative_y > 0.7:
            return 0.0
        return 1.0 - (relative_y / 0.7)
    else:
        # Sigma Octantis da üst %70'te (kamera güneye bakıyor)
        if relative_y > 0.7:
            return 0.0
        return 1.0 - (relative_y / 0.7)


def calculate_brightness_score(brightness, all_brightnesses, hemisphere='north'):
    """
    Kuzey: Polaris orta-parlak
    Güney: Sigma Octantis çok sönük — en sönük yıldızlar öncelikli
    """
    if not all_brightnesses:
        return brightness / 255.0

    max_b = max(all_brightnesses)
    mean_b = np.mean(all_brightnesses)

    if hemisphere == 'north':
        norm = brightness / 255.0
        # Aşırı parlaksa ceza (gezegen olabilir)
        if max_b > 0 and brightness > 0.9 * max_b and max_b > mean_b * 2:
            norm *= 0.5
        return min(norm, 1.0)
    else:
        # Güney: Sigma Octantis çok sönük
        # En sönük yıldızlara yüksek skor ver
        norm = brightness / 255.0
        # Sönük yıldız = yüksek skor (ters mantık)
        south_score = 1.0 - norm
        return min(south_score, 1.0)


def find_polaris(stars, image_shape, top_candidates=50, hemisphere='north'):
    """
    Kuzey: Polaris ara
    Güney: Sigma Octantis ara

    Args:
        stars: [(x, y, brightness), ...] listesi
        image_shape: (height, width)
        top_candidates: Kaç aday incelenecek
        hemisphere: 'north' veya 'south'
    """
    if not stars:
        return None, 0, {'error': 'Yıldız bulunamadı'}

    height = image_shape[0]
    width = image_shape[1]

    if hemisphere == 'north':
        # Kuzeyde: en parlak 50 adaya bak
        if len(stars) > top_candidates:
            sorted_stars = sorted(stars, key=lambda s: -s[2])[:top_candidates]
        else:
            sorted_stars = stars
    else:
        # Güneyde: Sigma Octantis çok sönük — en sönük 50 adaya bak
        if len(stars) > top_candidates:
            sorted_stars = sorted(stars, key=lambda s: s[2])[:top_candidates]
        else:
            sorted_stars = stars

    all_brightnesses = [s[2] for s in sorted_stars]

    best_star = None
    best_score = -1
    scores_debug = []

    for star in sorted_stars:
        x, y, brightness = star

        height_score = calculate_height_score(y, height, hemisphere)
        brightness_score = calculate_brightness_score(brightness, all_brightnesses, hemisphere)
        iso_score = calculate_isolation_score(star, sorted_stars, height, width)

        if hemisphere == 'north':
            # Kuzey ağırlıkları
            total_score = (
                0.4 * height_score +
                0.3 * brightness_score +
                0.3 * iso_score
            )
        else:
            # Güney: izolasyon daha kritik (Sigma Octantis çok izole)
            total_score = (
                0.35 * height_score +
                0.25 * brightness_score +
                0.40 * iso_score
            )

        scores_debug.append({
            'star': star,
            'height_score': round(height_score, 3),
            'brightness_score': round(brightness_score, 3),
            'iso_score': round(iso_score, 3),
            'total_score': round(total_score, 3)
        })

        if total_score > best_score:
            best_score = total_score
            best_star = star

    debug_info = {
        'total_candidates': len(sorted_stars),
        'scores': scores_debug,
        'best_score': round(best_score, 3)
    }

    return best_star, best_score, debug_info