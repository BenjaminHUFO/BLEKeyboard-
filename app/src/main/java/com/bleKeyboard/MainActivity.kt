package com.blekeyboard

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var ble        : BleManager
    private lateinit var statusText : TextView
    private lateinit var statusDot  : View
    private lateinit var valueText  : TextView
    private lateinit var tsText     : TextView
    private lateinit var btnConnect : Button
    private lateinit var accessWarn : View
    private lateinit var btnAccess  : Button

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    // ── Permissions ───────────────────────────────────────────────────────────
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startBle()
        else updateStatus("Permissions Bluetooth refusées", StatusLevel.ERROR)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot  = findViewById(R.id.statusDot)
        valueText  = findViewById(R.id.measureValue)
        tsText     = findViewById(R.id.measureTs)
        btnConnect = findViewById(R.id.btnConnect)
        accessWarn = findViewById(R.id.accessibilityWarning)
        btnAccess  = findViewById(R.id.btnAccessibility)

        ble = BleManager(this)

        ble.onStatus = { msg, level -> runOnUiThread { updateStatus(msg, level) } }
        ble.onMeasure = { value -> runOnUiThread { onMeasureReceived(value) } }

        btnConnect.setOnClickListener {
            if (ble.isConnected) ble.disconnect()
            else requestPermissionsAndConnect()
        }

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
    }

    // ── BLE ───────────────────────────────────────────────────────────────────
    private fun requestPermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))    needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (needed.isEmpty()) startBle()
        else permLauncher.launch(needed.toTypedArray())
    }

    private fun startBle() {
        // Vérifie que le Bluetooth est activé
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (adapter?.isEnabled == false) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        ble.connect()
    }

    // ── Mesure reçue → injection ──────────────────────────────────────────────
    private fun onMeasureReceived(value: String) {
        // Affichage
        valueText.text = value
        tsText.text    = "dernière mesure : ${timeFormat.format(Date())}"
        flashValue()

        // Injection dans le champ actif via le service d'accessibilité
        if (TextInjectionService.instance != null) {
            val intent = Intent(TextInjectionService.ACTION_INJECT_TEXT).apply {
                putExtra(TextInjectionService.EXTRA_TEXT, value)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private fun updateStatus(msg: String, level: StatusLevel) {
        statusText.text = msg
        statusText.setTextColor(
            getColor(when (level) {
                StatusLevel.CONNECTED  -> R.color.status_connected
                StatusLevel.CONNECTING -> R.color.status_connecting
                StatusLevel.ERROR      -> R.color.status_error
                StatusLevel.IDLE       -> R.color.status_idle
            })
        )
        statusDot.background = ContextCompat.getDrawable(this,
            if (level == StatusLevel.CONNECTED) R.drawable.dot_connected
            else R.drawable.dot_disconnected
        )
        btnConnect.text = if (level == StatusLevel.CONNECTED) "DÉCONNECTER" else "CONNECTER"
    }

    private fun flashValue() {
        ObjectAnimator.ofArgb(
            valueText, "textColor",
            0xFFFFFFFF.toInt(), 0xFF00E5A0.toInt()
        ).apply {
            duration = 400
            start()
        }
    }

    private fun checkAccessibilityService() {
        val enabled = TextInjectionService.instance != null
        accessWarn.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
