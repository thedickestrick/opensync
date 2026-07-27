package com.opensync.foldersync.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024; i++
    }
    return if (i == 0) "${bytes} B" else String.format(Locale.getDefault(), "%.1f %s", value, units[i])
}

fun formatTimestamp(time: Long): String =
    if (time <= 0) "Never"
    else SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(time))
