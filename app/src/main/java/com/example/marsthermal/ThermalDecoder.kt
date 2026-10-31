package com.example.marsthermal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Поток = 513 пар [служебная строка 640 | строка изображения 640] = 1280 значений <u2.
 * Порт find_shift / decode / sync_lost из mars_autosync2.py (проверено на реальных кадрах в Python).
 */
object ThermalDecoder {
    const val WIDTH = 640
    const val OUT_H = 512
    const val ROW = 1280
    const val ROWS = 513
    const val VALUES = ROWS * ROW          // 656640
    const val FRAME_BYTES = VALUES * 2     // 1313280

    fun toU16(frame: ByteArray, out: ShortArray) {
        ByteBuffer.wrap(frame, 0, FRAME_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(out)
    }

    private fun u(a: ShortArray, i: Int): Int = a[i % VALUES].toInt() and 0xFFFF

    private fun rowStd(a: ShortArray, start: Int): Double {
        var s = 0.0
        var s2 = 0.0
        for (j in 0 until WIDTH) {
            val x = u(a, start + j).toDouble()
            s += x
            s2 += x * x
        }
        val m = s / WIDTH
        return sqrt(maxOf(0.0, s2 / WIDTH - m * m))
    }

    private fun padScore(a: ShortArray, c: Int): Double {
        var t = 0.0
        var n = 0
        var r = 0
        while (r < ROWS) {
            t += rowStd(a, c + r * ROW)
            n++
            r += 4
        }
        return t / n
    }

    /** Возвращает сдвиг плоского потока или -1, если фазу найти не удалось. */
    fun findShift(a: ShortArray): Int {
        // 1) столбцовая фаза: резкая граница служебная/изображение
        val hx = DoubleArray(ROW)
        var r = 0
        while (r < ROWS) {
            val base = r * ROW
            for (c in 0 until ROW) {
                val cur = a[base + c].toInt() and 0xFFFF
                val nxt = a[base + (c + 1) % ROW].toInt() and 0xFFFF
                hx[c] += abs(nxt - cur).toDouble()
            }
            r += 4
        }
        var best = 0
        var bestV = -1.0
        for (c in 0 until WIDTH) {
            val s = hx[c] + hx[c + WIDTH]
            if (s > bestV) { bestV = s; best = c }
        }
        val c0 = best + 1
        val cands = intArrayOf(c0 % ROW, (c0 + WIDTH) % ROW)
        var bestC = cands[0]
        var bestScore = Double.MAX_VALUE
        for (c in cands) {
            val sc = padScore(a, c)
            if (sc < bestScore) { bestScore = sc; bestC = c }
        }
        // 2) строковая фаза: строка заголовка = служебная строка с максимальным разбросом
        val stds = DoubleArray(ROWS)
        var k = 0
        var kStd = -1.0
        for (row in 0 until ROWS) {
            stds[row] = rowStd(a, bestC + row * ROW)
            if (stds[row] > kStd) { kStd = stds[row]; k = row }
        }
        val med = stds.sortedArray()[ROWS / 2]
        val ok = bestScore < 200 && kStd > 1000 && med < 100
        return if (ok) (bestC + (k + 1) * ROW) % VALUES else -1
    }

    /** Вырезает 512x640 изображения (без служебных строк и мусорной строки кадра). */
    fun decode(a: ShortArray, shift: Int, out: ShortArray) {
        for (r in 0 until OUT_H) {
            val start = (shift + r * ROW + WIDTH) % VALUES
            val dst = r * WIDTH
            if (start + WIDTH <= VALUES) {
                System.arraycopy(a, start, out, dst, WIDTH)
            } else {
                val first = VALUES - start
                System.arraycopy(a, start, out, dst, first)
                System.arraycopy(a, 0, out, dst + first, WIDTH - first)
            }
        }
    }

    /** Служебная половина должна оставаться плоской; иначе фаза потеряна. */
    fun syncLost(a: ShortArray, shift: Int): Boolean {
        val s = DoubleArray(64)
        var i = 0
        var r = 0
        while (r < OUT_H) {
            s[i++] = rowStd(a, shift + r * ROW)
            r += 8
        }
        s.sort()
        return s[32] > 200
    }
}

/** 16 бит -> 8 бит по перцентилям (как np.percentile в Python) + мягкое выравнивание гистограммы + INFERNO. */
class ThermalRenderer {
    private val hist = IntArray(65536)
    private val g = IntArray(ThermalDecoder.OUT_H * ThermalDecoder.WIDTH)
    private val hist256 = IntArray(256)
    private val map256 = IntArray(256)

    fun render(img: ShortArray, contrastPct: Double, equalize: Boolean, out: IntArray) {
        val n = img.size
        java.util.Arrays.fill(hist, 0)
        for (i in 0 until n) hist[img[i].toInt() and 0xFFFF]++

        val loTarget = n * contrastPct / 100.0
        val hiTarget = n * (100.0 - contrastPct) / 100.0
        var cum = 0
        var lo = 0
        var hi = 65535
        var loSet = false
        for (v in 0 until 65536) {
            cum += hist[v]
            if (!loSet && cum >= loTarget) { lo = v; loSet = true }
            if (cum >= hiTarget) { hi = v; break }
        }
        if (hi <= lo + 1) {
            java.util.Arrays.fill(out, InfernoLut.argb[0])
            return
        }
        val scale = 255.0 / (hi - lo)
        java.util.Arrays.fill(hist256, 0)
        for (i in 0 until n) {
            var x = ((((img[i].toInt() and 0xFFFF) - lo) * scale)).toInt()
            if (x < 0) x = 0 else if (x > 255) x = 255
            g[i] = x
            hist256[x]++
        }
        if (equalize) {
            // plateau equalization: обрезаем пики гистограммы, чтобы не раздувать шум
            val clip = maxOf(1, (2 * n) / 256)
            var total = 0L
            for (b in 0 until 256) {
                if (hist256[b] > clip) hist256[b] = clip
                total += hist256[b]
            }
            var run = 0L
            for (b in 0 until 256) {
                run += hist256[b]
                map256[b] = if (total > 0) ((run * 255) / total).toInt() else b
            }
            for (i in 0 until n) out[i] = InfernoLut.argb[map256[g[i]]]
        } else {
            for (i in 0 until n) out[i] = InfernoLut.argb[g[i]]
        }
    }
}
