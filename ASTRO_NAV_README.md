# Astro Navigation (Python 3.11)

Bu paket, gece gokyuzu fotografindan enlem tahmini icin moduler bir yapi sunar.

## Modlar

- `north`: Polaris odakli kuzey kutup yuksekligi -> enlem
- `south`: Crux + Alpha/Beta Centauri ile South Celestial Pole (SCP) -> enlem

> Not: Sigma Octantis ana cozum degildir; sadece opsiyonel yardimci kontroldur.

## Klasorler

- `astro_nav/detection.py`: yildiz tespiti
- `astro_nav/north.py`: kuzey cozumleyici (Polaris)
- `astro_nav/south.py`: guney cozumleyici (Crux + pointers)
- `astro_nav/latitude.py`: piksel -> kutup yuksekligi -> enlem
- `astro_nav/pipeline.py`: uc modulleri birlestiren ana akis
- `run_astro_nav.py`: CLI
- `astro_nav_tests/`: unittest dosyalari

## Calistirma

```bash
python run_astro_nav.py --image path/to/night.jpg --mode south --vfov 60 --hfov 70 --pitch 12 --roll 1
```

## Test

```bash
python -m unittest discover -s astro_nav_tests -v
```

## Cikti Alani

- `stars`: tespit edilen yildizlar
- `detected_patterns`: taninan desenler
- `north_polaris` veya `south_scp`
- `latitude`
- `warnings`

