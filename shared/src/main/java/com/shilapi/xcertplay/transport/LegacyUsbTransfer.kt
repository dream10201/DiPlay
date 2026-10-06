package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build

/** Before Android 9, one synchronous bulk transfer is truncated to 16 KiB. */
internal const val LEGACY_BULK_TRANSFER_LIMIT = 16_384

/** Async USB reads with a timeout require Android 8. */
internal val usbRequestWaitTimeoutSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

/** Writes [data] in transfers Android can complete; returns bytes sent or the failing result. */
internal fun UsbDeviceConnection.bulkWriteAll(endpoint: UsbEndpoint, data: ByteArray, timeoutMillis: Int): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return bulkTransfer(endpoint, data, data.size, timeoutMillis)
    }
    var offset = 0
    while (offset < data.size) {
        val length = minOf(LEGACY_BULK_TRANSFER_LIMIT, data.size - offset)
        val transferred = bulkTransfer(endpoint, data, offset, length, timeoutMillis)
        if (transferred <= 0) return if (offset > 0) offset else transferred
        offset += transferred
    }
    return offset
}
