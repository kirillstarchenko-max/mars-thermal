package com.example.marsthermal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import android.view.View
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
        private val RETICLE_NAMES = arrayOf("выкл", "перекрестие", "+ значение", "+ горячая/холодная")
        private val SHARP_NAMES = arrayOf("выкл", "слабая", "средняя", "сильная")
        private val VIEW_NAMES = arrayOf("вписать", "на весь экран", "растянуть")
        private val VIEW_SCALE = arrayOf(
            ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.CENTER_CROP, ImageView.ScaleType.FIT_XY)
        const val CALIB_FRAMES = 32         // ~1 с усреднения
        const val CALIB_FILE = "calib.bin"
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
    private lateinit var btnReticle: Button
    private lateinit var btnSharp: Button
    private lateinit var btnView: Button
    private lateinit var btnCalib: Button
    private lateinit var btnInfo: Button

    // настройки отображения
    @Volatile private var palIdx = 0
    @Volatile private var invert = false
    @Volatile private var contrast = 1
    @Volatile private var tnr = 2
    @Volatile private var reticle = 1
    @Volatile private var showInfo = false
    @Volatile private var sharp = 1
    private var viewMode = 0
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

    // калибровка: UI ставит запрос, поток обработки выполняет (нативное состояние трогает только он)
    @Volatile private var calibRequest = 0     // 1 начать, 2 сбросить
    @Volatile private var calibLeft = 0
    @Volatile private var haveCalib = false

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
            refreshCalibButton()
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
        contrast = prefs.getInt("contrast", 1)
        tnr = prefs.getInt("tnr", 2)
        reticle = prefs.getInt("reticle", 1)
        showInfo = prefs.getBoolean("info", false)
        sharp = prefs.getInt("sharp", 1)
        viewMode = prefs.getInt("view", 0)
        haveCalib = calibFile().exists()
        palette = Palettes.get(palIdx, invert)

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
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x66000000)
        }
        btnPal = makeButton({ cyclePalette(1) }, { invert = !invert; applySettings() })
        btnContrast = makeButton({ contrast = (contrast + 1) % CONTRAST_NAMES.size; applySettings() })
        btnNoise = makeButton({ tnr = (tnr + 1) % 5; applySettings() })
        btnSharp = makeButton({ sharp = (sharp + 1) % SHARP_NAMES.size; applySettings() })
        btnReticle = makeButton({ reticle = (reticle + 1) % RETICLE_NAMES.size; applySettings() })
        btnView = makeButton({ viewMode = (viewMode + 1) % VIEW_NAMES.size; applySettings() })
        btnCalib = makeButton({ startCalibration() }, { clearCalibration() })
        val btnSnap = makeButton({ takeSnapshot() })
        btnSnap.text = "Снимок"
        btnInfo = makeButton({ showInfo = !showInfo; dumps = 0; applySettings() })
        for (b in listOf(btnPal, btnContrast, btnNoise, btnSharp, btnReticle, btnView, btnCalib, btnSnap, btnInfo)) {
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun applySettings() {
        palette = Palettes.get(palIdx, invert)
        prefs.edit()
            .putInt("pal", palIdx).putBoolean("invert", invert).putInt("contrast", contrast)
            .putInt("tnr", tnr).putInt("reticle", reticle).putBoolean("info", showInfo)
            .putInt("sharp", sharp).putInt("view", viewMode)
            .apply()
        refreshButtons()
    }

    private fun refreshButtons() {
        btnPal.text = "Палитра: ${Palettes.NAMES[palIdx]}${if (invert) " (инв.)" else ""}"
        btnContrast.text = "Контраст: ${CONTRAST_NAMES[contrast]}"
        btnNoise.text = "Шумоподавл.: ${if (tnr == 0) "выкл" else tnr.toString()}"
        btnSharp.text = "Резкость: ${SHARP_NAMES[sharp]}"
        btnReticle.text = "Прицел: ${RETICLE_NAMES[reticle]}"
        btnView.text = "Вид: ${VIEW_NAMES[viewMode]}"
        btnInfo.text = if (showInfo) "Инфо: вкл" else "Инфо: выкл"
        refreshCalibButton()
        image.scaleType = VIEW_SCALE[viewMode]
    }

    private fun refreshCalibButton() {
        btnCalib.text = when {
            calibLeft > 0 -> "Калибровка..."
            haveCalib -> "Калибровка: есть"
            else -> "Калибровка: нет"
        }
    }

    private fun calibFile() = java.io.File(filesDir, CALIB_FILE)

    private fun startCalibration() {
        if (!NativeRenderer.available) { toast("Калибровка работает только с NDK-рендером"); return }
        calibRequest = 1
        calibLeft = CALIB_FRAMES
        refreshCalibButton()
        toast("Наведите камеру на ровную стену или закройте объектив, держите ~1 с")
    }

    private fun clearCalibration() {
        calibRequest = 2
        haveCalib = false
        calibFile().delete()
        refreshCalibButton()
        toast("Калибровка сброшена")
    }

    /** Поток обработки: сохранить поправку после калибровки. */
    private fun saveCalibration() {
        val data = ShortArray(ThermalRenderer.N)
        if (!NativeRenderer.calibGet(data)) return
        try {
            val bb = ByteBuffer.allocate(data.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(data)
            calibFile().writeBytes(bb.array())
            haveCalib = true
            toast("Калибровка сохранена")
        } catch (t: Throwable) {
            toast("Не удалось сохранить калибровку: $t")
        }
        runOnUiThread { refreshCalibButton() }
    }

    /** Поток обработки: загрузить сохранённую поправку в NDK-рендер. */
    private fun loadCalibration() {
        val f = calibFile()
        if (!f.exists()) return
        try {
            val bytes = f.readBytes()
            if (bytes.size != ThermalRenderer.N * 2) { f.delete(); haveCalib = false; return }
            val data = ShortArray(ThermalRenderer.N)
            ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
            NativeRenderer.calibSet(data)
            haveCalib = true
        } catch (_: Throwable) {}
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

    private fun cross(c: Canvas, cx: Float, cy: Float, gap: Float, arm: Float) {
        for (p in arrayOf(blackLine, whiteLine)) {
            c.drawLine(cx - arm, cy, cx - gap, cy, p)
            c.drawLine(cx + gap, cy, cx + arm, cy, p)
            c.drawLine(cx, cy - arm, cx, cy - gap, p)
            c.drawLine(cx, cy + gap, cx, cy + arm, p)
        }
    }

    private fun drawOverlay(c: Canvas, mode: Int, r: ThermalRenderer) {
        if (mode <= 0) return
        val w = ThermalDecoder.WIDTH
        val cx = w / 2f
        val cy = ThermalDecoder.OUT_H / 2f
        cross(c, cx, cy, 6f, 30f)
        if (mode >= 2) outlined(c, "L ${r.centerLevel}", cx + 38f, cy - 8f)
        if (mode >= 3) {
            val hx = (r.maxPos % w).toFloat()
            val hy = (r.maxPos / w).toFloat()
            cross(c, hx, hy, 3f, 10f)
            outlined(c, "H ${r.maxLevel}", minOf(hx + 12f, w - 80f), maxOf(hy - 6f, 22f))
            val lx = (r.minPos % w).toFloat()
            val ly = (r.minPos / w).toFloat()
            cross(c, lx, ly, 3f, 10f)
            outlined(c, "C ${r.minLevel}", minOf(lx + 12f, w - 80f), minOf(ly + 20f, ThermalDecoder.OUT_H - 6f))
        }
    }

    private fun drawOverlayNative(c: Canvas, mode: Int, stats: IntArray) {
        if (mode <= 0) return
        val w = ThermalDecoder.WIDTH
        val cx = w / 2f
        val cy = ThermalDecoder.OUT_H / 2f
        cross(c, cx, cy, 6f, 30f)
        if (mode >= 2) outlined(c, "L ${stats[4]}", cx + 38f, cy - 8f)
        if (mode >= 3) {
            val hx = (stats[1] % w).toFloat()
            val hy = (stats[1] / w).toFloat()
            cross(c, hx, hy, 3f, 10f)
            outlined(c, "H ${stats[3]}", minOf(hx + 12f, w - 80f), maxOf(hy - 6f, 22f))
            val lx = (stats[0] % w).toFloat()
            val ly = (stats[0] / w).toFloat()
            cross(c, lx, ly, 3f, 10f)
            outlined(c, "C ${stats[2]}", minOf(lx + 12f, w - 80f), minOf(ly + 20f, ThermalDecoder.OUT_H - 6f))
        }
    }

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
        if (nativeOk) loadCalibration()

        var shift = -1
        var badInRow = 0
        var cur = 0
        var fpsCount = 0
        var tWin = System.nanoTime()
        var fpsText = ""

        while (running) {
            if (relockRequest) {
                shift = -1
                badInRow = 0
                relockRequest = false
                if (nativeOk) NativeRenderer.resetNative()
                renderer?.reset()
            }
            val req = calibRequest
            if (nativeOk && req != 0) {
                calibRequest = 0
                if (req == 1) NativeRenderer.calibStart(CALIB_FRAMES) else NativeRenderer.calibClear()
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
                if (nativeOk) {
                    val wasCalib = calibLeft > 0
                    NativeRenderer.renderNative(f, shift, tnr, contrast, sharp, palette, bmp, nativeStats)
                    if (calibRequest == 0) {
                        calibLeft = nativeStats[5]
                        if (wasCalib && calibLeft == 0) saveCalibration()
                    }
                    rendererMinPos = nativeStats[0]
                    rendererMaxPos = nativeStats[1]
                    rendererMinLevel = nativeStats[2]
                    rendererMaxLevel = nativeStats[3]
                    rendererCenterLevel = nativeStats[4]
                    t1 = System.nanoTime()
                    t2 = System.nanoTime()
                    drawOverlayNative(cv, reticle, nativeStats)
                } else {
                    ThermalDecoder.decodeY(f, shift, y!!)
                    t1 = System.nanoTime()
                    renderer!!.render(y, tnr, contrast, palette, argb!!)
                    t2 = System.nanoTime()
                    bmp.setPixels(argb, 0, ThermalDecoder.WIDTH, 0, 0, ThermalDecoder.WIDTH, ThermalDecoder.OUT_H)
                    drawOverlay(cv, reticle, renderer)
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
                val text = fpsText
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
