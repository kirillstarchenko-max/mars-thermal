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
static int disp[N];         // рабочий буфер: декодированный кадр, потом резкость
static int sb1[N];          // итоговая картинка 0..255 до резкости
static int sb2[N];
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

/*
 * Подавление полос по столбцам и строкам прямо по сцене (как в Python-версии): в каждом столбце медиана
 * отклонения пикселей от соседей по строке. Предметы дают отклонение лишь в части строк, а полоса
 * матрицы во всех, поэтому медиана видит только полосу. Оценка обновляется каждый кадр, так что
 * сама подстраивается под АРУ камеры; калибровка по ровной стене с такой камерой не работает:
 * на ровном она растягивает пару уровней на весь диапазон, и в кадре одни контуры.
 */
static const int STRIPE_WIN = 7;            // полуокно сглаживания (15 пикселей)
static const int STRIPE_MAX = 5 * 16;       // полоса не сильнее 5 уровней: остальное считаем сценой
static const int COL_SAMPLES = OUT_H / 2;   // каждая вторая строка
static const int ROW_SAMPLES = WIDTH / 2;   // каждый второй столбец
static int colBuf[WIDTH * COL_SAMPLES];
static int rowBuf[OUT_H * ROW_SAMPLES];
static float colOff[WIDTH];
static float rowOff[OUT_H];
static bool haveStripe = false;

static const int BASE_ALPHA[5] = {256, 150, 100, 64, 30};
static const int CORE = 2;             // перепады до 2 уровней (шум) резкость не усиливает
static const int BORDER = 4;           // края кадра у камеры с мусором: экстремумы там не ищем
static const int SPOT_R = 32;          // окно уточнения горячей/холодной точки, пикселей

static int medianOf(int* a, int n) {
    std::nth_element(a, a + n / 2, a + n);
    return a[n / 2];
}

/** Оценка полос по кадру disp (Y*16) и плавное обновление colOff/rowOff. */
static void estimateStripes() {
    // по столбцам: отклонение от среднего по 15 пикселям строки
    for (int r = 0, n = 0; r < OUT_H; r += 2, ++n) {
        const int* v = disp + r * WIDTH;
        int sum = 0, cnt = 0;
        for (int j = 0; j <= STRIPE_WIN; ++j) { sum += v[j]; ++cnt; }
        for (int j = 0; j < WIDTH; ++j) {
            colBuf[j * COL_SAMPLES + n] = v[j] - sum / cnt;
            const int add = j + STRIPE_WIN + 1, del = j - STRIPE_WIN;
            if (add < WIDTH) { sum += v[add]; ++cnt; }
            if (del >= 0) { sum -= v[del]; --cnt; }
        }
    }
    // по строкам: отклонение от среднего по 15 пикселям столбца (префиксные суммы по столбцу в tmp)
    for (int j = 0; j < WIDTH; ++j) {
        int run = 0;
        for (int r = 0; r < OUT_H; ++r) { run += disp[r * WIDTH + j]; tmp[r * WIDTH + j] = run; }
    }
    for (int r = 0; r < OUT_H; ++r) {
        const int r0 = std::max(0, r - STRIPE_WIN), r1 = std::min(OUT_H - 1, r + STRIPE_WIN);
        for (int j = 0, n = 0; j < WIDTH; j += 2, ++n) {
            const int hi = tmp[r1 * WIDTH + j], lo = r0 > 0 ? tmp[(r0 - 1) * WIDTH + j] : 0;
            rowBuf[r * ROW_SAMPLES + n] = disp[r * WIDTH + j] - (hi - lo) / (r1 - r0 + 1);
        }
    }
    const float k = haveStripe ? 0.03f : 1.0f;
    for (int j = 0; j < WIDTH; ++j) {
        float est = (float)std::clamp(medianOf(colBuf + j * COL_SAMPLES, COL_SAMPLES), -STRIPE_MAX, STRIPE_MAX);
        colOff[j] += k * (est - colOff[j]);
    }
    for (int r = 0; r < OUT_H; ++r) {
        float est = (float)std::clamp(medianOf(rowBuf + r * ROW_SAMPLES, ROW_SAMPLES), -STRIPE_MAX, STRIPE_MAX);
        rowOff[r] += k * (est - rowOff[r]);
    }
    haveStripe = true;
}

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

/**
 * Точка для метки. Сначала центр всех пикселей не дальше 1 уровня от экстремума: для одного объекта
 * это его середина. Если таких объектов несколько, центр попадает между ними, и тогда окно
 * пошагово сдвигается к центру пятна вокруг самого экстремума.
 */
static int spotFind(int pos, int level, bool hot) {
    long sx = 0, sy = 0, n = 0;
    for (int y = BORDER; y < OUT_H - BORDER; ++y) {
        const int* b = blur + y * WIDTH;
        for (int x = BORDER; x < WIDTH - BORDER; ++x) {
            if (hot ? b[x] >= level - 16 : b[x] <= level + 16) { sx += x; sy += y; ++n; }
        }
    }
    if (n > 0) {
        const int c = (int)(sy / n) * WIDTH + (int)(sx / n);
        if (hot ? blur[c] >= level - 16 : blur[c] <= level + 16) return c;
    }
    for (int it = 0; it < 8; ++it) {
        int next = spotCenter(pos, level, hot);
        if (next == pos) break;
        pos = next;
    }
    return pos;
}

JNIEXPORT void JNICALL
Java_com_example_marsthermal_NativeRenderer_renderNative(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray frame_, jint shift,
        jint tnr, jint contrast, jint sharp, jboolean destripe,
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

    // 1) Декодирование Y
    for (int r = 0; r < OUT_H; ++r) {
        int p = (shift + r * ROW + WIDTH) % VALUES;
        int* d = disp + r * WIDTH;
        for (int j = 0; j < WIDTH; ++j) {
            d[j] = (frame[(p << 1) + 1] & 0xFF) << 4;
            p++;
            if (p == VALUES) p = 0;
        }
    }
    if (destripe) estimateStripes();
    else haveStripe = false;

    // 2) Вычитание полос + временной фильтр
    for (int r = 0; r < OUT_H; ++r) {
        const int ro = destripe ? (int)rowOff[r] : 0;
        const int dst = r * WIDTH;
        for (int j = 0; j < WIDTH; ++j) {
            int i = dst + j;
            int v = disp[i];
            if (destripe) {
                v -= (int)colOff[j] + ro;
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
        }
    }
    haveAcc = true;

    // 3) Размытие 3x3, гистограмма, экстремумы
    boxBlur(acc, blur);
    std::memset(hist, 0, sizeof(hist));
    long long sumY = 0;
    int mnV = 0x7FFFFFFF, mxV = -0x7FFFFFFF;
    int mnI = 0, mxI = 0;
    for (int r = 0; r < OUT_H; ++r) {
        const bool rowIn = r >= BORDER && r < OUT_H - BORDER;
        for (int j = 0; j < WIDTH; ++j) {
            int i = r * WIDTH + j;
            int a = acc[i];
            sumY += a >> 4;
            int lv = (a + 8) >> 4;
            if (lv > 255) lv = 255;
            hist[lv]++;
            // экстремумы по размытому и без краёв: битый пиксель и мусор по краям не перетягивают метку
            if (rowIn && j >= BORDER && j < WIDTH - BORDER) {
                int b = blur[i];
                if (b < mnV) { mnV = b; mnI = i; }
                if (b > mxV) { mxV = b; mxI = i; }
            }
        }
    }

    // сброс TNR при резком изменении сцены (появление неба/монитора)
    float avgY = (float)sumY / (float)N;
    if (prevAvgY >= 0.0f && fabsf(avgY - prevAvgY) > 8.0f) {
        haveAcc = false;
        haveRange = false;
    }
    prevAvgY = avgY;

    // 4) Границы диапазона (быстрое сглаживание)
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

    // 5) Растяжение до 0..255
    std::memset(hist2, 0, sizeof(hist2));
    for (int i = 0; i < N; ++i) {
        int v = ((acc[i] - lo16) * scale) >> 16;
        if (v < 0) v = 0; else if (v > 255) v = 255;
        idx[i] = (uint8_t)v;
        hist2[v]++;
    }

    // 6) Выравнивание (plateau) или прямое отображение
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
    for (int i = 0; i < N; ++i) idx[i] = (uint8_t)map256[idx[i]];

    // 7) Резкость по итоговой картинке: после растяжения контраста, иначе выравнивание раздувает
    //    её в квадраты и ореолы. Выход за min/max соседей 3x3 ограничен 4/8/12 уровнями: ореол едва заметен.
    const int k = std::clamp((int)sharp, 0, 3);
    if (k > 0) {
        const int lim = 4 * k;
        for (int i = 0; i < N; ++i) sb1[i] = idx[i];
        boxBlur(sb1, sb2);
        boxBlur(sb2, disp);
        for (int r = 1; r < OUT_H - 1; ++r) {
            for (int j = 1; j < WIDTH - 1; ++j) {
                const int i = r * WIDTH + j;
                const int v = sb1[i];
                int d = v - disp[i];
                if (d > CORE) d -= CORE; else if (d < -CORE) d += CORE; else d = 0;
                if (d == 0) continue;
                int s = v + d * k;              // сила 1 / 2 / 3
                int mn = v, mx = v;
                for (int dy = -WIDTH; dy <= WIDTH; dy += WIDTH) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        int q = sb1[i + dy + dx];
                        if (q < mn) mn = q;
                        if (q > mx) mx = q;
                    }
                }
                idx[i] = (uint8_t)std::clamp(s, std::max(0, mn - lim), std::min(255, mx + lim));
            }
        }
    }

    // 8) Палитра -> Bitmap (RGBA_8888: в памяти ABGR little-endian)
    uint32_t* out = (uint32_t*)pixels;
    for (int i = 0; i < N; ++i) {
        uint32_t c = (uint32_t)pal[idx[i]];
        uint32_t a = (c >> 24) & 0xFF;
        uint32_t r = (c >> 16) & 0xFF;
        uint32_t g = (c >> 8) & 0xFF;
        uint32_t b = c & 0xFF;
        out[i] = (a << 24) | (b << 16) | (g << 8) | r;
    }

    // 9) Метрики для прицела
    if (stats_ != nullptr && env->GetArrayLength(stats_) >= 6) {
        jint stats[6];
        stats[0] = spotFind(mnI, mnV, false);
        stats[1] = spotFind(mxI, mxV, true);
        stats[2] = (mnV + 8) >> 4;
        stats[3] = (mxV + 8) >> 4;
        int ci = (OUT_H / 2) * WIDTH + WIDTH / 2;
        stats[4] = (blur[ci] + 8) >> 4;
        stats[5] = 0;
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
    haveStripe = false;
    for (int i = 0; i < 256; ++i) eqLut[i] = (float)i;
}


} // extern "C"
