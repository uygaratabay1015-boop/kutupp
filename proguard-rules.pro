# Kutup Navigasyon - R8/ProGuard kurallari
# build.gradle.kts release tipinde "proguard-rules.pro" dosyasina basvurur; dosya yoktu
# ve release derlemesi "file not found" ile basarisiz oluyordu.
#
# Uygulama refleksiyon kullanmadigi icin ozel keep kurali gerekmiyor.
# Hata ayiklama icin satir numaralarini koru:
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
