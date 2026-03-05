# Kutup Navigasyon Metodu (Juri Icin)

Bu belge, uygulamanin GPS kullanmadan enlem tahmini yaparken izledigi hesaplama akisini ve dayandigi kaynaklari ozetler.

## 1) Temel Prensip

- Kuzey yarimkure: Polaris (Kutup Yildizi) yuksekligi enleme yakindir.
- Guney yarimkure: Guney Gok Kutbu dogrudan parlak bir yildiz degildir; Crux (Guney Haci) geometrisinden kutup noktasi yaklasik cikarilir.
- Uygulama tek bir yildiza bakip sonuc vermez; yildiz deseni, katalog benzerligi ve geometri guvenini birlikte kullanir.

## 2) Is Akisi

1. Fotograf girisi (kamera veya galeri)
2. Yildiz tespiti (yerel tepe + yerel arka plan farki)
3. Desen eslestirme (ucgen/dortlu oranlari)
4. Katalog benzerlik skoru (kuzey/guney ayri)
5. Referans kutup adayi secimi
   - Kuzey: Polaris adaylari skorlanir, dusuk guvenli adaylar elenir
   - Guney: Crux geometrisinden SCP adaylari uretilir
6. Piksel -> yukseklik acisi donusumu (FOV + pitch + roll telafisi)
7. Agirlikli medyan ile enlem cozumu ve hata payi
8. Guven kapilari (confidence gates):
   - Desen/katalog/referans guveni dusukse sonuc verilmez
   - Asiri kutup (|lat| >= 88) sonucunda ekstra guven kontrolu uygulanir

## 3) Neden Daha Guvenli?

- Kuzey modunda, `Ursa Minor/Polaris` yeterince guvenli degilse "sonuc verme" kurali vardir.
- Kamera roll acisi (telefonun saga/sola yatikligi) hesaba katilir; bu, egik cekimlerde enlem hatasini azaltir.
- Polaris seciminde sadece "en parlak/yukari yildiz" degil:
  - yukseklik,
  - parlaklik,
  - komsu yildiz yapisi (geometry context),
  - izolasyon dengesi
  birlikte puanlanir.
- Bu sayede tekil parazit piksellerin Polaris sanilmasi azaltilir.

## 4) Bilinen Sinirlar

- Tek kare, dusuk kalite, bulut, isik kirliligi ve lens bozulmasi hatayi artirir.
- Galeri goruntulerinde EXIF tarih/saat ve kamera yonu eksikse manuel kalibrasyon gerekir.
- SCP (Guney) tahmini, Polaris gibi direkt bir "tek yildiz" referansi kadar kolay degildir; bu nedenle kalite kontrolu daha kritiktir.

## 5) Kaynaklar

- Astrometry.net (plate-solving fikri ve yildiz deseni eslestirme yaklasimi):  
  https://astrometry.net/
- Lang et al., "Astrometry.net: Blind astrometric calibration of arbitrary astronomical images", arXiv:0910.2233:  
  https://arxiv.org/abs/0910.2233
- ESA Hipparcos Catalogues (parlak yildiz koordinatlari icin temel astronomik katalog kaynagi):  
  https://www.cosmos.esa.int/web/hipparcos/catalogues
- Bennett atmosferik kirilma yaklasimi (ufka yakin yukseklik duzeltmesi):  
  https://doi.org/10.1086/132749

## 6) Juriye Kisa Anlatim Metni

"Uygulama GPS kullanmadan, gok fotografindan cikan yildiz noktalarini desen ve katalog tabanli eslestiriyor. Kuzeyde Polaris/Kucuk Ayi, guneyde ise Guney Haci geometrisi kullanilarak kutup referansi uretiliyor. Sonra bu referansin goruntu koordinati, kamera FOV ve pitch kalibrasyonu ile acisal yukseklige cevriliyor. Enlem sonucu tek bir tespitten degil, agirlikli medyan ve guven filtrelerinden gecen birden fazla adaydan uretiliyor."
