package com.example.marsthermal

import kotlin.math.abs

/**
 * Поток камеры: 513 пар [служебная строка 640 | строка изображения 640] = 1280 значений <u2 на строку.
 * Служебные строки почти постоянны (~235), последняя в кадре — заголовок. В строке изображения
 * младший байт слова всегда 0x80, а старший — яркость Y (0..255): это уже обработанное камерой
 * 8-битное изображение (UYVY с нейтральной цветностью).
 *
 * Все проверки работают прямо по байтам кадра, без преобразования в 16-битный массив.
 */
object ThermalDecoder {
    const val WIDTH = 640
    const val OUT_H = 512
    const val ROW = 1280
    const val ROWS = 513
    const val VALUES = ROWS * ROW          // 656640
    const val FRAME_BYTES = VALUES * 2     // 1313280

    private const val PAD_LEVEL = 235
    private const val PAD_TOL = 25
    private const val FLAT_RANGE = 400
    private const val HEADER_RANGE = 20000

    // результат последнего rowStats (поток обработки один, поэтому без синхронизации)
    private var rMin = 0
    private var rMax = 0
    private var rMean = 0

    /** Диапазон и среднее по 160 словам строки (каждое четвёртое из 640), строка начинается со слова startWord. */
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

    /**
     * Сдвиг потока (в словах) или -1. Фаза по столбцам всегда кратна 256 словам (512 байт = USB-пакет),
     * поэтому проверяем 5 кандидатов: у верного служебная половина плоская И держится на уровне 235.
     * Это не зависит от сцены (ровное небо, чёрный кадр не путают поиск).
     */
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
        // все строки при лучшей фазе: ищем строку-заголовок
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

    /** Строгая проверка кадра при данном сдвиге: служебные строки плоские, последняя (заголовок) «живая». */
    fun frameOk(f: ByteArray, shift: Int): Boolean {
        var bad = 0
        for (r in 0 until OUT_H) {
            if (!padFlat(f, shift + r * ROW)) {
                bad++
                if (bad > 2) return false
            }
        }
        rowStats(f, shift + OUT_H * ROW)
        return rMax - rMin > HEADER_RANGE
    }

    /** Текстовая диагностика для экрана «Инфо»: сколько строк плоские и чем плох кадр. */
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

    /** Яркость Y (старший байт слова) изображения 512x640 без служебных строк и мусорной строки кадра. */
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

/**
 * Яркость Y -> цвет: усреднение по времени (с защитой от шлейфов), контраст, палитра.
 * Целочисленная арифметика (Y*16), около 10 мс на кадр даже на слабом CPU.
 */
class ThermalRenderer {
    companion object {
        const val N = ThermalDecoder.OUT_H * ThermalDecoder.WIDTH
        /** Вес нового кадра (из 256) для уровней усреднения 0..4. */
        private val BASE_ALPHA = intArrayOf(256, 150, 100, 64, 30)
    }

    private val acc = IntArray(N)           // состояние временного фильтра, Y*16
    private val idx = ByteArray(N)
    private val hist = IntArray(256)
    private val hist2 = IntArray(256)
    private val map = IntArray(256)
    private val eqLut = FloatArray(256) { it.toFloat() }
    private var haveAcc = false
    private var haveRange = false
    private var lo = 0f
    private var hi = 255f

    var minPos = 0
    var maxPos = 0
    var minLevel = 0
    var maxLevel = 0
    var centerLevel = 0

    fun reset() {
        haveAcc = false
        haveRange = false
    }

    /** contrast: 0 как у камеры, 1 авто (перцентили), 2 выравнивание гистограммы. */
    fun render(y: ByteArray, tnr: Int, contrast: Int, pal: IntArray, out: IntArray) {
        val base = BASE_ALPHA[tnr.coerceIn(0, 4)]
        java.util.Arrays.fill(hist, 0)
        var mnV = Int.MAX_VALUE
        var mxV = Int.MIN_VALUE
        var mnI = 0
        var mxI = 0

        // 1) временной фильтр + гистограмма + экстремумы
        val first = !haveAcc
        for (i in 0 until N) {
            val v = (y[i].toInt() and 0xFF) shl 4
            var a = acc[i]
            if (first) {
                a = v
            } else {
                val d = v - a
                val ad = if (d < 0) -d else d
                val w = if (ad > 96) 256 else if (ad > 48) (if (base > 160) base else 160) else base
                a += (d * w) shr 8
            }
            acc[i] = a
            var lv = (a + 8) shr 4
            if (lv < 0) lv = 0 else if (lv > 255) lv = 255
            hist[lv]++
            if (a < mnV) { mnV = a; mnI = i }
            if (a > mxV) { mxV = a; mxI = i }
        }
        haveAcc = true
        minPos = mnI
        maxPos = mxI
        minLevel = (mnV + 8) shr 4
        maxLevel = (mxV + 8) shr 4
        centerLevel = (acc[(ThermalDecoder.OUT_H / 2) * ThermalDecoder.WIDTH + ThermalDecoder.WIDTH / 2] + 8) shr 4

        // 2) границы диапазона
        var newLo = 0f
        var newHi = 255f
        if (contrast != 0) {
            val loT = N * 0.01
            val hiT = N * 0.99
            var cum = 0
            var gotLo = false
            for (v in 0 until 256) {
                cum += hist[v]
                if (!gotLo && cum >= loT) { newLo = v.toFloat(); gotLo = true }
                if (cum >= hiT) { newHi = v.toFloat(); break }
            }
        }
        if (!haveRange) {
            lo = newLo; hi = newHi; haveRange = true
        } else {
            lo += 0.12f * (newLo - lo)         // плавно, чтобы картинка не «дышала»
            hi += 0.12f * (newHi - hi)
        }
        var span = hi - lo
        if (span < 12f) {
            val mid = (hi + lo) / 2f
            lo = mid - 6f
            span = 12f
        }
        val lo16 = (lo * 16f).toInt()
        val scale = ((255 shl 16) / (span * 16f)).toInt()

        // 3) растяжение до 0..255
        java.util.Arrays.fill(hist2, 0)
        for (i in 0 until N) {
            var v = ((acc[i] - lo16) * scale) shr 16
            if (v < 0) v = 0 else if (v > 255) v = 255
            idx[i] = v.toByte()
            hist2[v]++
        }

        // 4) выравнивание гистограммы (plateau) или прямое отображение
        if (contrast == 2) {
            val cap = maxOf(1, 2 * N / 256)
            var total = 0L
            for (b in 0 until 256) {
                if (hist2[b] > cap) hist2[b] = cap
                total += hist2[b]
            }
            var run = 0L
            for (b in 0 until 256) {
                run += hist2[b]
                val target = if (total > 0) run * 255f / total else b.toFloat()
                eqLut[b] += 0.2f * (target - eqLut[b])
                map[b] = eqLut[b].toInt().coerceIn(0, 255)
            }
        } else {
            for (b in 0 until 256) map[b] = b
        }

        // 5) палитра
        for (i in 0 until N) out[i] = pal[map[idx[i].toInt() and 0xFF]]
    }
}
