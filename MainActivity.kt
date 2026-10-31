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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val VID = 0x04b4
        const val PID = 0xf7f7
        const val EP_IN = 0x81
        const val ACTION_PERM = "com.example.marsthermal.USB_PERMISSION"
        const val CONTRAST_PCT = 1.0       // как ползунок Contrast в Python-версии
        const val EQUALIZE = true          // мягкая замена CLAHE
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
    private val frames = ArrayBlockingQueue<ByteArray>(2)
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
        frames.clear()
        readerThread = thread(name = "usb-reader") { readerLoop(dev) }
        workerThread = thread(name = "decoder") { workerLoop() }
    }

    private fun stop() {
        running = false
        readerThread?.join(1500)
        workerThread?.join(1500)
        readerThread = null
        workerThread = null
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun readerLoop(dev: UsbDevice) {
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

        val chunk = ByteArray(65536)
        repeat(5) { conn.bulkTransfer(endpoint, chunk, chunk.size, 20) }   // как в Python: сбрасываем хвост

        val acc = ByteArray(ThermalDecoder.FRAME_BYTES + chunk.size)
        var accLen = 0
        var fails = 0
        while (running) {
            val n = conn.bulkTransfer(endpoint, chunk, chunk.size, 100)
            if (n <= 0) {
                if (++fails > 50) { setStatus("Нет данных от камеры"); fails = 0 }
                continue
            }
            fails = 0
            System.arraycopy(chunk, 0, acc, accLen, n)
            accLen += n
            if (accLen >= ThermalDecoder.FRAME_BYTES) {
                val f = acc.copyOf(ThermalDecoder.FRAME_BYTES)
                System.arraycopy(acc, ThermalDecoder.FRAME_BYTES, acc, 0,
                    accLen - ThermalDecoder.FRAME_BYTES)
                accLen -= ThermalDecoder.FRAME_BYTES
                if (!frames.offer(f)) { frames.poll(); frames.offer(f) }   // роняем самый старый кадр
            }
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
        var count = 0
        var cur = 0
        var fpsCount = 0
        var fpsT = System.nanoTime()
        var fps = 0

        while (running) {
            val f = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
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
            if (++count % 30 == 0 && ThermalDecoder.syncLost(u16, shift)) {
                shift = -1; pending = -1
                setStatus("Синхронизация потеряна, ищу заново...")
                continue
            }

            ThermalDecoder.decode(u16, shift, img)
            renderer.render(img, CONTRAST_PCT, EQUALIZE, argb)
            val bmp = bitmaps[cur]
            cur = 1 - cur
            bmp.setPixels(argb, 0, ThermalDecoder.WIDTH, 0, 0, ThermalDecoder.WIDTH, ThermalDecoder.OUT_H)

            fpsCount++
            val now = System.nanoTime()
            if (now - fpsT >= 1_000_000_000L) {
                fps = fpsCount; fpsCount = 0; fpsT = now
            }
            val fpsNow = fps
            runOnUiThread {
                image.setImageBitmap(bmp)
                status.text = "FPS: $fpsNow"
            }
        }
    }
}
