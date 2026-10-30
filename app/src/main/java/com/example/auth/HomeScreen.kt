package com.example.auth

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
val CHAR_UUID: UUID    = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
val CCCD_UUID: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Verifica si un permiso está concedido
fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

// Verifica los permisos BLE según versión de Android
fun hasBlePermissions(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

fun saveGlucoseToFirestore(glucose: String) {
    val user = Firebase.auth.currentUser ?: return
    val db = Firebase.firestore

    val record = hashMapOf(
        "glucose" to glucose.toDoubleOrNull(),
        "timestamp" to com.google.firebase.Timestamp.now(),
        "formattedTime" to SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(Date())
    )

    // Estructura: users/{uid}/glucose/{autoId}
    db.collection("users")
        .document(user.uid)
        .collection("glucose")
        .add(record)
}
@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter

    var glucose by remember { mutableStateOf("--") }
    var status by remember { mutableStateOf("Desconectado") }
    var gatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var readings by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

    //cargar lecturas desde Firestore
    LaunchedEffect(Unit) {
        val user = Firebase.auth.currentUser ?: return@LaunchedEffect
        Firebase.firestore
            .collection("users")
            .document(user.uid)
            .collection("glucose")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(10)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    readings = snapshot.documents.mapNotNull { it.data }
                }
            }
    }

    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = "Conectado, descubriendo servicios..."
                    try {
                        if (hasBlePermissions(context)) {
                            g.discoverServices()
                        } else {
                            status = "Permiso BLE denegado"
                        }
                    } catch (e: SecurityException) {
                        status = "Error de seguridad: ${e.message}"
                    }
                } else {
                    status = "Desconectado"
                    glucose = "--"
                    gatt = null
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                val characteristic = g
                    .getService(SERVICE_UUID)
                    ?.getCharacteristic(CHAR_UUID)

                if (characteristic != null) {
                    try {
                        if (hasBlePermissions(context)) {
                            g.setCharacteristicNotification(characteristic, true)

                            val descriptor = characteristic.getDescriptor(CCCD_UUID)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                g.writeDescriptor(
                                    descriptor,
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(descriptor)
                            }
                            status = "Conectado a ESP32"
                        } else {
                            status = "Permiso BLE denegado"
                        }
                    } catch (e: SecurityException) {
                        status = "Error de seguridad: ${e.message}"
                    }
                } else {
                    status = "Servicio no encontrado"
                }
            }

            @Deprecated("Para API < 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == CHAR_UUID) {
                    @Suppress("DEPRECATION")
                    glucose= characteristic.getStringValue(0)
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHAR_UUID) {
                    glucose = String(value)
                }
            }
        }
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    if (!hasBlePermissions(context)) {
                        status = "Permiso BLE denegado"
                        return
                    }
                    val device = result.device
                    if (device.name == "ESP32_Temp") {
                        bluetoothAdapter.bluetoothLeScanner.stopScan(this)
                        isScanning = false
                        status = "Dispositivo encontrado, conectando..."
                        gatt = device.connectGatt(context, false, gattCallback)
                    }
                } catch (e: SecurityException) {
                    status = "Error de seguridad: ${e.message}"
                    isScanning = false
                }
            }

            override fun onScanFailed(errorCode: Int) {
                status = "Error de escaneo: $errorCode"
                isScanning = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                if (hasBlePermissions(context)) {
                    gatt?.disconnect()
                    gatt?.close()
                }
            } catch (e: SecurityException) {
                // ignorar al salir
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Monitor de Glucosa", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Estado: $status", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "$glucose mg/dL",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (!hasBlePermissions(context)) {
                    status = "Permisos BLE no concedidos"
                    return@Button
                }
                try {
                    if (gatt != null) {
                        gatt?.disconnect()
                        gatt?.close()
                        gatt = null
                        status = "Desconectado"
                    } else {
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (gatt != null) "Desconectar"
                else if (isScanning) "Buscando..."
                else "Conectar ESP32"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Guardar en Firestore
        Button(
            onClick = {
                if (glucose != "--") {
                    saveGlucoseToFirestore(glucose)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            ),
            enabled = gatt != null && glucose != "--"
        ) {
            Text("Guardar lectura actual")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Historial de lecturas
        if (readings.isNotEmpty()) {
            Text("Últimas lecturas", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(readings) { record ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${record["glucose"]} mg/dL",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = record["formattedTime"]?.toString() ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = {
            Firebase.auth.signOut()
            navController.navigate("login") {
                popUpTo("home") { inclusive = true }
            }
        }) {
            Text("Cerrar sesión", color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}