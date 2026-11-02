package com.example.marsthermal

import android.graphics.Bitmap

object NativeRenderer {
    @Volatile var available = false
        private set

    init {
        try {
            System.loadLibrary("thermal")
            available = true
        } catch (t: Throwable) {
            available = false
        }
    }

    /**
     * Декодирует Y + калибровка + TNR + резкость + контраст + палитра, пишет прямо в Bitmap (RGBA_8888).
     * @param sharp резкость 0..3 (0 выкл)
     * @param stats IntArray(8): [холодная поз., горячая поз., холодная, горячая, центр,
     *   осталось кадров калибровки, сила поправки x100 (-1 нет), итог калибровки (1 ок, -1 сцена неровная)]
     */
    external fun renderNative(
        frame: ByteArray, shift: Int,
        tnr: Int, contrast: Int, sharp: Int,
        palette: IntArray,
        bitmap: Bitmap,
        stats: IntArray
    )

    external fun resetNative()

    /** Начать калибровку: следующие frames кадров усредняются, камера должна смотреть на ровную поверхность. */
    external fun calibStart(frames: Int)

    external fun calibClear()

    /** Текущая поправка (ThermalRenderer.N значений) или false, если калибровки нет. */
    external fun calibGet(out: ShortArray): Boolean

    external fun calibSet(data: ShortArray)
}
