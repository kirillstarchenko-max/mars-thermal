package com.example.marsthermal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val VID = 0x04b4
        const val PID = 0xf7f7
        const val EP_IN = 0x81
        const val ACTION_PERM = "com.example.marsthermal.USB_PERMISSION"
        const val CONTRAST_PCT = 1.0
        const val EQUALIZE = true
        const val REQ_SIZE = 16384          // размер одного USB-запроса
        const val REQ_COUNT = 64            // сколько запросов одновременно в очереди (~1 МБ)
    }

    private lateinit var usb: UsbManager
    private lateinit var image: ImageView
    private lateinit var status: TextView

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

    // пул кадровых буферов: reader берёт из free, отдаёт в ready; worker возвращает в free
    private val free = ArrayBlockingQueue<ByteArray>(8)
    private val ready = ArrayBlockingQueue<ByteArray>(2)
    private val rxFrames = AtomicInteger(0)
    private val relocks = AtomicInteger(0)

    private val bitmaps = arrayOf(
        Bitmap.createBitmap(ThermalDecoder.WIDTH, ThermalDecoder.OUT_H, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(ThermalDecoder.WIDTH, ThermalDecoder.OUT_H, Bitmap.Config.ARGB_8888)
    )

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_PERM) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) start() else setStatus("Нет разрешения на USB")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 14f
            text = "Ищу камеру..."
        }
        val root = FrameLayout(this)
        root.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { topMargin = 96 })
        setContentView(root)

        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(ACTION_PERM), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
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
        relocks.set(0)
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

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun obtain(): ByteArray =
        free.poll() ?: ready.poll() ?: ByteArray(ThermalDecoder.FRAME_BYTES)

    private fun publish(f: ByteArray) {
        if (!ready.offer(f)) {
            val old = ready.poll()
            if (old != null) free.offer(old)
            ready.offer(f)
        }
    }

    /**
     * Чтение через асинхронную очередь UsbRequest: всегда висит REQ_COUNT запросов,
     * поэтому у камеры нет пауз между запросами и данные не теряются.
     */
    private fun readerLoop(dev: UsbDevice) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        val conn = usb.openDevice(dev)
        if (conn == null) {
            setStatus("openDevice вернул null (нет разрешения?)")
            running = false
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
            setStatus("Endpoint 0x81 не найден")
            conn.close(); running = false
            return
        }
        val endpoint: UsbEndpoint = ep
        val usbInterface: UsbInterface = iface
        if (!conn.claimInterface(usbInterface, true)) {
            setStatus("claimInterface не удался")
            conn.close(); running = false
            return
        }

        val bufs = Array(REQ_COUNT) { ByteBuffer.allocateDirect(REQ_SIZE) }
        val reqs = ArrayList<UsbRequest>()
        var setupOk = true
        for (i in 0 until REQ_COUNT) {
            val r = UsbRequest()
            if (!r.initialize(conn, endpoint)) { setupOk = false; break }
            r.clientData = i
            bufs[i].clear()
            if (!r.queue(bufs[i])) { setupOk = false; break }
            reqs.add(r)
        }
        if (!setupOk) setStatus("Не удалось поставить USB-запросы в очередь")

        var cur = obtain()
        var curLen = 0
        var fails = 0
        while (running && setupOk) {
            val done: UsbRequest? = try {
                conn.requestWait(200)
            } catch (e: TimeoutException) {
                null
            }
            if (done == null) {
                if (++fails > 15) { setStatus("Нет данных от камеры"); fails = 0 }
                continue
            }
            fails = 0
            val b = bufs[done.clientData as Int]
            var n = b.position()
            if (n <= 0 || n > REQ_SIZE) n = REQ_SIZE
            b.position(0)
            b.limit(n)

            var off = 0
            while (off < n) {
                val take = minOf(n - off, ThermalDecoder.FRAME_BYTES - curLen)
                b.get(cur, curLen, take)
                curLen += take
                off += take
                if (curLen == ThermalDecoder.FRAME_BYTES) {
                    publish(cur)
                    rxFrames.incrementAndGet()
                    cur = obtain()
                    curLen = 0
                }
            }
            b.clear()
            if (!done.queue(b)) {
                setStatus("queue() не удался")
                break
            }
        }

        for (r in reqs) {
            try { r.cancel() } catch (_: Exception) {}
            try { r.close() } catch (_: Exception) {}
        }
        conn.releaseInterface(usbInterface)
        conn.close()
    }

    private fun workerLoop() {
        val u16 = ShortArray(ThermalDecoder.VALUES)
        val img = ShortArray(ThermalDecoder.OUT_H * ThermalDecoder.WIDTH)
        val argb = IntArray(img.size)
        val renderer = ThermalRenderer()

        var shift = -1
        var pending = -1
        var cur = 0
        var fpsCount = 0
        var rxPrev = 0
        var tWin = System.nanoTime()
        var procAvg = 0.0
        var statText = ""

        while (running) {
            val f = ready.poll(200, TimeUnit.MILLISECONDS) ?: continue
            try {
                val t0 = System.nanoTime()
                ThermalDecoder.toU16(f, u16)

                if (shift < 0) {
                    // фаза принимается, когда два кадра подряд дали один и тот же сдвиг
                    val s = ThermalDecoder.findShift(u16)
                    if (s >= 0 && s == pending) {
                        shift = s
                        setStatus("Фаза: столбец ${s % ThermalDecoder.ROW}, строка ${s / ThermalDecoder.ROW}")
                    }
                    pending = s
                    continue
                }
                // проверяем каждый кадр (стоит доли миллисекунды): битый кадр не показываем
                if (ThermalDecoder.syncLost(u16, shift)) {
                    shift = -1
                    pending = -1
                    relocks.incrementAndGet()
                    continue
                }

                ThermalDecoder.decode(u16, shift, img)
                renderer.render(img, CONTRAST_PCT, EQUALIZE, argb)
                val bmp = bitmaps[cur]
                cur = 1 - cur
                bmp.setPixels(argb, 0, ThermalDecoder.WIDTH, 0, 0,
                    ThermalDecoder.WIDTH, ThermalDecoder.OUT_H)

                val ms = (System.nanoTime() - t0) / 1e6
                procAvg = if (procAvg == 0.0) ms else procAvg * 0.9 + ms * 0.1
                fpsCount++
                val now = System.nanoTime()
                if (now - tWin >= 1_000_000_000L) {
                    val rx = rxFrames.get()
                    statText = "FPS: $fpsCount | вход: ${rx - rxPrev}/с | " +
                            "срывов: ${relocks.get()} | ${"%.0f".format(procAvg)} мс/кадр"
                    rxPrev = rx
                    fpsCount = 0
                    tWin = now
                }
                val text = statText
                runOnUiThread {
                    image.setImageBitmap(bmp)
                    if (text.isNotEmpty()) status.text = text
                }
            } finally {
                free.offer(f)
            }
        }
    }
}
