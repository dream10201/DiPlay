package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import java.io.File

internal object DiPlayBluetooth {
    private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    @Volatile private var rootAddress: String? = null
    @Volatile private var rootChecked = false

    fun isValidAddress(text: String): Boolean =
        MAC.matches(text) && !text.startsWith("02:00:00:00:00:") && text != "00:00:00:00:00:00"

    fun localAddress(context: Context): String? {
        DiPlayPreferences.bluetoothAddress(context)?.let { return it }
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull { isValidAddress(it) } ?: rootAddress()
    }

    private fun rootAddress(): String? {
        if (rootChecked) return rootAddress
        synchronized(this) {
            if (rootChecked) return rootAddress
            rootAddress = readWithRoot()
            rootChecked = true
            return rootAddress
        }
    }

    private fun readWithRoot(): String? {
        if (listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su").none { File(it).exists() } &&
            System.getenv("PATH")?.split(':')?.none { File(it, "su").exists() } != false
        ) return null
        return runCatching {
            val process = ProcessBuilder("su", "-c", "settings get secure bluetooth_address")
                .redirectErrorStream(true).start()
            var output = ""
            val reader = Thread { output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("") }
            reader.isDaemon = true
            reader.start()
            reader.join(5_000)
            if (reader.isAlive) {
                process.destroy()
                return null
            }
            output.trim().uppercase().takeIf { isValidAddress(it) }
        }.getOrNull()
    }
}
