import cv2
import numpy as np


def adaptive_threshold(img_blur, base_threshold=180):
    """
    Görüntünün genel parlaklığına göre threshold otomatik ayarlanır.
    
    Eski kod: sabit 180 → karanlık gecelerde yıldız bulamaz,
              aydınlık gecelerde gürültü dolar
    Yeni kod: görüntü medyanına göre dinamik threshold
    """
    median_brightness = np.median(img_blur)

    # Gece gökyüzü çok karanlıksa (medyan < 30) eşiği düşür
    # Aydınlık geceyse (medyan > 60) eşiği yükselt
    if median_brightness < 30:
        # Çok karanlık gece — medyanın 4-5 katı
        threshold = max(base_threshold * 0.6, median_brightness * 5)
    elif median_brightness > 80:
        # Şehir ışığı var — daha yüksek eşik
        threshold = min(base_threshold * 1.3, 240)
    else:
        threshold = base_threshold

    return int(threshold)


def get_star_brightness(img, cnt, x, y, w, h):
    """
    Yıldızın gerçek parlaklığını hesaplar.
    
    Eski kod: bounding box tüm piksel ortalaması → karanlık kenarlar dahil
    Yeni kod: sadece kontur içindeki piksellerin maksimum + ortalama kombinasyonu
    """
    # Kontur maskesi oluştur
    mask = np.zeros(img.shape[:2], dtype=np.uint8)
    cv2.drawContours(mask, [cnt], -1, 255, -1)

    # Sadece yıldız piksellerini al
    star_pixels = img[mask == 255]

    if len(star_pixels) == 0:
        return 0.0

    # Maksimum parlaklık + ortalama kombinasyonu
    # Max: yıldızın gerçek tepe parlaklığı
    # Mean: gürültüye karşı dayanıklılık
    brightness = 0.6 * np.max(star_pixels) + 0.4 * np.mean(star_pixels)
    return float(brightness)


def calculate_area_limits(img_shape):
    """
    Görüntü boyutuna göre dinamik alan sınırları hesaplar.
    
    Eski kod: sabit 3-200 → yüksek çözünürlükte yıldızları eliyor
    Yeni kod: görüntü diyagonaline orantılı
    """
    height, width = img_shape[:2]
    diagonal = np.hypot(height, width)

    # Diyagonalin binde 0.5'i ile binde 10'u arasındaki nesneler yıldız
    min_area = max(3, (diagonal * 0.0005) ** 2)
    max_area = min(500, (diagonal * 0.01) ** 2)

    return min_area, max_area


def detect_stars(image_path):
    """
    Yıldızları tespit eder ve merkez koordinatlarıyla parlaklığını döndürür.

    Args:
        image_path: Gökyüzü fotoğrafı yolu

    Returns:
        stars: [(x, y, brightness), ...] listesi
        shape: Görüntü boyutları (height, width)
    """
    # DÜZELTME: Renkli oku, sonra luminance'a çevir
    # Gri direkt okumak yerine weighted luminance daha doğru yıldız rengi yakalar
    img_color = cv2.imread(image_path, cv2.IMREAD_COLOR)

    if img_color is None:
        raise ValueError(f"Görüntü yüklenemedi: {image_path}")

    # Luminance = 0.299R + 0.587G + 0.114B (insan gözü ağırlıkları)
    img = np.dot(img_color[..., ::-1].astype(np.float32),
                 [0.299, 0.587, 0.114]).astype(np.uint8)

    # Gaussian blur — kernel boyutu görüntüye orantılı
    blur_kernel = 5
    img_blur = cv2.GaussianBlur(img, (blur_kernel, blur_kernel), 0)

    # DÜZELTME: Adaptif threshold
    threshold_val = adaptive_threshold(img_blur)
    _, thresh = cv2.threshold(img_blur, threshold_val, 255, cv2.THRESH_BINARY)

    # Morfoloji — küçük gürültü temizle
    kernel = np.ones((3, 3), np.uint8)
    thresh = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, kernel, iterations=1)

    # Konturları bul
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    # DÜZELTME: Dinamik alan limitleri
    min_area, max_area = calculate_area_limits(img.shape)

    stars = []
    for cnt in contours:
        area = cv2.contourArea(cnt)

        if min_area < area < max_area:
            x, y, w, h = cv2.boundingRect(cnt)

            # Merkez koordinat (integer değil float — daha hassas)
            cx = x + w / 2.0
            cy = y + h / 2.0

            # DÜZELTME: Gerçek yıldız parlaklığı (kontur maskesi ile)
            brightness = get_star_brightness(img, cnt, x, y, w, h)

            # Çok sönük yıldızları ele (parlaklık threshold'un %70'inden düşükse)
            if brightness > threshold_val * 0.7:
                stars.append((cx, cy, brightness))

    return stars, img.shape