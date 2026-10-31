package app.okoklibre.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime-permission and Bluetooth-radio guards. Every public BLE entry point
 * must pass through here so missing permissions surface as state/errors
 * instead of SecurityException crashes.
 *
 * BLUETOOTH_SCAN / BLUETOOTH_CONNECT are runtime permissions on API 31+. Older
 * devices get the legacy BLUETOOTH/BLUETOOTH_ADMIN permissions at install time,
 * but also need location at runtime (all declared with maxSdkVersion 30).
 */
object BlePermissions {

    fun requiredRuntimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Before Android 12, the system hides BLE scan results from apps without location.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasBlePermissions(context: Context): Boolean =
        requiredRuntimePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(context: Context): Boolean =
        try {
            bluetoothAdapter(context)?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
}
