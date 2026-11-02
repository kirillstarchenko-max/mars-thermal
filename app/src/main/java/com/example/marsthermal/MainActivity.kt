package com.example.marsthermal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val VID = 0x04b4
        const val PID = 0xf7f7
        const val EP_IN = 0x81
        const val ACTION_PERM = "com.example.marsthermal.USB_PERMISSION"
        const val REQ_SIZE = 65536
        const val REQ_COUNT = 16
        const val STALL_MS = 4000L
        private val CONTRAST_NAMES = arrayOf("как у камеры", "авто", "выравнивание")
        private val SHAPE_NAMES = arrayOf("выкл", "крест", "крест+точка", "точка", "круг", "дальномер")
        private val COLOR_NAMES = arrayOf("белый", "красный", "зелёный", "жёлтый", "голубой")
        private val COLOR_VALUES = intArrayOf(
            Color.WHITE, Color.rgb(255, 40, 40), Color.rgb(40, 255, 60), Color.rgb(255, 230, 0), Color.rgb(0, 220, 255))
        private val LABEL_NAMES = arrayOf("выкл", "значение", "+ горячая/холодная")
        private val HL_NAMES = arrayOf("выкл", "верх 5%", "верх 15%", "верх 30%")
        private val HL_THRESHOLD = intArrayOf(256, 242, 217, 178)
        private val ZOOM_STEPS = floatArrayOf(1f, 2f, 4f)
        const val ZOOM_MAX = 4f
        const val AIM_MAX_X = 200            // предел пристрелки, пикселей кадра
        const val AIM_MAX_Y = 160
        private val SHARP_NAMES = arrayOf("выкл", "слабая", "средняя", "сильная")
        private val VIEW_NAMES = arrayOf("вписать", "на весь экран", "растянуть")
        private val ROTATE_NAMES = arrayOf("авто", "горизонт.", "вертик.")
        private val ROTATE_MODES = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        private val VIEW_SCALE = arrayOf(
            ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.CENTER_CROP, ImageView.ScaleType.FIT_XY)
    }

    private lateinit var usb: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var image: ImageView
    private lateinit var msgView: TextView
    private lateinit var infoView: TextView
    private lateinit var bar: LinearLayout
    private lateinit var btnPal: Button
    private lateinit var btnContrast: Button
    private lateinit var btnNoise: Button
    private lateinit var btnShape: Button
    private lateinit var btnLabels: Button
    private lateinit var btnHighlight: Button
    private lateinit var btnZoom: Button
    private lateinit var btnZero: Button
    private lateinit var btnRec: Button
    private lateinit var zeroPanel: LinearLayout
    private lateinit var zeroLabel: TextView
    private lateinit var btnSharp: Button
    private lateinit var btnView: Button
    private lateinit var btnRotate: Button
    private lateinit var buttons: List<Button>
    private lateinit var btnInfo: Button

    // настройки отображения
    @Volatile private var palIdx = 0
    @Volatile private var invert = false
    @Volatile private var contrast = 0
    @Volatile private var tnr = 2
    @Volatile private var retShape = 1
    @Volatile private var retColor = 0
    @Volatile private var labels = 0
    @Volatile private var highlight = 0
    @Volatile private var zoom = 1f
    @Volatile private var aimX = 0            // пристрелка: сдвиг точки прицеливания от центра, пикселей кадра
    @Volatile private var aimY = 0
    @Volatile private var zeroing = false
    @Volatile private var recRequest = 0      // 1 начать запись, 2 остановить (выполняет поток обработки)
    @Volatile private var recording = false
    @Volatile private var recStartMs = 0L
    private var pinched = false
    @Volatile private var showInfo = false
    @Volatile private var sharp = 1
    @Volatile private var destripe = false
    private var viewMode = 0
    private var rotateMode = 0
    @Volatile private var palette: IntArray = Palettes.get(0, false)

    @Volatile private var running = false
    private var permRequested = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!running) connectOrRequest()
            handler.postDelayed(this, 2000)
        }
    }
    private var readerThread: Thread? = null
    private var workerThread: Thread? = null

    private val free = ArrayBlockingQueue<ByteArray>(8)
    private val ready = ArrayBlockingQueue<ByteArray>(2)

    private val rxFrames = AtomicInteger(0)
    private val rxBytes = AtomicLong(0)
    private val relocks = AtomicInteger(0)
    private val rejected = AtomicInteger(0)
    private val recoveries = AtomicInteger(0)
    private val shown = AtomicInteger(0)
    @Volatile private var readerMode = "-"
    @Volatile private var lastErr = ""
    @Volatile private var lastReject = ""
    @Volatile private var stalled = false
    @Volatile private var relockRequest = false
    @Volatile private var tDec = 0.0
    @Volatile private var tRen = 0.0
    @Volatile private var tUi = 0.0
    @Volatile private var dumps = 0


    // статистика от NDK/Kotlin рендерера (для прицела)
    @Volatile private var rendererMinPos = 0
    @Volatile private var rendererMaxPos = 0
    @Volatile private var rendererMinLevel = 0
    @Volatile private var rendererMaxLevel = 0
    @Volatile private var rendererCenterLevel = 0
    private val nativeStats = IntArray(6)

    private val bitmaps = arrayOf(
        Bitmap.createBitmap(ThermalDecoder.WIDTH, ThermalDecoder.OUT_H, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(ThermalDecoder.WIDTH, ThermalDecoder.OUT_H, Bitmap.Config.ARGB_8888)
    )
    @Volatile private var lastBmp: Bitmap? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (showInfo) {
                infoView.visibility = View.VISIBLE
                val mode = if (NativeRenderer.available) "NDK" else "Kotlin"
                infoView.text = "reader: $readerMode | рендер: $mode | ${rxBytes.get() / 1_000_000} МБ | кадров ${rxFrames.get()} | " +
                        "показано ${shown.get()}\n" +
                        "срывов ${relocks.get()} | брак ${rejected.get()} | переподк. ${recoveries.get()}\n" +
                        "время: разбор %.1f | обработка %.1f | вывод %.1f мс\n".format(tDec, tRen, tUi) +
                        (if (lastReject.isNotEmpty()) "последний брак: $lastReject\n" else "") +
                        (if (lastErr.isNotEmpty()) "ОШИБКА: $lastErr" else "")
            } else {
                infoView.visibility = View.GONE
            }
            handler.postDelayed(this, 500)
        }
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_PERM) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) start() else setStatus("Нет разрешения на USB")
        }
    }

    // --------------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 24 && pm.isSustainedPerformanceModeSupported) {
                window.setSustainedPerformanceMode(true)
            }
        } catch (_: Throwable) {}

        prefs = getPreferences(Context.MODE_PRIVATE)
        palIdx = prefs.getInt("pal", 0)
        invert = prefs.getBoolean("invert", false)
        contrast = prefs.getInt("contrast", 0)
        tnr = prefs.getInt("tnr", 2)
        // раньше был один «Прицел» 0..3: переносим в форму сетки и метки
        val oldReticle = prefs.getInt("reticle", 1)
        retShape = prefs.getInt("ret_shape", if (oldReticle == 0) 0 else 1).coerceIn(0, SHAPE_NAMES.size - 1)
        retColor = prefs.getInt("ret_color", 0).coerceIn(0, COLOR_NAMES.size - 1)
        labels = prefs.getInt("labels", (oldReticle - 1).coerceIn(0, 2)).coerceIn(0, LABEL_NAMES.size - 1)
        highlight = prefs.getInt("highlight", 0).coerceIn(0, HL_NAMES.size - 1)
        zoom = prefs.getFloat("zoom", 1f).coerceIn(1f, ZOOM_MAX)
        aimX = prefs.getInt("aim_x", 0)
        aimY = prefs.getInt("aim_y", 0)
        showInfo = prefs.getBoolean("info", false)
        sharp = prefs.getInt("sharp", 1)
        viewMode = prefs.getInt("view", 0)
        destripe = prefs.getBoolean("destripe", false)
        rotateMode = prefs.getInt("rotate", 0).coerceIn(0, ROTATE_MODES.size - 1)
        requestedOrientation = ROTATE_MODES[rotateMode]
        // калибровки больше нет: убираем её старые файлы
        java.io.File(filesDir, "calib.bin").delete()
        java.io.File(filesDir, "calib2.bin").delete()
        palette = currentPalette()

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        msgView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 14f
            text = "Ищу камеру..."
        }
        infoView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 11f
            visibility = View.GONE
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
        }
        btnPal = makeButton({ cyclePalette(1) }, { invert = !invert; applySettings() })
        btnContrast = makeButton({ contrast = (contrast + 1) % CONTRAST_NAMES.size; applySettings() })
        btnNoise = makeButton({ tnr = (tnr + 1) % 5; applySettings() }, {
            destripe = !destripe
            applySettings()
            toast(if (destripe) "Подавление полос включено" else "Подавление полос выключено")
        })
        btnSharp = makeButton({ sharp = (sharp + 1) % SHARP_NAMES.size; applySettings() })
        btnShape = makeButton({ retShape = (retShape + 1) % SHAPE_NAMES.size; applySettings() },
            { retColor = (retColor + 1) % COLOR_NAMES.size; applySettings() })
        btnLabels = makeButton({ labels = (labels + 1) % LABEL_NAMES.size; applySettings() })
        btnHighlight = makeButton({ highlight = (highlight + 1) % HL_NAMES.size; applySettings() })
        btnZoom = makeButton({ zoom = nextZoomStep(zoom); applySettings() }, { zoom = 1f; applySettings() })
        btnZero = makeButton({ zeroing = !zeroing; refreshButtons() })
        btnRec = makeButton({ recRequest = if (recording) 2 else 1 })
        btnView = makeButton({ viewMode = (viewMode + 1) % VIEW_NAMES.size; applySettings() })
        val btnSnap = makeButton({ takeSnapshot() })
        btnSnap.text = "Снимок"
        btnInfo = makeButton({ showInfo = !showInfo; dumps = 0; applySettings() })
        btnRotate = makeButton({ rotateMode = (rotateMode + 1) % ROTATE_MODES.size; applySettings() })
        buttons = listOf(btnPal, btnContrast, btnNoise, btnSharp, btnShape, btnLabels, btnHighlight,
            btnZoom, btnView, btnRotate, btnZero, btnSnap, btnRec, btnInfo)
        layoutBar()

        // панель пристрелки: стрелки двигают точку прицеливания на 1 пиксель кадра, долгое нажатие — на 10
        zeroLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
        zeroPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xAA000000.toInt())
            visibility = View.GONE
        }
        zeroPanel.addView(zeroLabel)
        for ((label, dx, dy) in listOf(Triple("◀", -1, 0), Triple("▶", 1, 0), Triple("▲", 0, -1), Triple("▼", 0, 1))) {
            zeroPanel.addView(makeButton({ moveAim(dx, dy) }, { moveAim(dx * 10, dy * 10) }).apply { text = label },
                LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        zeroPanel.addView(makeButton({ aimX = 0; aimY = 0; applySettings() }).apply { text = "Сброс" })
        zeroPanel.addView(makeButton({ zeroing = false; refreshButtons() }).apply { text = "Готово" })

        // щипок двумя пальцами — плавный зум; одиночное касание по-прежнему прячет/показывает кнопки
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, ZOOM_MAX)
                refreshButtons()
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) = applySettings()
        })
        image.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            scaleDetector.onTouchEvent(e)
            if (e.pointerCount > 1 || scaleDetector.isInProgress) pinched = true
            pinched
        }
        image.setOnClickListener {
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val root = FrameLayout(this)
        root.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(msgView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))
        root.addView(infoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { topMargin = 70 })
        root.addView(zeroPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        refreshButtons()

        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(ACTION_PERM), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Кнопки в два ряда в альбомной ориентации и в три — в книжной, чтобы не сжимались. */
    private fun layoutBar() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        bar.removeAllViews()
        for (row in buttons.chunked(if (portrait) 5 else 7)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in row) {
                (b.parent as? ViewGroup)?.removeView(b)
                line.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            bar.addView(line, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // активность не пересоздаётся при повороте (configChanges в манифесте), USB-поток не прерывается
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutBar()
    }

    private fun makeButton(onClick: () -> Unit, onLong: (() -> Unit)? = null): Button =
        Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA202020.toInt())
            setPadding(6, 10, 6, 10)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }

    private fun cyclePalette(step: Int) {
        palIdx = (palIdx + step + Palettes.NAMES.size) % Palettes.NAMES.size
        applySettings()
    }

    private fun currentPalette(): IntArray =
        if (highlight > 0) Palettes.isotherm(HL_THRESHOLD[highlight]) else Palettes.get(palIdx, invert)

    private fun nextZoomStep(z: Float): Float = ZOOM_STEPS.firstOrNull { it > z + 0.05f } ?: 1f

    private fun moveAim(dx: Int, dy: Int) {
        aimX = (aimX + dx).coerceIn(-AIM_MAX_X, AIM_MAX_X)
        aimY = (aimY + dy).coerceIn(-AIM_MAX_Y, AIM_MAX_Y)
        applySettings()
    }

    private fun applySettings() {
        palette = currentPalette()
        prefs.edit()
            .putInt("pal", palIdx).putBoolean("invert", invert).putInt("contrast", contrast)
            .putInt("tnr", tnr).putBoolean("info", showInfo)
            .putInt("ret_shape", retShape).putInt("ret_color", retColor).putInt("labels", labels)
            .putInt("highlight", highlight).putFloat("zoom", zoom)
            .putInt("aim_x", aimX).putInt("aim_y", aimY)
            .putInt("sharp", sharp).putInt("view", viewMode).putBoolean("destripe", destripe)
            .putInt("rotate", rotateMode)
            .apply()
        requestedOrientation = ROTATE_MODES[rotateMode]
        refreshButtons()
    }

    private fun refreshButtons() {
        btnPal.text = "Палитра: ${Palettes.NAMES[palIdx]}${if (invert) " (инв.)" else ""}"
        btnContrast.text = "Контраст: ${CONTRAST_NAMES[contrast]}"
        btnNoise.text = "Шумоподавл.: ${if (tnr == 0) "выкл" else tnr.toString()}${if (destripe) " +полосы" else ""}"
        btnSharp.text = "Резкость: ${SHARP_NAMES[sharp]}"
        btnShape.text = "Сетка: ${SHAPE_NAMES[retShape]}, ${COLOR_NAMES[retColor]}"
        btnLabels.text = "Метки: ${LABEL_NAMES[labels]}"
        btnHighlight.text = "Подсветка: ${HL_NAMES[highlight]}"
        btnZoom.text = "Зум: %.1fx".format(zoom)
        btnZero.text = if (zeroing) "Пристрелка: идёт" else "Пристрелка"
        btnRec.text = if (recording) "■ Стоп" else "● Запись"
        zeroPanel.visibility = if (zeroing) View.VISIBLE else View.GONE
        zeroLabel.text = "X %+d  Y %+d".format(aimX, aimY)
        btnView.text = "Вид: ${VIEW_NAMES[viewMode]}"
        btnInfo.text = if (showInfo) "Инфо: вкл" else "Инфо: выкл"
        btnRotate.text = "Поворот: ${ROTATE_NAMES[rotateMode]}"
        image.scaleType = VIEW_SCALE[viewMode]
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun takeSnapshot() {
        val src = lastBmp
        if (src == null || Build.VERSION.SDK_INT < 29) {
            toast("Снимок недоступен")
            return
        }
        val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: return
        thread(name = "snapshot") {
            try {
                val big = Bitmap.createScaledBitmap(copy, 1280, 1024, true)
                val name = "mars_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MarsThermal")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    toast("Сохранено: Pictures/MarsThermal/$name")
                } else {
                    toast("Не удалось сохранить снимок")
                }
            } catch (t: Throwable) {
                toast("Ошибка снимка: $t")
            }
        }
    }

    private fun dumpFrame(name: String, f: ByteArray) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MarsThermal")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(f, 0, ThermalDecoder.FRAME_BYTES) }
        } catch (_: Throwable) {}
    }

    // ----------------------------------------------------------- жизненный цикл

    override fun onResume() {
        super.onResume()
        handler.post(poll)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(ticker)
        permRequested = false
        stop()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(permReceiver)
        super.onDestroy()
    }

    private fun findDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }

    private fun connectOrRequest() {
        val dev = findDevice()
        if (dev == null) {
            permRequested = false
            val all = usb.deviceList.values.joinToString(", ") {
                "%04x:%04x".format(it.vendorId, it.productId)
            }
            setStatus("Камера не найдена. Видно USB-устройств: " +
                    (if (all.isEmpty()) "ни одного" else all))
            return
        }
        if (usb.hasPermission(dev)) {
            start()
        } else if (!permRequested) {
            permRequested = true
            setStatus("Камера найдена, жду разрешение USB...")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERM).setPackage(packageName), flags
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun start() {
        if (running) return
        val dev = findDevice() ?: return
        running = true
        free.clear()
        ready.clear()
        repeat(4) { free.offer(ByteArray(ThermalDecoder.FRAME_BYTES)) }
        rxFrames.set(0)
        rxBytes.set(0)
        relocks.set(0)
        rejected.set(0)
        recoveries.set(0)
        shown.set(0)
        lastErr = ""
        lastReject = ""
        readerMode = "старт"
        stalled = false
        dumps = 0
        readerThread = thread(name = "usb-reader") { readerLoop(dev) }
        workerThread = thread(name = "decoder") { workerLoop() }
    }

    private fun stop() {
        running = false
        readerThread?.join(2000)
        workerThread?.join(1500)
        readerThread = null
        workerThread = null
    }

    private fun setStatus(s: String) = runOnUiThread { msgView.text = s }

    private fun obtain(): ByteArray =
        free.poll() ?: ready.poll() ?: ByteArray(ThermalDecoder.FRAME_BYTES)

    private fun publish(f: ByteArray) {
        if (!ready.offer(f)) {
            val old = ready.poll()
            if (old != null) free.offer(old)
            ready.offer(f)
        }
    }

    // ------------------------------------------------------------------- USB

    private inner class Assembler {
        private var cur = obtain()
        private var curLen = 0

        fun reset() { curLen = 0 }

        private fun advance(take: Int) {
            curLen += take
            if (curLen == ThermalDecoder.FRAME_BYTES) {
                publish(cur)
                rxFrames.incrementAndGet()
                cur = obtain()
                curLen = 0
            }
        }

        fun feed(b: ByteBuffer, n: Int) {
            var off = 0
            while (off < n) {
                val take = minOf(n - off, ThermalDecoder.FRAME_BYTES - curLen)
                b.get(cur, curLen, take)
                off += take
                advance(take)
            }
        }

        fun feed(b: ByteArray, start: Int, n: Int) {
            var off = 0
            while (off < n) {
                val take = minOf(n - off, ThermalDecoder.FRAME_BYTES - curLen)
                System.arraycopy(b, start + off, cur, curLen, take)
                off += take
                advance(take)
            }
        }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun readerLoop(first: UsbDevice) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var dev: UsbDevice? = first
        while (running) {
            if (dev == null) {
                lastErr = "камера не видна, жду..."
                sleepMs(1000)
                dev = findDevice()
                continue
            }
            stalled = false
            try {
                readSession(dev)
            } catch (t: Throwable) {
                lastErr = "reader: $t"
            }
            if (!running) break
            recoveries.incrementAndGet()
            readerMode = "переподключение..."
            sleepMs(800)
            dev = findDevice()
        }
    }

    private fun readSession(dev: UsbDevice) {
        val conn = usb.openDevice(dev)
        if (conn == null) {
            lastErr = "openDevice вернул null (нет разрешения?)"
            return
        }
        var iface: UsbInterface? = null
        var ep: UsbEndpoint? = null
        loop@ for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            for (j in 0 until itf.endpointCount) {
                val e = itf.getEndpoint(j)
                if (e.address == EP_IN && e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    iface = itf; ep = e
                    break@loop
                }
            }
        }
        if (iface == null || ep == null) {
            lastErr = "Endpoint 0x81 не найден"
            conn.close()
            return
        }
        val endpoint: UsbEndpoint = ep
        val usbInterface: UsbInterface = iface
        if (!conn.claimInterface(usbInterface, true)) {
            lastErr = "claimInterface не удался"
            conn.close()
            return
        }
        try { conn.setInterface(usbInterface) } catch (_: Throwable) {}
        try { conn.controlTransfer(0x02, 0x01, 0, EP_IN, null, 0, 1000) } catch (_: Throwable) {}

        val asm = Assembler()
        relockRequest = true
        readerMode = "async ${REQ_SIZE / 1024}K"
        val ok = try {
            runAsync(conn, endpoint, asm)
        } catch (t: Throwable) {
            lastErr = "async: $t"
            false
        }
        if (running && !ok && !stalled) {
            readerMode = "sync ${REQ_SIZE / 1024}K"
            asm.reset()
            relockRequest = true
            runSync(conn, endpoint, asm)
        }
        conn.releaseInterface(usbInterface)
        conn.close()
    }

    private fun runAsync(conn: UsbDeviceConnection, ep: UsbEndpoint, asm: Assembler): Boolean {
        val bufs = Array(REQ_COUNT) { ByteBuffer.allocateDirect(REQ_SIZE) }
        val reqs = ArrayList<UsbRequest>()
        try {
            for (i in 0 until REQ_COUNT) {
                val r = UsbRequest()
                if (!r.initialize(conn, ep)) { lastErr = "UsbRequest.initialize не удался"; return false }
                r.clientData = i
                reqs.add(r)
                bufs[i].clear()
                if (!r.queue(bufs[i])) { lastErr = "queue() не удался при старте"; return false }
            }
            val t0 = System.currentTimeMillis()
            var gotAny = false
            var lastDataMs = t0
            while (running) {
                val now = System.currentTimeMillis()
                if (gotAny && now - lastDataMs > STALL_MS) {
                    stalled = true
                    lastErr = "камера молчит ${STALL_MS / 1000} с (одни пустые пакеты)"
                    return false
                }
                val done: UsbRequest? = try {
                    conn.requestWait(200)
                } catch (e: TimeoutException) {
                    null
                }
                if (done == null) {
                    if (!gotAny && now - t0 > 2500) {
                        lastErr = "async: за 2.5 с ни один запрос не завершился"
                        return false
                    }
                    continue
                }
                gotAny = true
                val b = bufs[done.clientData as Int]
                var n = b.position()
                if (n < 0) n = 0
                if (n > REQ_SIZE) n = REQ_SIZE
                if (n > 0) {
                    lastDataMs = now
                    rxBytes.addAndGet(n.toLong())
                    b.position(0)
                    b.limit(n)
                    asm.feed(b, n)
                }
                b.clear()
                if (!done.queue(b)) { lastErr = "queue() не удался в цикле"; return false }
            }
            return true
        } finally {
            for (r in reqs) {
                try { r.cancel() } catch (_: Exception) {}
                try { r.close() } catch (_: Exception) {}
            }
        }
    }

    private fun runSync(conn: UsbDeviceConnection, ep: UsbEndpoint, asm: Assembler) {
        val chunk = ByteArray(REQ_SIZE)
        var lastDataMs = System.currentTimeMillis()
        while (running) {
            if (System.currentTimeMillis() - lastDataMs > STALL_MS) {
                stalled = true
                lastErr = "камера молчит ${STALL_MS / 1000} с (sync)"
                return
            }
            val n = conn.bulkTransfer(ep, chunk, chunk.size, 100)
            if (n <= 0) continue
            lastDataMs = System.currentTimeMillis()
            rxBytes.addAndGet(n.toLong())
            asm.feed(chunk, 0, n)
        }
    }

    // --------------------------------------------------------------- обработка

    private fun workerLoop() {
        try {
            workerBody()
        } catch (t: Throwable) {
            lastErr = "worker: $t"
        }
    }

    private val retLine = Paint().apply { strokeWidth = 2f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val retEdge = Paint().apply { strokeWidth = 5f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val retDot = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val retDotEdge = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val whiteLine = Paint().apply {
        color = Color.WHITE; strokeWidth = 2f; isAntiAlias = true; style = Paint.Style.STROKE
    }
    private val blackLine = Paint().apply {
        color = Color.BLACK; strokeWidth = 5f; isAntiAlias = true; style = Paint.Style.STROKE
    }
    private val textFill = Paint().apply {
        color = Color.WHITE; textSize = 22f; isAntiAlias = true; style = Paint.Style.FILL
    }
    private val textOutline = Paint().apply {
        color = Color.BLACK; textSize = 22f; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 4f
    }

    private fun outlined(c: Canvas, s: String, x: Float, y: Float) {
        c.drawText(s, x, y, textOutline)
        c.drawText(s, x, y, textFill)
    }

    /** Маленький белый крестик (горячая/холодная точка). */
    private fun cross(c: Canvas, cx: Float, cy: Float, gap: Float, arm: Float) {
        for (p in arrayOf(blackLine, whiteLine)) {
            c.drawLine(cx - arm, cy, cx - gap, cy, p)
            c.drawLine(cx + gap, cy, cx + arm, cy, p)
            c.drawLine(cx, cy - arm, cx, cy - gap, p)
            c.drawLine(cx, cy + gap, cx, cy + arm, p)
        }
    }

    /** Прицельная сетка выбранной формы и цвета с контрастной обводкой. */
    private fun drawReticle(c: Canvas, x: Float, y: Float, shape: Int, color: Int) {
        val edge = if (color == Color.BLACK) Color.WHITE else Color.BLACK
        retLine.color = color; retDot.color = color
        retEdge.color = edge; retDotEdge.color = edge
        for (p in arrayOf(retEdge, retLine)) {
            val dotPaint = if (p === retEdge) retDotEdge else retDot
            val grow = if (p === retEdge) 1.5f else 0f
            fun arms(gap: Float, arm: Float) {
                c.drawLine(x - arm, y, x - gap, y, p)
                c.drawLine(x + gap, y, x + arm, y, p)
                c.drawLine(x, y - arm, x, y - gap, p)
                c.drawLine(x, y + gap, x, y + arm, p)
            }
            when (shape) {
                1 -> arms(6f, 30f)
                2 -> { arms(9f, 40f); c.drawCircle(x, y, 2f + grow, dotPaint) }
                3 -> c.drawCircle(x, y, 3f + grow, dotPaint)
                4 -> { c.drawCircle(x, y, 22f, p); c.drawCircle(x, y, 1.6f + grow, dotPaint) }
                5 -> {
                    // дальномерная: длинные нити с рисками через 20 пикселей
                    arms(6f, 160f)
                    var d = 20f
                    while (d <= 160f) {
                        val t = if (d % 100f == 0f) 8f else 4f
                        c.drawLine(x - d, y - t, x - d, y + t, p)
                        c.drawLine(x + d, y - t, x + d, y + t, p)
                        c.drawLine(x - t, y + d, x + t, y + d, p)
                        d += 20f
                    }
                }
            }
        }
    }

    /**
     * Сетка, значение и горячая/холодная точки поверх готового кадра. Координаты кадра (до зума)
     * переводятся в экранные: зум z вокруг точки (cropX, cropY).
     */
    private fun drawOverlay(c: Canvas, z: Float, cropX: Int, cropY: Int,
                            coldPos: Int, hotPos: Int, coldLvl: Int, hotLvl: Int, aimLvl: Int) {
        val w = ThermalDecoder.WIDTH
        val h = ThermalDecoder.OUT_H
        fun sx(px: Float) = (px - cropX) * z + w / 2f
        fun sy(py: Float) = (py - cropY) * z + h / 2f
        val ax = sx(w / 2f + aimX)
        val ay = sy(h / 2f + aimY)
        val shape = if (retShape == 0 && zeroing) 1 else retShape
        drawReticle(c, ax, ay, shape, COLOR_VALUES[retColor])
        if (labels >= 1) outlined(c, "L $aimLvl", minOf(ax + 38f, w - 80f), maxOf(ay - 8f, 22f))
        if (labels >= 2) {
            for ((pos, tag, lvl, below) in listOf(
                    Quad(hotPos, "H", hotLvl, false), Quad(coldPos, "C", coldLvl, true))) {
                val x = sx((pos % w).toFloat())
                val y = sy((pos / w).toFloat())
                if (x < 0 || x >= w || y < 0 || y >= h) continue     // точка за краем увеличенного кадра
                cross(c, x, y, 3f, 10f)
                val ty = if (below) minOf(y + 20f, h - 6f) else maxOf(y - 6f, 22f)
                outlined(c, "$tag $lvl", minOf(x + 12f, w - 80f), ty)
            }
        }
    }

    private data class Quad(val pos: Int, val tag: String, val lvl: Int, val below: Boolean)

    private fun workerBody() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var hint: Any? = null
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val pm = getSystemService(Context.PERFORMANCE_HINT_SERVICE) as PerformanceHintManager
                hint = pm.createHintSession(intArrayOf(Process.myTid()), 33_000_000L)
            } catch (_: Throwable) {}
        }

        val nativeOk = NativeRenderer.available
        val y = if (nativeOk) null else ByteArray(ThermalRenderer.N)
        val argb = if (nativeOk) null else IntArray(ThermalRenderer.N)
        val renderer = if (nativeOk) null else ThermalRenderer()
        val canvases = arrayOf(Canvas(bitmaps[0]), Canvas(bitmaps[1]))
        val recorder = VideoRecorder(this)
        try {
            processLoop(nativeOk, y, argb, renderer, canvases, recorder, hint)
        } finally {
            if (recorder.active) finishRecording(recorder)
        }
    }

    private fun finishRecording(recorder: VideoRecorder) {
        val name = recorder.fileName
        val ok = recorder.stop()
        recording = false
        runOnUiThread { refreshButtons() }
        toast(if (ok) "Видео сохранено: Movies/MarsThermal/$name" else "Видео не записалось")
    }

    private fun processLoop(nativeOk: Boolean, y: ByteArray?, argb: IntArray?, renderer: ThermalRenderer?,
                            canvases: Array<Canvas>, recorder: VideoRecorder, hint: Any?) {
        var shift = -1
        var badInRow = 0
        var cur = 0
        var fpsCount = 0
        var tWin = System.nanoTime()
        var fpsText = ""

        while (running) {
            when (recRequest) {
                1 -> {
                    recRequest = 0
                    if (recorder.start()) {
                        recording = true
                        recStartMs = System.currentTimeMillis()
                    } else {
                        toast("Запись не началась: ${recorder.lastError}")
                    }
                    runOnUiThread { refreshButtons() }
                }
                2 -> {
                    recRequest = 0
                    finishRecording(recorder)
                }
            }
            if (relockRequest) {
                shift = -1
                badInRow = 0
                relockRequest = false
                if (nativeOk) NativeRenderer.resetNative()
                renderer?.reset()
            }
            val f = ready.poll(200, TimeUnit.MILLISECONDS) ?: continue
            try {
                val t0 = System.nanoTime()
                if (shift < 0) {
                    val s = ThermalDecoder.findShift(f)
                    if (s >= 0 && ThermalDecoder.frameOk(f, s)) {
                        shift = s
                        badInRow = 0
                        renderer?.reset()
                        if (nativeOk) NativeRenderer.resetNative()
                        if (showInfo && dumps == 0) { dumps = 1; dumpFrame("good_shift$s.bin", f) }
                    } else {
                        continue
                    }
                } else if (!ThermalDecoder.frameOk(f, shift)) {
                    rejected.incrementAndGet()
                    lastReject = ThermalDecoder.diagnose(f, shift)
                    if (showInfo && dumps in 1..3) {
                        dumps++
                        dumpFrame("bad${dumps - 1}_shift$shift.bin", f)
                    }
                    if (++badInRow >= 2) {
                        shift = -1
                        badInRow = 0
                        relocks.incrementAndGet()
                    }
                    continue
                } else {
                    badInRow = 0
                }

                val bmp = bitmaps[cur]
                val cv = canvases[cur]
                cur = 1 - cur
                val t1: Long
                val t2: Long
                // зум вокруг точки прицеливания; окно не выходит за край кадра (Kotlin-рендер без зума)
                val w = ThermalDecoder.WIDTH
                val h = ThermalDecoder.OUT_H
                val z = if (nativeOk) zoom else 1f
                val aimPx = w / 2 + aimX
                val aimPy = h / 2 + aimY
                var cropX = w / 2
                var cropY = h / 2
                if (z > 1.01f) {
                    val hw = (w / 2f / z).toInt()
                    val hh = (h / 2f / z).toInt()
                    cropX = aimPx.coerceIn(hw, w - hw)
                    cropY = aimPy.coerceIn(hh, h - hh)
                }
                if (nativeOk) {
                    NativeRenderer.renderNative(f, shift, tnr, contrast, sharp, destripe,
                        (z * 256).toInt(), cropX, cropY, aimPx, aimPy, palette, bmp, nativeStats)
                    rendererMinPos = nativeStats[0]
                    rendererMaxPos = nativeStats[1]
                    rendererMinLevel = nativeStats[2]
                    rendererMaxLevel = nativeStats[3]
                    rendererCenterLevel = nativeStats[4]
                    t1 = System.nanoTime()
                    t2 = System.nanoTime()
                    drawOverlay(cv, z, cropX, cropY, nativeStats[0], nativeStats[1], nativeStats[2],
                        nativeStats[3], nativeStats[4])
                } else {
                    ThermalDecoder.decodeY(f, shift, y!!)
                    t1 = System.nanoTime()
                    renderer!!.render(y, tnr, contrast, palette, argb!!)
                    t2 = System.nanoTime()
                    bmp.setPixels(argb, 0, ThermalDecoder.WIDTH, 0, 0, ThermalDecoder.WIDTH, ThermalDecoder.OUT_H)
                    drawOverlay(cv, 1f, cropX, cropY, renderer.minPos, renderer.maxPos, renderer.minLevel,
                        renderer.maxLevel, renderer.centerLevel)
                }
                if (recording) {
                    try {
                        recorder.frame(bmp)
                    } catch (t: Throwable) {
                        lastErr = "запись: $t"
                        finishRecording(recorder)
                    }
                }
                lastBmp = bmp
                val t3 = System.nanoTime()

                tDec = tDec * 0.9 + (t1 - t0) / 1e6 * 0.1
                tRen = tRen * 0.9 + (t2 - t1) / 1e6 * 0.1
                tUi = tUi * 0.9 + (t3 - t2) / 1e6 * 0.1
                if (hint != null && Build.VERSION.SDK_INT >= 31) {
                    try { (hint as PerformanceHintManager.Session).reportActualWorkDuration(t3 - t0) } catch (_: Throwable) {}
                }

                shown.incrementAndGet()
                fpsCount++
                val now = System.nanoTime()
                if (now - tWin >= 1_000_000_000L) {
                    fpsText = "$fpsCount FPS"
                    fpsCount = 0
                    tWin = now
                }
                val text = if (recording) {
                    val sec = (System.currentTimeMillis() - recStartMs) / 1000
                    "$fpsText  ● REC %d:%02d".format(sec / 60, sec % 60)
                } else fpsText
                runOnUiThread {
                    image.setImageBitmap(bmp)
                    if (text.isNotEmpty()) msgView.text = text
                }
            } finally {
                free.offer(f)
            }
        }
    }
}
