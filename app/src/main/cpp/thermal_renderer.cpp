#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <algorithm>

extern "C" {

// Константы потока
static const int WIDTH = 640;
static const int OUT_H = 512;
static const int ROW = 1280;
static const int ROWS = 513;
static const int VALUES = ROWS * ROW;          // 656640
static const int FRAME_BYTES = VALUES * 2;     // 1313280
static const int N = OUT_H * WIDTH;            // 327680

// Состояние рендерера (аналог ThermalRenderer.kt)
static int acc[N];          // Y*16, временной фильтр
static uint8_t idx[N];      // индексы 0..255 после контраста
static int hist[256];
static int hist2[256];
static int map256[256];
static float eqLut[256];
static bool haveAcc = false;
static bool haveRange = false;
static float loF = 0.0f;
static float hiF = 255.0f;

// Вес нового кадра (из 256) для уровней TNR 0..4
static const int BASE_ALPHA[5] = {256, 150, 100, 64, 30};

// Вспомогательная: Y (байт) -> 16-битный «уровень» для TNR
static inline int yByte(const uint8_t* f, int wordIndex) {
    return f[(wordIndex << 1) + 1];
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_renderNative(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray frame_, jint shift,
        jint tnr, jint contrast,
        jintArray pal_,
        jobject bitmap,
        jintArray stats_) {

    jbyte* frame = env->GetByteArrayElements(frame_, nullptr);
    jint* pal = env->GetIntArrayElements(pal_, nullptr);
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        info.width != WIDTH || info.height != OUT_H || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        env->ReleaseByteArrayElements(frame_, frame, JNI_ABORT);
        env->ReleaseIntArrayElements(pal_, pal, JNI_ABORT);
        return;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        env->ReleaseByteArrayElements(frame_, frame, JNI_ABORT);
        env->ReleaseIntArrayElements(pal_, pal, JNI_ABORT);
        return;
    }

    const int base = BASE_ALPHA[std::clamp((int)tnr, 0, 4)];
    std::memset(hist, 0, sizeof(hist));

    int mnV = 0x7FFFFFFF, mxV = -0x7FFFFFFF;
    int mnI = 0, mxI = 0;
    const bool first = !haveAcc;

    // 1) Временной фильтр + гистограмма + экстремумы
    //    Декодируем Y в том же проходе: shift задаёт начало кадра в словах
    for (int r = 0; r < OUT_H; ++r) {
        int p = (shift + r * ROW + WIDTH) % VALUES;
        const int dst = r * WIDTH;
        for (int j = 0; j < WIDTH; ++j) {
            int Y = frame[(p << 1) + 1] & 0xFF;
            int v = Y << 4;
            int i = dst + j;

            int a = acc[i];
            if (first) {
                a = v;
            } else {
                int d = v - a;
                int ad = d < 0 ? -d : d;
                int w = (ad > 96) ? 256 : (ad > 48 ? (base > 160 ? base : 160) : base);
                a += (d * w) >> 8;
            }
            acc[i] = a;

            int lv = (a + 8) >> 4;
            if (lv < 0) lv = 0; else if (lv > 255) lv = 255;
            hist[lv]++;

            if (a < mnV) { mnV = a; mnI = i; }
            if (a > mxV) { mxV = a; mxI = i; }

            p++;
            if (p == VALUES) p = 0;
        }
    }
    haveAcc = true;

    // 2) Границы диапазона
    float newLo = 0.0f, newHi = 255.0f;
    if (contrast != 0) {
        const float loT = N * 0.01f;
        const float hiT = N * 0.99f;
        int cum = 0;
        bool gotLo = false;
        for (int v = 0; v < 256; ++v) {
            cum += hist[v];
            if (!gotLo && cum >= loT) { newLo = (float)v; gotLo = true; }
            if (cum >= hiT) { newHi = (float)v; break; }
        }
    }
    if (!haveRange) {
        loF = newLo; hiF = newHi; haveRange = true;
    } else {
        loF += 0.12f * (newLo - loF);
        hiF += 0.12f * (newHi - hiF);
    }
    float span = hiF - loF;
    if (span < 12.0f) {
        float mid = (hiF + loF) * 0.5f;
        loF = mid - 6.0f;
        span = 12.0f;
    }
    const int lo16 = (int)(loF * 16.0f);
    const int scale = (int)((255 << 16) / (span * 16.0f));

    // 3) Растяжение до 0..255
    std::memset(hist2, 0, sizeof(hist2));
    for (int i = 0; i < N; ++i) {
        int v = ((acc[i] - lo16) * scale) >> 16;
        if (v < 0) v = 0; else if (v > 255) v = 255;
        idx[i] = (uint8_t)v;
        hist2[v]++;
    }

    // 4) Выравнивание (plateau) или прямое отображение
    if (contrast == 2) {
        const int cap = std::max(1, 2 * N / 256);
        long long total = 0;
        for (int b = 0; b < 256; ++b) {
            if (hist2[b] > cap) hist2[b] = cap;
            total += hist2[b];
        }
        long long run = 0;
        for (int b = 0; b < 256; ++b) {
            run += hist2[b];
            float target = (total > 0) ? (float)run * 255.0f / (float)total : (float)b;
            eqLut[b] += 0.2f * (target - eqLut[b]);
            int m = (int)eqLut[b];
            if (m < 0) m = 0; else if (m > 255) m = 255;
            map256[b] = m;
        }
    } else {
        for (int b = 0; b < 256; ++b) map256[b] = b;
    }

    // 5) Палитра -> Bitmap (RGBA_8888: в памяти это ABGR little-endian)
    uint32_t* out = (uint32_t*)pixels;
    for (int i = 0; i < N; ++i) {
        // pal[] из Kotlin: 0xAARRGGBB. В памяти Bitmap RGBA_8888 ожидает R,G,B,A по байтам,
        // что при чтении как uint32_t little-endian даёт 0xAABBGGRR. Поэтому переставляем R и B.
        uint32_t c = (uint32_t)pal[map256[idx[i]]];
        uint32_t a = (c >> 24) & 0xFF;
        uint32_t r = (c >> 16) & 0xFF;
        uint32_t g = (c >> 8) & 0xFF;
        uint32_t b = c & 0xFF;
        out[i] = (a << 24) | (b << 16) | (g << 8) | r;
    }

    // 6) Метрики для прицела (center/min/max)
    if (stats_ != nullptr) {
        jint stats[6];
        stats[0] = mnI;
        stats[1] = mxI;
        stats[2] = (mnV + 8) >> 4;
        stats[3] = (mxV + 8) >> 4;
        int ci = (OUT_H / 2) * WIDTH + WIDTH / 2;
        stats[4] = (acc[ci] + 8) >> 4;
        stats[5] = 0;
        env->SetIntArrayRegion(stats_, 0, 6, stats);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    env->ReleaseByteArrayElements(frame_, frame, JNI_ABORT);
    env->ReleaseIntArrayElements(pal_, pal, JNI_ABORT);
}

// Сброс состояния (при пересинхронизации)
JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_resetNative(JNIEnv*, jobject) {
    haveAcc = false;
    haveRange = false;
    for (int i = 0; i < 256; ++i) eqLut[i] = (float)i;
}

} // extern "C"
