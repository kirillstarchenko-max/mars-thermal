#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <algorithm>

extern "C" {

static const int WIDTH = 640;
static const int OUT_H = 512;
static const int ROW = 1280;
static const int ROWS = 513;
static const int VALUES = ROWS * ROW;          // 656640
static const int FRAME_BYTES = VALUES * 2;     // 1313280
static const int N = OUT_H * WIDTH;            // 327680

static int acc[N];          // состояние временного фильтра, Y*16
static int tmp[N];          // суммы по строкам для размытия 3x3
static int blur[N];         // acc, размытый 3x3: поиск горячей/холодной точки
static int blur2[N];        // blur, размытый ещё раз (~5x5): основа для резкости
static int disp[N];         // что показываем (acc после резкости)
static uint8_t idx[N];
static int hist[256];
static int hist2[256];
static int map256[256];
static float eqLut[256];
static bool haveAcc = false;
static bool haveRange = false;
static float loF = 0.0f;
static float hiF = 255.0f;
static float prevAvgY = -1.0f;

// калибровка по ровной поверхности: поправка на каждый пиксель (неравномерность и полосы матрицы)
static int16_t offs[N];
static int calSum[N];
static bool haveOffs = false;
static int calLeft = 0;
static int calTotal = 0;

static const int BASE_ALPHA[5] = {256, 150, 100, 64, 30};
static const int CORE = 12;            // мелкие перепады (шум) резкость не усиливает, Y*16
static const int SPOT_R = 24;          // окно уточнения горячей/холодной точки, пикселей

/** Размытие 3x3 (среднее), на краях повторяется крайний пиксель. */
static void boxBlur(const int* src, int* dst) {
    for (int r = 0; r < OUT_H; ++r) {
        const int* s = src + r * WIDTH;
        int* t = tmp + r * WIDTH;
        t[0] = s[0] * 2 + s[1];
        for (int j = 1; j < WIDTH - 1; ++j) t[j] = s[j - 1] + s[j] + s[j + 1];
        t[WIDTH - 1] = s[WIDTH - 2] + s[WIDTH - 1] * 2;
    }
    for (int r = 0; r < OUT_H; ++r) {
        const int* up = tmp + (r > 0 ? r - 1 : 0) * WIDTH;
        const int* mid = tmp + r * WIDTH;
        const int* dn = tmp + (r < OUT_H - 1 ? r + 1 : r) * WIDTH;
        int* d = dst + r * WIDTH;
        for (int j = 0; j < WIDTH; ++j) d[j] = ((up[j] + mid[j] + dn[j]) * 7282) >> 16;   // /9
    }
}

/**
 * Центр пятна около экстремума: среднее положение пикселей не дальше 1 уровня от него в окне SPOT_R.
 * Без этого на засвеченном объекте (всё = 255) метка прыгает в его левый верхний угол.
 */
static int spotCenter(int pos, int level, bool hot) {
    const int px = pos % WIDTH, py = pos / WIDTH;
    const int x0 = std::max(0, px - SPOT_R), x1 = std::min(WIDTH - 1, px + SPOT_R);
    const int y0 = std::max(0, py - SPOT_R), y1 = std::min(OUT_H - 1, py + SPOT_R);
    long sx = 0, sy = 0, n = 0;
    for (int y = y0; y <= y1; ++y) {
        const int* b = blur + y * WIDTH;
        for (int x = x0; x <= x1; ++x) {
            if (hot ? b[x] >= level - 16 : b[x] <= level + 16) { sx += x; sy += y; ++n; }
        }
    }
    if (n == 0) return pos;
    return (int)(sy / n) * WIDTH + (int)(sx / n);
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_renderNative(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray frame_, jint shift,
        jint tnr, jint contrast, jint sharp,
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
    const bool first = !haveAcc;
    const bool calib = calLeft > 0;

    // 1) Декодирование Y + поправка калибровки + временной фильтр (один проход)
    for (int r = 0; r < OUT_H; ++r) {
        int p = (shift + r * ROW + WIDTH) % VALUES;
        const int dst = r * WIDTH;
        for (int j = 0; j < WIDTH; ++j) {
            int i = dst + j;
            int v = (frame[(p << 1) + 1] & 0xFF) << 4;
            if (calib) calSum[i] += v;
            if (haveOffs) {
                v -= offs[i];
                if (v < 0) v = 0; else if (v > 4080) v = 4080;
            }

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

            p++;
            if (p == VALUES) p = 0;
        }
    }
    haveAcc = true;

    // калибровка набрала кадры: поправка = отклонение пикселя от среднего по кадру
    if (calib && --calLeft == 0) {
        long long total = 0;
        for (int i = 0; i < N; ++i) total += calSum[i];
        const int mean = (int)(total / ((long long)N * calTotal));
        for (int i = 0; i < N; ++i) {
            offs[i] = (int16_t)std::clamp(calSum[i] / calTotal - mean, -2000, 2000);
        }
        haveOffs = true;
        haveAcc = false;
    }

    // 2) Размытие 3x3, резкость (нерезкое маскирование с порогом шума), гистограмма, экстремумы
    boxBlur(acc, blur);
    const int k = std::clamp((int)sharp, 0, 3);
    if (k > 0) boxBlur(blur, blur2);
    std::memset(hist, 0, sizeof(hist));
    long long sumY = 0;
    int mnV = 0x7FFFFFFF, mxV = -0x7FFFFFFF;
    int mnI = 0, mxI = 0;
    for (int i = 0; i < N; ++i) {
        int a = acc[i];
        sumY += a >> 4;
        if (k > 0) {
            int d = a - blur2[i];
            if (d > CORE) d -= CORE; else if (d < -CORE) d += CORE; else d = 0;
            a += (d * k) >> 1;          // сила 0.5 / 1.0 / 1.5
            if (a < 0) a = 0; else if (a > 4080) a = 4080;
        }
        disp[i] = a;
        int lv = (a + 8) >> 4;
        if (lv > 255) lv = 255;
        hist[lv]++;
        // экстремумы по размытому: одиночный битый пиксель не перетягивает метку
        int b = blur[i];
        if (b < mnV) { mnV = b; mnI = i; }
        if (b > mxV) { mxV = b; mxI = i; }
    }

    // сброс TNR при резком изменении сцены (появление неба/монитора)
    float avgY = (float)sumY / (float)N;
    if (prevAvgY >= 0.0f && fabsf(avgY - prevAvgY) > 8.0f) {
        haveAcc = false;
        haveRange = false;
    }
    prevAvgY = avgY;

    // 3) Границы диапазона (быстрое сглаживание)
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
        loF += 0.5f * (newLo - loF);
        hiF += 0.5f * (newHi - hiF);
    }
    float span = hiF - loF;
    if (span < 12.0f) {
        float mid = (hiF + loF) * 0.5f;
        loF = mid - 6.0f;
        span = 12.0f;
    }
    const int lo16 = (int)(loF * 16.0f);
    const int scale = (int)((255 << 16) / (span * 16.0f));

    // 4) Растяжение до 0..255
    std::memset(hist2, 0, sizeof(hist2));
    for (int i = 0; i < N; ++i) {
        int v = ((disp[i] - lo16) * scale) >> 16;
        if (v < 0) v = 0; else if (v > 255) v = 255;
        idx[i] = (uint8_t)v;
        hist2[v]++;
    }

    // 5) Выравнивание (plateau) или прямое отображение
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

    // 6) Палитра -> Bitmap (RGBA_8888: в памяти ABGR little-endian)
    uint32_t* out = (uint32_t*)pixels;
    for (int i = 0; i < N; ++i) {
        uint32_t c = (uint32_t)pal[map256[idx[i]]];
        uint32_t a = (c >> 24) & 0xFF;
        uint32_t r = (c >> 16) & 0xFF;
        uint32_t g = (c >> 8) & 0xFF;
        uint32_t b = c & 0xFF;
        out[i] = (a << 24) | (b << 16) | (g << 8) | r;
    }

    // 7) Метрики для прицела
    if (stats_ != nullptr) {
        jint stats[6];
        // два прохода: второй окно уже вокруг центра пятна
        stats[0] = spotCenter(spotCenter(mnI, mnV, false), mnV, false);
        stats[1] = spotCenter(spotCenter(mxI, mxV, true), mxV, true);
        stats[2] = (mnV + 8) >> 4;
        stats[3] = (mxV + 8) >> 4;
        int ci = (OUT_H / 2) * WIDTH + WIDTH / 2;
        stats[4] = (blur[ci] + 8) >> 4;
        stats[5] = calLeft;
        env->SetIntArrayRegion(stats_, 0, 6, stats);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    env->ReleaseByteArrayElements(frame_, frame, JNI_ABORT);
    env->ReleaseIntArrayElements(pal_, pal, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_resetNative(JNIEnv*, jobject) {
    haveAcc = false;
    haveRange = false;
    prevAvgY = -1.0f;
    for (int i = 0; i < 256; ++i) eqLut[i] = (float)i;
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_calibStart(JNIEnv*, jobject, jint frames) {
    std::memset(calSum, 0, sizeof(calSum));
    calTotal = std::max(1, (int)frames);
    calLeft = calTotal;
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_calibClear(JNIEnv*, jobject) {
    calLeft = 0;
    haveOffs = false;
    haveAcc = false;
}

/** Копирует поправку в out (N значений); false, если калибровки нет. */
JNIEXPORT jboolean JNICALL
Java_com_example_marsthermal_NativeRenderer_calibGet(JNIEnv* env, jobject, jshortArray out) {
    if (!haveOffs || env->GetArrayLength(out) != N) return JNI_FALSE;
    env->SetShortArrayRegion(out, 0, N, offs);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_calibSet(JNIEnv* env, jobject, jshortArray in) {
    if (env->GetArrayLength(in) != N) return;
    env->GetShortArrayRegion(in, 0, N, offs);
    haveOffs = true;
    haveAcc = false;
}

} // extern "C"
