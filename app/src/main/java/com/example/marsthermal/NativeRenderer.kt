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
     * Декодирует Y + подавление полос + TNR + контраст + резкость + палитра, пишет прямо в Bitmap (RGBA_8888).
     * @param sharp резкость 0..3 (0 выкл)
     * @param destripe убирать полосы по столбцам/строкам
     * @param stats IntArray(6): [холодная поз., горячая поз., холодная, горячая, центр, 0]
     */
    external fun renderNative(
        frame: ByteArray, shift: Int,
        tnr: Int, contrast: Int, sharp: Int, destripe: Boolean,
        palette: IntArray,
        bitmap: Bitmap,
        stats: IntArray
    )

    external fun resetNative()
}
