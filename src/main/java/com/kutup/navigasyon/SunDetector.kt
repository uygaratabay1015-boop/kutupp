package com.kutup.navigasyon

import android.graphics.Bitmap

data class SunDetection(val ok: Boolean, val x: Double = 0.0, val y: Double = 0.0, val areaPx: Int = 0, val reason: String = "")

/** Kisa pozlu karedeki doymus Gunes cekirdeginin merkezini bulur (piksel indeksi merkez kabulu). */
object SunDetector {
    fun detect(bitmap: Bitmap): SunDetection {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { val c = px[it]; (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }
        return detect(gray, w, h)
    }

    fun detect(gray: IntArray, w: Int, h: Int): SunDetection {
        var mx = 0
        for (g in gray) if (g > mx) mx = g
        if (mx < 200) return SunDetection(false, reason = "Gunes gorunmuyor (ya da cok sonuk). Kameranin Gunese baktigindan emin olun")
        val thr = maxOf(220, mx - 10)
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var best = 0; var bestArea = 0; var bestSx = 0.0; var bestSy = 0.0; var second = 0
        var next = 0
        for (i in gray.indices) {
            if (gray[i] < thr || label[i] != 0) continue
            next++
            var sp = 0; stack[sp++] = i; label[i] = next
            var area = 0; var sx = 0.0; var sy = 0.0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                area++; sx += x; sy += y
                if (x > 0) { val q = p - 1; if (gray[q] >= thr && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (gray[q] >= thr && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (gray[q] >= thr && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (gray[q] >= thr && label[q] == 0) { label[q] = next; stack[sp++] = q } }
            }
            if (area > bestArea) { second = bestArea; bestArea = area; best = next; bestSx = sx; bestSy = sy }
            else if (area > second) second = area
        }
        if (best == 0 || bestArea < 6) return SunDetection(false, reason = "Gunes cekirdegi cok kucuk")
        if (bestArea > w.toLong() * h / 25) return SunDetection(false, reason = "Goruntu asiri parlak (poz cok uzun ya da Gunes degil)")
        if (second > bestArea * 0.6) return SunDetection(false, reason = "Birden cok parlak nokta (yansima/bulut): Gunesi ortalayip tekrar cekin")
        return SunDetection(true, bestSx / bestArea, bestSy / bestArea, bestArea)
    }
}
