package com.pixel.gallery.utils

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.pixel.gallery.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object AppDiagnostics {
    fun recordPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val records = JSONArray()
            exits.forEach { info ->
                records.put(JSONObject().apply {
                    put("timestamp", info.timestamp)
                    put("reason", info.reason)
                    put("status", info.status)
                    put("description", info.description)
                    put("process", info.processName)
                    put("rssKb", info.rss)
                    put("pssKb", info.pss)
                })
            }
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            File(dir, "process-exits.json").writeText(records.toString(2))
            exits.firstOrNull { it.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE }?.let { info ->
                info.traceInputStream?.use { input ->
                    File(dir, "exit-${info.timestamp}.trace").outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var remaining = 256 * 1024
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
            dir.listFiles().orEmpty().filter { it.name.startsWith("exit-") && it.extension == "trace" }
                .sortedByDescending { it.name }.drop(3).forEach { it.delete() }
        }.onFailure { android.util.Log.w("AppDiagnostics", "Exit history unavailable", it) }
    }

    fun export(context: Context, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("build.txt"))
            zip.write("${context.packageName}\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n".toByteArray())
            zip.closeEntry()
            File(context.cacheDir, "logs").listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                RandomAccessFile(file, "r").use { input ->
                    val length = input.length()
                    input.seek((length - 512 * 1024).coerceAtLeast(0))
                    val buffer = ByteArray(8192)
                    var remaining = minOf(length, 512 * 1024L)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
