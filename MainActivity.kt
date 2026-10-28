package com.example.gamepad

/*
 MANDO BLUETOOTH VIRTUAL (HID) - Samsung A71 (Android 9+)

 PASOS:
 1. Android Studio > New Project > "Empty Views Activity" (Kotlin), nombre de paquete com.example.gamepad, Minimum SDK 28.
 2. Reemplaza el contenido de MainActivity.kt por este archivo.
 3. En AndroidManifest.xml añade, dentro de <manifest> y antes de <application>:

    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    Y en la etiqueta <activity> de MainActivity: android:screenOrientation="landscape"
 4. Instala en el A71. En el otro dispositivo (donde corre GameHub): Ajustes > Bluetooth > busca "A71" y vincula.
    Si no aparece, pulsa "Visible" en la app. Luego pulsa "Conectar" y elige ese dispositivo.
 5. En GameHub, mapea los botones del mando detectado.
*/

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Capa Bluetooth HID
// Reporte de 9 bytes: [botones lo][botones hi][hat][X][Y][Z][Rz][Rx(L2)][Ry(R2)]
// ---------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class HidGamepad(private val ctx: Context, private val status: (String) -> Unit) {

    private val adapter: BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val exec = Executors.newSingleThreadExecutor()

    private val descriptor: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,             // Gamepad
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10,             // 16 botones
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39,                         // Hat (cruceta)
        0x15, 0x01, 0x25, 0x08, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,             // relleno
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, // X Y Z Rz
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x33, 0x09, 0x34,                         // Rx Ry (gatillos)
        0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0
    ).map { it.toByte() }.toByteArray()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            status(if (registered) "Listo: vincula o conecta" else "App HID no registrada")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                status("Conectado a ${device.name ?: device.address}")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) {
                host = null
                status("Desconectado")
            }
        }
    }

    fun start() {
        val ok = adapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Gamepad A71", "Mando virtual", "DIY",
                    BluetoothHidDevice.SUBCLASS1_COMBO, descriptor
                )
                h.registerApp(sdp, null, null, exec, callback)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE) ?: false
        if (!ok) status("Bluetooth no disponible")
    }

    fun bonded(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(d: BluetoothDevice) {
        hid?.connect(d)
    }

    fun send(report: ByteArray) {
        val h = host ?: return
        hid?.sendReport(h, 0, report)
    }
}

// ---------------------------------------------------------------------------
// Pantalla del mando
// ---------------------------------------------------------------------------
class GamepadView(ctx: Context, private val onReport: (ByteArray) -> Unit) : View(ctx) {

    // pos = (fx*ancho + ox*u, fy*alto + oy*u), radio = fr*u, u = min(ancho, alto)
    private class Btn(
        val name: String, val bit: Int,
        val fx: Float, val fy: Float, val ox: Float, val oy: Float, val fr: Float
    )

    private val btns = listOf(
        Btn("A", 0, .85f, .45f, 0f, .14f, .065f),
        Btn("B", 1, .85f, .45f, .14f, 0f, .065f),
        Btn("X", 2, .85f, .45f, -.14f, 0f, .065f),
        Btn("Y", 3, .85f, .45f, 0f, -.14f, .065f),
        Btn("L1", 4, .15f, .12f, 0f, 0f, .06f),
        Btn("R1", 5, .85f, .12f, 0f, 0f, .06f),
        Btn("SEL", 6, .44f, .15f, 0f, 0f, .045f),
        Btn("STA", 7, .56f, .15f, 0f, 0f, .045f),
        Btn("L2", 10, .06f, .12f, 0f, 0f, .06f),
        Btn("R2", 11, .94f, .12f, 0f, 0f, .06f),
        Btn("U", -1, .30f, .72f, 0f, -.14f, .065f),
        Btn("D", -1, .30f, .72f, 0f, .14f, .065f),
        Btn("L", -1, .30f, .72f, -.14f, 0f, .065f),
        Btn("R", -1, .30f, .72f, .14f, 0f, .065f)
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.argb(120, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var u = 1f
    private var sr = 1f
    private val lc = PointF()
    private val rc = PointF()
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var leftId = -1
    private var rightId = -1
    private var pressed: Set<String> = emptySet()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        u = min(w, h).toFloat()
        sr = .17f * u
        lc.set(.12f * w, .55f * h)
        rc.set(.68f * w, .72f * h)
        text.textSize = .04f * u
    }

    private fun pos(b: Btn) = PointF(b.fx * width + b.ox * u, b.fy * height + b.oy * u)

    private fun stick(x: Float, y: Float, c: PointF): PointF {
        var dx = (x - c.x) / sr
        var dy = (y - c.y) / sr
        val m = hypot(dx, dy)
        if (m > 1f) { dx /= m; dy /= m }
        return PointF(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val act = e.actionMasked
        val idx = e.actionIndex
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            val id = e.getPointerId(idx)
            val x = e.getX(idx)
            val y = e.getY(idx)
            if (leftId < 0 && hypot(x - lc.x, y - lc.y) < sr * 1.4f) leftId = id
            else if (rightId < 0 && hypot(x - rc.x, y - rc.y) < sr * 1.4f) rightId = id
        }
        val lifting = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP
        val cancel = act == MotionEvent.ACTION_CANCEL
        if (lifting) {
            val id = e.getPointerId(idx)
            if (id == leftId) leftId = -1
            if (id == rightId) rightId = -1
        }
        if (cancel) { leftId = -1; rightId = -1 }

        lx = 0f; ly = 0f; rx = 0f; ry = 0f
        val down = HashSet<String>()
        if (!cancel) {
            for (i in 0 until e.pointerCount) {
                if (lifting && i == idx) continue
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                when {
                    id == leftId -> { val v = stick(x, y, lc); lx = v.x; ly = v.y }
                    id == rightId -> { val v = stick(x, y, rc); rx = v.x; ry = v.y }
                    else -> btns.forEach { b ->
                        val p = pos(b)
                        if (hypot(x - p.x, y - p.y) <= b.fr * u * 1.25f) down.add(b.name)
                    }
                }
            }
        }
        pressed = down
        emit()
        invalidate()
        return true
    }

    private fun emit() {
        var mask = 0
        btns.forEach { if (it.bit >= 0 && it.name in pressed) mask = mask or (1 shl it.bit) }
        val up = "U" in pressed
        val dn = "D" in pressed
        val lf = "L" in pressed
        val rt = "R" in pressed
        val hat = when {
            up && rt -> 2
            rt && dn -> 4
            dn && lf -> 6
            lf && up -> 8
            up -> 1
            rt -> 3
            dn -> 5
            lf -> 7
            else -> 0
        }
        fun ax(v: Float) = (v * 127f).roundToInt().coerceIn(-127, 127).toByte()
        onReport(
            byteArrayOf(
                mask.toByte(), (mask shr 8).toByte(), hat.toByte(),
                ax(lx), ax(ly), ax(rx), ax(ry),
                (if ("L2" in pressed) 255 else 0).toByte(),
                (if ("R2" in pressed) 255 else 0).toByte()
            )
        )
    }

    override fun onDraw(c: Canvas) {
        btns.forEach { b ->
            val p = pos(b)
            val on = b.name in pressed
            fill.color = if (on) Color.argb(200, 255, 255, 255) else Color.argb(70, 255, 255, 255)
            c.drawCircle(p.x, p.y, b.fr * u, fill)
            text.color = if (on) Color.BLACK else Color.WHITE
            val label = when (b.name) { "U" -> "▲"; "D" -> "▼"; "L" -> "◀"; "R" -> "▶"; else -> b.name }
            c.drawText(label, p.x, p.y + text.textSize / 3f, text)
        }
        listOf(Triple(lc, lx, ly), Triple(rc, rx, ry)).forEach { (ctr, vx, vy) ->
            c.drawCircle(ctr.x, ctr.y, sr, line)
            fill.color = Color.argb(150, 255, 255, 255)
            c.drawCircle(ctr.x + vx * sr, ctr.y + vy * sr, sr * .45f, fill)
        }
    }
}

// ---------------------------------------------------------------------------
// Actividad
// ---------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var pad: HidGamepad
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        pad = HidGamepad(this) { s -> runOnUiThread { label.text = s } }
        val view = GamepadView(this) { pad.send(it) }

        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Iniciando…"
            setPadding(24, 8, 24, 8)
        }
        val visible = Button(this).apply {
            text = "Visible"
            setOnClickListener {
                startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                )
            }
        }
        val connect = Button(this).apply {
            text = "Conectar"
            setOnClickListener { pickDevice() }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(visible)
            addView(connect)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(17, 17, 17))
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1
            )
        } else {
            pad.start()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) pad.start()
        else label.text = "Faltan permisos de Bluetooth"
    }

    private fun pickDevice() {
        val list = pad.bonded()
        if (list.isEmpty()) {
            label.text = "Primero vincula desde el otro dispositivo"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Conectar a…")
            .setItems(list.map { it.name ?: it.address }.toTypedArray()) { _, i -> pad.connect(list[i]) }
            .show()
    }
}
