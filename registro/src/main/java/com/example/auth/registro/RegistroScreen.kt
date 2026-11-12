package com.example.auth.registro

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val DEVICE_NAME = "ESP32_Temp"

private fun hasBlePermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

private fun workbookFile(context: Context): File {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
    return File(dir, "mediciones.xlsx")
}

@SuppressLint("MissingPermission")
@Composable
fun RegistroScreen(onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val bluetoothAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    val file = remember { workbookFile(context) }

    var status by remember { mutableStateOf("Desconectado") }
    var current by remember { mutableStateOf("--") }
    var isScanning by remember { mutableStateOf(false) }
    var gatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    val readings = remember { mutableStateListOf<Measurement>() }
    val acceptScan = remember { AtomicBoolean(false) }

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun store(raw: String) {
        val value = raw.trim().trim('\u0000')
        if (value.isEmpty()) return
        val now = Date()
        val measurement = Measurement(
            date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now),
            time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now),
            value = value,
        )
        onMain {
            current = value
            readings.add(0, measurement)
            MeasurementWorkbook.write(file, readings.asReversed())
            status = "Conectado a ESP32 · guardado"
        }
    }

    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onMain { status = "Conectado, descubriendo servicios..." }
                    if (hasBlePermissions(context)) {
                        g.discoverServices()
                    } else {
                        onMain { status = "Permiso Bluetooth denegado" }
                    }
                } else {
                    onMain {
                        status = "Desconectado"
                        current = "--"
                        gatt = null
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                if (characteristic == null) {
                    onMain { status = "Servicio no encontrado" }
                    return
                }
                if (!hasBlePermissions(context)) {
                    onMain { status = "Permiso Bluetooth denegado" }
                    return
                }
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    onMain { status = "No se pudieron activar las notificaciones" }
                    return
                }
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                onMain { status = "Conectado a ESP32" }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == CHAR_UUID) {
                    store(String(value, Charsets.UTF_8))
                }
            }

            @Deprecated("Para dispositivos que aún llaman la API anterior")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == CHAR_UUID) {
                    @Suppress("DEPRECATION")
                    store(characteristic.getStringValue(0) ?: return)
                }
            }
        }
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (name != DEVICE_NAME || !acceptScan.compareAndSet(true, false)) return
                try {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                } catch (_: SecurityException) {
                    return
                }
                onMain {
                    isScanning = false
                    status = "Dispositivo encontrado, conectando..."
                    gatt = result.device.connectGatt(context, false, gattCallback)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onMain {
                    status = "Error de escaneo: $errorCode"
                    isScanning = false
                }
            }
        }
    }

    fun stopScan() {
        if (!isScanning || !hasBlePermissions(context)) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        isScanning = false
    }

    fun closeGatt() {
        val connection = gatt ?: return
        try {
            if (hasBlePermissions(context)) {
                connection.disconnect()
                connection.close()
            }
        } catch (_: SecurityException) {
        }
        gatt = null
    }

    LaunchedEffect(file) {
        readings.clear()
        readings.addAll(MeasurementWorkbook.read(file).asReversed())
    }

    DisposableEffect(Unit) {
        onDispose {
            stopScan()
            closeGatt()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("Registro ESP32", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Estado: $status", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = current,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (!hasBlePermissions(context)) {
                    status = "Concede los permisos de Bluetooth"
                    onRequestPermissions()
                    return@Button
                }
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                    status = "Activa el Bluetooth del teléfono"
                    return@Button
                }
                try {
                    if (gatt != null) {
                        closeGatt()
                        status = "Desconectado"
                        current = "--"
                    } else if (isScanning) {
                        acceptScan.set(false)
                        stopScan()
                        status = "Búsqueda cancelada"
                    } else {
                        acceptScan.set(true)
                        isScanning = true
                        status = "Buscando ESP32..."
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build()
                        bluetoothAdapter.bluetoothLeScanner.startScan(null, settings, scanCallback)
                    }
                } catch (e: SecurityException) {
                    status = "Error de seguridad: ${e.message}"
                    isScanning = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    gatt != null -> "Desconectar"
                    isScanning -> "Cancelar búsqueda"
                    else -> "Conectar ESP32"
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { shareWorkbook(context, file) },
            enabled = readings.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Compartir Excel")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Cada medición se guarda en mediciones.xlsx",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (readings.isNotEmpty()) {
            Text("Mediciones", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(readings) { record ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(record.value, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${record.date} ${record.time}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun shareWorkbook(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Abrir mediciones.xlsx"))
}
