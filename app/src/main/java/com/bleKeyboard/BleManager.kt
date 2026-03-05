package com.blekeyboard

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

typealias OnMeasure = (String) -> Unit
typealias OnStatus  = (String, StatusLevel) -> Unit

enum class StatusLevel { IDLE, CONNECTING, CONNECTED, ERROR }

class BleManager(private val context: Context) {

    companion object {
        const val DEVICE_NAME  = "ET030-237A"
        val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHAR_RX_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CHAR_TX_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    var onMeasure : OnMeasure? = null
    var onStatus  : OnStatus?  = null

    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager).adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt            : BluetoothGatt? = null
    private var scanning        = false
    private var userDisconnected = false
    private var reconnectRunnable: Runnable? = null

    // ── Connexion publique ────────────────────────────────────────────────────
    fun connect() {
        userDisconnected = false
        startScan()
    }

    fun disconnect() {
        userDisconnected = true
        cancelReconnect()
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onStatus?.invoke("Déconnecté", StatusLevel.IDLE)
    }

    val isConnected get() = gatt?.device != null

    // ── Scan ──────────────────────────────────────────────────────────────────
    private fun startScan() {
        if (scanning) return
        scanning = true
        onStatus?.invoke("Recherche du mètre…", StatusLevel.CONNECTING)

        val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)

        // Timeout scan 10s
        mainHandler.postDelayed({
            if (scanning) {
                stopScan()
                onStatus?.invoke("Mètre introuvable", StatusLevel.ERROR)
                scheduleReconnect()
            }
        }, 10_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            onStatus?.invoke("Connexion GATT…", StatusLevel.CONNECTING)
            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus?.invoke("Erreur scan : $errorCode", StatusLevel.ERROR)
        }
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    onStatus?.invoke("Découverte services…", StatusLevel.CONNECTING)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    gatt = null
                    mainHandler.post {
                        onStatus?.invoke("Déconnecté", StatusLevel.IDLE)
                        if (!userDisconnected) scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { onStatus?.invoke("Erreur services", StatusLevel.ERROR) }
                return
            }
            val service = g.getService(SERVICE_UUID) ?: run {
                mainHandler.post { onStatus?.invoke("Service FFF0 introuvable", StatusLevel.ERROR) }
                return
            }

            // Active les notifications sur RX
            val rxChar = service.getCharacteristic(CHAR_RX_UUID) ?: return
            g.setCharacteristicNotification(rxChar, true)
            rxChar.getDescriptor(CCCD_UUID)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Notifications activées → envoie commande init
            val txChar = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_TX_UUID)
            txChar?.let {
                it.value = byteArrayOf(0x01, 0x02, 0x03)
                g.writeCharacteristic(it)
            }
            mainHandler.post {
                onStatus?.invoke("En écoute · $DEVICE_NAME", StatusLevel.CONNECTED)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = characteristic.value.toString(Charsets.UTF_8)
            val idx = raw.indexOf('L')
            if (idx == -1) return

            // Extrait uniquement le nombre (ex: "L1523mm" → "1523")
            val full  = raw.substring(idx + 1).trim()          // "1523mm" ou "1523.5mm"
            val number = full.replace(Regex("[^0-9.]"), "")     // "1523" ou "1523.5"
            if (number.isEmpty()) return

            mainHandler.post { onMeasure?.invoke(number) }
        }
    }

    // ── Reconnexion automatique ───────────────────────────────────────────────
    private fun scheduleReconnect() {
        cancelReconnect()
        onStatus?.invoke("Reconnexion dans 5s…", StatusLevel.CONNECTING)
        reconnectRunnable = Runnable { if (!userDisconnected) startScan() }.also {
            mainHandler.postDelayed(it, 5_000)
        }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }
}
