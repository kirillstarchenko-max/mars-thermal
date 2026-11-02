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
     * Декодирует Y + применяет TNR/контраст/палитру + пишет прямо в Bitmap (RGBA_8888).
     * @param stats IntArray(6): [minPos, maxPos, minLevel, maxLevel, centerLevel, 0]
     */
    external fun renderNative(
        frame: ByteArray, shift: Int,
        tnr: Int, contrast: Int,
        palette: IntArray,
        bitmap: Bitmap,
        stats: IntArray
    )

    external fun resetNative()
}
