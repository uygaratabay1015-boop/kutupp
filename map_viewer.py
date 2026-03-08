#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import matplotlib.pyplot as plt
import matplotlib.patches as patches
from matplotlib.patches import Ellipse
import numpy as np


class WorldMap:

    def __init__(self, output_path: str = "konum_haritasi.png"):
        self.output_path = output_path

    def _open_file(self, path):
        try:
            if sys.platform == 'win32':
                os.startfile(path)
            elif sys.platform == 'darwin':
                os.system(f'open "{path}"')
            else:
                os.system(f'xdg-open "{path}"')
        except Exception:
            print(f"   Haritayı manuel açın: {path}")

    def _draw_base_map(self, ax):
        ax.set_facecolor('#A8D5E2')

        land_areas = [
            patches.Rectangle((-170, 15), 130, 75, facecolor='#C8E6C9', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((-82, -56), 50, 70, facecolor='#C8E6C9', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((-10, 35), 65, 35, facecolor='#C8E6C9', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((-18, -35), 70, 75, facecolor='#C8E6C9', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((25, 10), 145, 70, facecolor='#C8E6C9', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((113, -40), 37, 32, facecolor='#C8E6C9', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((-180, -90), 360, 20, facecolor='#ECEFF1', edgecolor='#555', linewidth=0.5),
            patches.Rectangle((-180, 70), 360, 20, facecolor='#ECEFF1', edgecolor='#555', linewidth=0.5),
        ]
        for area in land_areas:
            ax.add_patch(area)

        for lat in range(-90, 91, 30):
            ax.axhline(y=lat, color='gray', linestyle='--', alpha=0.4, linewidth=0.5)
            ax.text(-185, lat, f'{lat}°', fontsize=7, va='center', color='gray')

        for lon in range(-180, 181, 30):
            ax.axvline(x=lon, color='gray', linestyle='--', alpha=0.4, linewidth=0.5)
            ax.text(lon, -95, f'{lon}°', fontsize=7, ha='center', color='gray')

        ax.axhline(y=0, color='orange', linestyle='-', alpha=0.5, linewidth=1, label='Ekvator')
        ax.axvline(x=0, color='orange', linestyle='-', alpha=0.5, linewidth=1, label='Ana Meridyen')

        ax.set_xlim(-190, 190)
        ax.set_ylim(-100, 100)
        ax.set_xlabel('Boylam (°)', fontsize=12, fontweight='bold')
        ax.set_ylabel('Enlem (°)', fontsize=12, fontweight='bold')

    def plot_location(self, latitude: float, longitude: float,
                      lat_error: float = 1.0, lon_error: float = 2.0,
                      title: str = "Navigasyon Sonucu",
                      hemisphere: str = 'north'):

        fig, ax = plt.subplots(figsize=(16, 9))
        self._draw_base_map(ax)

        ellipse = Ellipse(
            (longitude, latitude),
            width=lon_error * 2,
            height=lat_error * 2,
            facecolor='red', alpha=0.25,
            edgecolor='red', linewidth=1.5,
            label=f'Hata Payı (±{lat_error:.1f}°, ±{lon_error:.1f}°)',
            zorder=5
        )
        ax.add_patch(ellipse)

        ax.plot(longitude, latitude, 'r*', markersize=18, zorder=6,
                label=f'Konum: {latitude:.2f}°, {longitude:.2f}°')

        ax.axhline(y=latitude, color='red', linestyle=':', linewidth=1.5, alpha=0.7, zorder=4)
        ax.axvline(x=longitude, color='blue', linestyle=':', linewidth=1.5, alpha=0.7, zorder=4)

        hemisphere_label = "🔴 Kuzey Yarımküre" if hemisphere == 'north' else "🔵 Güney Yarımküre"
        star_label = "Polaris kullanıldı" if hemisphere == 'north' else "Sigma Octantis kullanıldı"

        ax.set_title(title, fontsize=14, fontweight='bold')
        ax.legend(loc='lower left', fontsize=9)

        info_text = (
            f"{hemisphere_label}\n"
            f"⭐ {star_label}\n"
            f"📍 Enlem:  {latitude:.4f}°\n"
            f"📍 Boylam: {longitude:.4f}°\n"
            f"🎯 Hata:   ±{lat_error:.2f}° / ±{lon_error:.2f}°"
        )
        ax.text(0.02, 0.97, info_text, transform=ax.transAxes,
                fontsize=10, verticalalignment='top',
                bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))

        plt.tight_layout()
        plt.savefig(self.output_path, dpi=150, bbox_inches='tight')
        print(f"\n✓ Harita kaydedildi: {self.output_path}")
        plt.close()
        self._open_file(self.output_path)

    def plot_observation_history(self, observations: list, title: str = "Gözlem Geçmişi"):

        fig, ax = plt.subplots(figsize=(14, 10))
        self._draw_base_map(ax)

        colors = plt.cm.rainbow(np.linspace(0, 1, len(observations)))

        for i, obs in enumerate(observations):
            lat = obs['latitude']
            error = obs.get('error', 1.0)
            label = obs.get('timestamp', f'Gözlem {i+1}')

            ax.axhline(y=lat, color=colors[i], linestyle='-', linewidth=2, alpha=0.7)
            ax.fill_between(
                [-180, 180],
                lat - error, lat + error,
                color=colors[i], alpha=0.15
            )
            ax.text(182, lat,
                    f'{label}: {lat:.2f}°', fontsize=10,
                    color=colors[i], fontweight='bold')

        ax.set_title(title, fontsize=14, fontweight='bold')
        ax.grid(True, alpha=0.2)

        output = "observations_history.png"
        plt.tight_layout()
        plt.savefig(output, dpi=150, bbox_inches='tight')
        print(f"\n✓ Gözlem geçmişi kaydedildi: {output}")
        plt.close()
        self._open_file(output)


def calculate_longitude_from_time(utc_hour: float, utc_minute: float = 0) -> tuple:
    total_minutes = utc_hour * 60 + utc_minute
    longitude = (total_minutes - 720) * (360 / 1440)

    if longitude > 180:
        longitude -= 360
    elif longitude < -180:
        longitude += 360

    lon_error = 2.0
    return round(longitude, 4), lon_error


def demo_harita():
    print("\n" + "="*60)
    print("🗺️  HARITA GÖSTERİMİ DEMO")
    print("="*60 + "\n")

    map_handler = WorldMap()
    map_handler.plot_location(
        latitude=-90.0,
        longitude=0.0,
        lat_error=1.5,
        lon_error=2.0,
        hemisphere='south',
        title="Güney Kutbu Testi"
    )


if __name__ == "__main__":
    demo_harita()