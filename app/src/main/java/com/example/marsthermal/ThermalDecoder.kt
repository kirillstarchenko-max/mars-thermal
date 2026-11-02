package com.example.marsthermal

import kotlin.math.abs

/**
 * Поток камеры: 513 пар [служебная строка 640 | строка изображения 640] = 1280 значений <u2 на строку.
 * Служебные строки почти постоянны (~235), последняя в кадре — заголовок. В строке изображения
 * младший байт слова всегда 0x80, а старший — яркость Y (0..255): это уже обработанное камерой
 * 8-битное изображение (UYVY с нейтральной цветностью).
 */
object ThermalDecoder {
    const val WIDTH = 640
    const val OUT_H = 512
    const val ROW = 1280
    const val ROWS = 513
    const val VALUES = ROWS * ROW          // 656640
    const val FRAME_BYTES = VALUES * 2     // 1313280

    // Ослабленные пороги: яркие объекты (ТВ, небо) больше не ломают синхронизацию
    private const val PAD_LEVEL = 235
    private const val PAD_TOL = 80         // было 25 — теперь допускаем больший разброс среднего
    private const val FLAT_RANGE = 2000    // было 400 — теперь допускаем больший разброс внутри строки
    private const val HEADER_RANGE = 20000

    private var rMin = 0
    private var rMax = 0
    private var rMean = 0

    private fun rowStats(f: ByteArray, startWord: Int) {
        var mn = 65535
        var mx = 0
        var sum = 0
        val base = startWord % VALUES
        var j = 0
        while (j < WIDTH) {
            var p = base + j
            if (p >= VALUES) p -= VALUES
            val b = p shl 1
            val w = ((f[b + 1].toInt() and 0xFF) shl 8) or (f[b].toInt() and 0xFF)
            if (w < mn) mn = w
            if (w > mx) mx = w
            sum += w
            j += 4
        }
        rMin = mn
        rMax = mx
        rMean = sum / (WIDTH / 4)
    }

    private fun padFlat(f: ByteArray, startWord: Int): Boolean {
        rowStats(f, startWord)
        return rMax - rMin < FLAT_RANGE && abs(rMean - PAD_LEVEL) < PAD_TOL
    }

    private fun median(a: IntArray, n: Int): Double {
        val c = a.copyOf(n)
        c.sort()
        return c[n / 2].toDouble()
    }

    fun findShift(f: ByteArray): Int {
        val tmpR = IntArray(ROWS)
        val tmpM = IntArray(ROWS)
        var bestC = 0
        var bestScore = Double.MAX_VALUE
        var c = 0
        while (c < ROW) {
            var n = 0
            var r = 0
            while (r < ROWS) {
                rowStats(f, c + r * ROW)
                tmpR[n] = rMax - rMin
                tmpM[n] = abs(rMean - PAD_LEVEL)
                n++
                r += 4
            }
            val score = median(tmpR, n) + 3.0 * median(tmpM, n)
            if (score < bestScore) { bestScore = score; bestC = c }
            c += 256
        }
        var k = 0
        var kRange = -1
        for (r in 0 until ROWS) {
            rowStats(f, bestC + r * ROW)
            tmpR[r] = rMax - rMin
            tmpM[r] = rMean
            if (tmpR[r] > kRange) { kRange = tmpR[r]; k = r }
        }
        val medR = median(tmpR, ROWS)
        val medM = median(tmpM, ROWS)
        val ok = kRange > HEADER_RANGE && medR < FLAT_RANGE && abs(medM - PAD_LEVEL) < PAD_TOL
        return if (ok) (bestC + (k + 1) * ROW) % VALUES else -1
    }

    /**
     * Ослабленная проверка: разрешаем до 15% «грязных» служебных строк.
     * Это спасает яркие сцены (телевизор, небо), где служебные строки «засвечиваются».
     */
    fun frameOk(f: ByteArray, shift: Int): Boolean {
        var bad = 0
        val maxBad = maxOf(4, OUT_H * 15 / 100)   // 4..76 строк в зависимости от OUT_H
        for (r in 0 until OUT_H) {
            if (!padFlat(f, shift + r * ROW)) {
                bad++
                if (bad > maxBad) return false
            }
        }
        rowStats(f, shift + OUT_H * ROW)
        return rMax - rMin > HEADER_RANGE
    }

    fun diagnose(f: ByteArray, shift: Int): String {
        var flat = 0
        var meanSum = 0L
        for (r in 0 until OUT_H) {
            rowStats(f, shift + r * ROW)
            if (rMax - rMin < FLAT_RANGE && abs(rMean - PAD_LEVEL) < PAD_TOL) flat++
            meanSum += rMean
        }
        rowStats(f, shift + OUT_H * ROW)
        return "плоских $flat/$OUT_H, ср.уровень ${meanSum / OUT_H}, заголовок ${rMax - rMin}"
    }

    fun decodeY(f: ByteArray, shift: Int, out: ByteArray) {
        for (r in 0 until OUT_H) {
            var p = (shift + r * ROW + WIDTH) % VALUES
            val dst = r * WIDTH
            for (j in 0 until WIDTH) {
                out[dst + j] = f[(p shl 1) + 1]
                p++
                if (p == VALUES) p = 0
            }
        }
    }
}
