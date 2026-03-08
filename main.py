#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import sys
import argparse
from pathlib import Path

from star_detection import detect_stars
from polaris_finder import find_polaris
from latitude_solver import calculate_latitude_with_error_bounds
from compass import CompassSensor
from map_viewer import WorldMap, calculate_longitude_from_time
from exif_reader import read_azimuth_from_exif, read_gps_location_from_exif


def print_header():
    print("\n" + "="*60)
    print("🌌 KUTUP NAVIGASYON SİSTEMİ - POLARIS ENLEMİ HESAPLAYICI 🌌")
    print("="*60 + "\n")


def print_results(polaris, latitude_data, longitude, lon_error,
                  debug_info, image_shape, vertical_fov, hemisphere, compass=None):

    if compass is not None and not compass.is_facing_north():
        print("\n⚠️ PUSULA UYARISI")
        print("-" * 60)
        print(f"Telefon kuzeye bakmıyor!")
        print(f"   Mevcut Yön: {compass.get_cardinal_direction()}")
        print(f"   Sapma: {compass.get_deviation_from_north():.1f}°")
        print(f"Enlem hesaplaması etkilenebilir.\n")

    star_name = "Polaris" if hemisphere == 'north' else "Sigma Octantis"

    print("📊 SONUÇLAR")
    print("-" * 60)
    print(f"Yarımküre:            {'Kuzey 🔴' if hemisphere == 'north' else 'Güney 🔵'}")
    print(f"Kullanılan Yıldız:    {star_name}")
    print(f"Tahmini Enlem:        {latitude_data['latitude']}°")
    print(f"Tahmini Boylam:       {longitude}°")
    print(f"Enlem Hata Payı:      ±{latitude_data['error_margin']}°")
    print(f"Boylam Hata Payı:     ±{lon_error}°")
    print(f"Enlem Aralığı:        {latitude_data['lower_bound']}° → {latitude_data['upper_bound']}°")
    print(f"Yıldız Yüksekliği:    {latitude_data['altitude']}°\n")

    print("🔍 YILDIZ KONUMU")
    print("-" * 60)
    print(f"X (Yatay):            {polaris[0]:.1f} piksel")
    print(f"Y (Dikey):            {polaris[1]:.1f} piksel")
    print(f"Parlaklık:            {polaris[2]:.1f}\n")

    if compass is not None:
        print("🧭 PUSULA BİLGİSİ")
        print("-" * 60)
        print(f"Azimuth:              {compass.get_azimuth():.1f}°")
        print(f"Yön:                  {compass.get_cardinal_direction()}")
        print(f"Kuzeye Sapma:         {compass.get_deviation_from_north():.1f}°")
        facing = "Evet ✓" if compass.is_facing_north() else "Hayır ✗"
        print(f"Kuzeye Bakıyor mu:    {facing}\n")

    print("📸 GÖRÜNTÜ BİLGİSİ")
    print("-" * 60)
    print(f"Görüntü Boyutu:       {image_shape[1]}x{image_shape[0]} piksel")
    print(f"Dikey FOV:            {vertical_fov}°")
    print(f"Toplam Yıldız:        {debug_info['total_stars']} (tespit edilen)\n")


def print_debug_info(debug_info, polaris_info):
    print("🔧 DEBUG BİLGİLERİ")
    print("-" * 60)
    print(f"Yıldız Seçim Skoru:   {polaris_info['score']:.3f}")
    print(f"İncelenen Adaylar:    {polaris_info['candidates']}\n")

    scores = polaris_info['scores']
    if scores:
        sorted_scores = sorted(scores, key=lambda s: -s['total_score'])
        print("🎯 EN İYİ 5 ADAY")
        print("-" * 60)
        for i, score_info in enumerate(sorted_scores[:5], 1):
            star = score_info['star']
            print(
                f"{i}. Skor: {score_info['total_score']:.3f} | "
                f"Yük: {score_info['height_score']:.2f} | "
                f"Par: {score_info['brightness_score']:.2f} | "
                f"İzo: {score_info['iso_score']:.2f}"
            )
            print(f"   Konum: ({star[0]:.1f}, {star[1]:.1f})")


def main():
    parser = argparse.ArgumentParser(description='Kutup yıldızından konum hesapla')
    parser.add_argument('image', type=str, help='Gökyüzü fotoğrafı yolu')
    parser.add_argument('--fov', type=float, default=60,
                        help='Kameranın dikey FOV değeri (derece, default: 60)')
    parser.add_argument('--debug', action='store_true',
                        help='Detaylı debug çıktısı göster')
    parser.add_argument('--azimuth', type=float, default=None,
                        help='Pusula azimuth değeri (derece, 0=Kuzey)')
    parser.add_argument('--no-compass', action='store_true',
                        help='Pusula sensörü devre dışı bırak')
    parser.add_argument('--tilt', type=float, default=0,
                        help='Kameranın ufka göre eğim açısı (derece). Default: 0')
    parser.add_argument('--utc-hour', type=float, default=None,
                        help='UTC saati (0-23). Boylam hesabı için gerekli.')
    parser.add_argument('--utc-minute', type=float, default=0,
                        help='UTC dakikası (0-59).')

    # YENİ: Yarımküre seçimi
    parser.add_argument('--hemisphere', type=str, default='north',
                        choices=['north', 'south'],
                        help='Yarımküre seçimi: north (Polaris) veya south (Sigma Octantis). Default: north')

    args = parser.parse_args()

    image_path = args.image
    vertical_fov = args.fov
    show_debug = args.debug
    camera_tilt = args.tilt
    hemisphere = args.hemisphere

    print_header()

    if not Path(image_path).exists():
        print(f"❌ HATA: Dosya bulunamadı: {image_path}")
        sys.exit(1)

    # Yarımküre bilgisi
    if hemisphere == 'north':
        print("🌍 Yarımküre: KUZEY — Polaris aranacak\n")
    else:
        print("🌍 Yarımküre: GÜNEY — Sigma Octantis aranacak\n")

    # Boylam
    if args.utc_hour is not None:
        longitude, lon_error = calculate_longitude_from_time(
            args.utc_hour, args.utc_minute
        )
        print(f"🕐 UTC Saati: {int(args.utc_hour):02d}:{int(args.utc_minute):02d}")
        print(f"   Hesaplanan Boylam: {longitude}° (±{lon_error}°)\n")
    else:
        longitude = 0.0
        lon_error = 180.0
        print("⚠️  UTC saati girilmedi — boylam hesaplanamıyor.")
        print("   Kullanım: --utc-hour 21 --utc-minute 30\n")

    # Pusula
    compass = None
    if not args.no_compass:
        if args.azimuth is not None:
            azimuth = args.azimuth
            azimuth_source = "manuel"
        else:
            azimuth, source = read_azimuth_from_exif(image_path)
            if azimuth is not None:
                azimuth_source = "EXIF"
            else:
                azimuth = 0
                azimuth_source = "bilinmiyor (kuzey varsayıldı)"
                print("⚠️  Fotoğrafta pusula verisi bulunamadı.")
                print("   Kuzey varsayılıyor.\n")

        compass = CompassSensor(mode="mock", azimuth=azimuth)
        print(f"🧭 Pusula Kaynağı:  {azimuth_source}")
        print(f"   Azimuth: {compass.get_azimuth()}°")
        print(f"   Yön: {compass.get_cardinal_direction()}\n")
    else:
        print("🧭 Pusula Sensörü: KAPALI\n")

    print(f"📂 Yükleniyor: {image_path}")
    print(f"📐 Dikey FOV: {vertical_fov}°")
    print(f"📐 Kamera Eğimi: {camera_tilt}°\n")

    try:
        print("⭐ Yıldızlar tespit ediliyor...")
        stars, image_shape = detect_stars(image_path)
        print(f"   ✓ {len(stars)} yıldız tespit edildi\n")

        if len(stars) == 0:
            print("❌ HATA: Yıldız tespit edilemedi.")
            sys.exit(1)

        print(f"🔍 {'Polaris' if hemisphere == 'north' else 'Sigma Octantis'} aranıyor...")
        polaris, score, polaris_debug = find_polaris(
            stars, image_shape,
            hemisphere=hemisphere      # ← yarımküre geçiliyor
        )
        print(f"   ✓ Yıldız bulundu\n")

        print("📐 Enlem hesaplanıyor...")
        latitude_data = calculate_latitude_with_error_bounds(
            polaris_pixel_y=polaris[1],
            image_height=image_shape[0],
            vertical_fov=vertical_fov,
            camera_tilt=camera_tilt,
            hemisphere=hemisphere      # ← yarımküre geçiliyor
        )
        print(f"   ✓ Enlem hesaplaması tamamlandı\n")

        debug_info = {'total_stars': len(stars)}
        polaris_info = {
            'score': score,
            'candidates': polaris_debug['total_candidates'],
            'scores': polaris_debug['scores']
        }

        print_results(polaris, latitude_data, longitude, lon_error,
                      debug_info, image_shape, vertical_fov, hemisphere, compass)

        if show_debug:
            print_debug_info(debug_info, polaris_info)

        print("\n🗺️  Harita hazırlanıyor...")
        map_handler = WorldMap(output_path="konum_haritasi.png")
        map_handler.plot_location(
            latitude=latitude_data['latitude'],
            longitude=longitude,
            lat_error=latitude_data['error_margin'],
            lon_error=lon_error,
            hemisphere=hemisphere,
            title=(
                f"Kutup Navigasyonu — "
                f"Enlem: {latitude_data['latitude']}°, "
                f"Boylam: {longitude}°"
            )
        )

        print("=" * 60)
        print("✅ İşlem başarıyla tamamlandı!")
        print(f"📍 Koordinatlar: {latitude_data['latitude']}°, {longitude}°")
        print("=" * 60 + "\n")

    except Exception as e:
        print(f"❌ HATA: {e}")
        if show_debug:
            import traceback
            traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()