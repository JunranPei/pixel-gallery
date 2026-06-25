package com.pixel.gallery.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {
    private const val TAG = "AppLogger"
    private val executor = Executors.newSingleThreadExecutor()
    private var logDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logDir = File(context.cacheDir, "logs").apply {
            if (!exists()) {
                mkdirs()
            }
        }
        setupCrashHandler()
        log("AppLogger", "Logger initialized. Cache dir: ${logDir?.absolutePath}")
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logCrash(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val crashInfo = buildString {
            append("================ CRASH ================\n")
            append("Time: ${dateFormat.format(Date())}\n")
            append("Thread: ${thread.name} (id: ${thread.id})\n")
            append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
            append("Stacktrace:\n")
            append(stackTrace)
            append("=======================================\n\n")
        }

        // 同步写入，避免应用退出时未写完
        try {
            logDir?.let { dir ->
                val crashFile = File(dir, "crash_log.txt")
                FileWriter(crashFile, true).use { writer ->
                    writer.write(crashInfo)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        }
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val time = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        val formattedLog = buildString {
            append("$time [$threadName] $tag: $message")
            if (throwable != null) {
                append("\n")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
            append("\n")
        }

        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }

        executor.submit {
            try {
                logDir?.let { dir ->
                    val logFile = File(dir, "app_log.txt")
                    if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                        logFile.delete()
                    }
                    FileWriter(logFile, true).use { writer ->
                        writer.write(formattedLog)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log file", e)
            }
        }
    }

    fun getLogFile(): File? {
        return logDir?.let { File(it, "app_log.txt") }
    }

    fun getCrashLogFile(): File? {
        return logDir?.let { File(it, "crash_log.txt") }
    }
}
