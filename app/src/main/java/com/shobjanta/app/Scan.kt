package com.shobjanta.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

object Scan {
    fun load(f: File, lim: Int = 2400): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > lim * 2) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rot = when (try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (e: Exception) { 1 }) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        val k = min(1f, lim.toFloat() / max(b.width, b.height))
        val m = Matrix().apply { postRotate(rot); postScale(k, k) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private val DEF = floatArrayOf(.05f, .05f, .95f, .05f, .95f, .95f, .05f, .95f)

    /** Returns normalized corners TL, TR, BR, BL. */
    fun detect(bmp: Bitmap): FloatArray {
        val w = 160
        val h = max(8, w * bmp.height / bmp.width)
        val px = IntArray(w * h)
        Bitmap.createScaledBitmap(bmp, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        val n = w * h
        val g = IntArray(n) { (Color.red(px[it]) * 30 + Color.green(px[it]) * 59 + Color.blue(px[it]) * 11) / 100 }
        val hist = IntArray(256)
        g.forEach { hist[it]++ }
        var sum = 0L
        for (i in 0..255) sum += i * hist[i].toLong()
        var sb = 0L; var wb = 0; var mx = -1.0; var th = 128
        for (i in 0..255) {
            wb += hist[i]; val wf = n - wb
            if (wb == 0 || wf == 0) continue
            sb += i * hist[i].toLong()
            val mb = sb.toDouble() / wb; val mf = (sum - sb).toDouble() / wf
            val v = wb.toDouble() * wf * (mb - mf) * (mb - mf)
            if (v > mx) { mx = v; th = i }
        }
        val m = BooleanArray(n) { g[it] > th }
        var bc = 0
        for (x in 0 until w) { if (m[x]) bc++; if (m[(h - 1) * w + x]) bc++ }
        for (y in 0 until h) { if (m[y * w]) bc++; if (m[y * w + w - 1]) bc++ }
        if (bc.toFloat() / (2 * w + 2 * h) > .6f) for (i in 0 until n) m[i] = !m[i]
        fun ok(x: Int, y: Int) = x in 0 until w && y in 0 until h && m[y * w + x]
        var a = Int.MAX_VALUE; var b2 = Int.MIN_VALUE; var c2 = Int.MIN_VALUE; var d2 = Int.MAX_VALUE
        var tl = intArrayOf(0, 0); var br = tl; var tr = tl; var bl = tl; var found = false
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            var c = 0
            if (ok(x - 3, y)) c++; if (ok(x + 3, y)) c++; if (ok(x, y - 3)) c++; if (ok(x, y + 3)) c++
            if (c < 3) continue
            val s1 = x + y; val s2 = x - y; val p = intArrayOf(x, y)
            if (s1 < a) { a = s1; tl = p; found = true }
            if (s1 > b2) { b2 = s1; br = p }
            if (s2 > c2) { c2 = s2; tr = p }
            if (s2 < d2) { d2 = s2; bl = p }
        }
        if (!found) return DEF.copyOf()
        val q = floatArrayOf(tl[0].toFloat() / w, tl[1].toFloat() / h, tr[0].toFloat() / w, tr[1].toFloat() / h,
            br[0].toFloat() / w, br[1].toFloat() / h, bl[0].toFloat() / w, bl[1].toFloat() / h)
        var ar = 0f
        for (i in 0..3) { val j = (i + 1) % 4; ar += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1] }
        ar = abs(ar) / 2
        return if (ar < .18f || ar > .985f) DEF.copyOf() else q
    }

    fun warp(src: Bitmap, q: FloatArray, lim: Int): Bitmap {
        val p = FloatArray(8) { q[it] * (if (it % 2 == 0) src.width else src.height) }
        fun d(i: Int, j: Int) = hypot(p[2 * i] - p[2 * j], p[2 * i + 1] - p[2 * j + 1])
        var w = max(d(0, 1), d(3, 2)); var h = max(d(0, 3), d(1, 2))
        val k = min(1f, lim.toFloat() / max(w, h))
        w = max(8f, w * k); h = max(8f, h * k)
        val o = Bitmap.createBitmap(w.roundToInt(), h.roundToInt(), Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(p, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4)
        Canvas(o).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return o
    }

    private fun ch(v: Int, bk: Int) = ((v * 238f / bk - 128f) * 1.18f + 122f).coerceIn(0f, 255f).toInt()

    /** 0 original, 1 color, 2 black and white, 3 gray */
    fun filter(src: Bitmap, f: Int): Bitmap {
        if (f == 0) return src
        val w = src.width; val h = src.height
        var t = src
        repeat(4) { t = Bitmap.createScaledBitmap(t, max(2, t.width / 2), max(2, t.height / 2), true) }
        val bg = Bitmap.createScaledBitmap(t, w, h, true)
        val a = IntArray(w * h); val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h); bg.getPixels(b, 0, w, 0, 0, w, h)
        for (i in a.indices) {
            val c = a[i]; val d = b[i]
            val br = Color.red(d) + 1; val bgc = Color.green(d) + 1; val bb = Color.blue(d) + 1
            if (f == 1) a[i] = Color.rgb(ch(Color.red(c), br), ch(Color.green(c), bgc), ch(Color.blue(c), bb))
            else {
                val gv = (Color.red(c) * .3f + Color.green(c) * .59f + Color.blue(c) * .11f) / (br * .3f + bgc * .59f + bb * .11f) * 255f
                val v = if (f == 2) { val s = ((gv - 135f) / 70f).coerceIn(0f, 1f); s * s * (3 - 2 * s) * 255f }
                else ((gv - 128f) * 1.25f + 128f).coerceIn(0f, 255f)
                val iv = v.toInt(); a[i] = Color.rgb(iv, iv, iv)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(a, 0, w, 0, 0, w, h) }
    }

    fun render(src: Bitmap, q: FloatArray, f: Int, rot: Int, lim: Int): Bitmap {
        val o = filter(warp(src, q, lim), f)
        if (rot == 0) return o
        return Bitmap.createBitmap(o, 0, 0, o.width, o.height, Matrix().apply { postRotate(rot * 90f) }, true)
    }

    /** Puts up to two card sides on one A4 page. */
    fun idCard(sides: List<Bitmap>): Bitmap {
        val o = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val c = Canvas(o); c.drawColor(Color.WHITE)
        sides.take(2).forEachIndexed { i, s ->
            val k = min(1000f / s.width, 700f / s.height)
            val w = s.width * k; val h = s.height * k
            val x = (1240 - w) / 2; val y = if (i == 0) 280f else 980f
            c.drawBitmap(s, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return o
    }

    fun pdf(pages: List<File>, out: File) {
        val doc = PdfDocument()
        pages.forEachIndexed { i, f ->
            val b = BitmapFactory.decodeFile(f.path) ?: return@forEachIndexed
            val h = (595f * b.height / b.width).roundToInt()
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(595, h, i + 1).create())
            pg.canvas.drawBitmap(b, null, Rect(0, 0, 595, h), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(pg); b.recycle()
        }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}
