package com.pixel.gallery.ui.viewer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/** Registered only by the Settings switch. No manifest component can start the app. */
internal object ViewerDiagnosticsInterface {
    @Volatile private var receiver: BroadcastReceiver? = null
    val isOpen: Boolean get() = receiver != null

    @Synchronized fun setOpen(context: Context, open: Boolean) {
        val app = context.applicationContext
        if (open && receiver == null) {
            val registered = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pending = goAsync()
                    Thread({
                        try {
                            val response = handle(context, intent).put("requestId",intent.getStringExtra("request_id"))
                            pending.resultCode = Activity.RESULT_OK
                            pending.resultData = "GALLERY_DIAG_V2:"+Base64.encodeToString(response.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        } catch (error: Exception) {
                            pending.resultCode = Activity.RESULT_CANCELED
                            pending.resultData = "GALLERY_DIAG_ERROR:"+error.javaClass.simpleName
                        } finally { pending.finish() }
                    }, "viewer-diagnostic-request").apply { priority = Thread.MIN_PRIORITY }.start()
                }
            }
            ContextCompat.registerReceiver(app, registered,
                IntentFilter("${app.packageName}.VIEWER_DIAGNOSTICS_CONTROL"),
                Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
            receiver = registered
        } else if (!open) {
            receiver?.let(app::unregisterReceiver)
            receiver = null
            ViewerMemoryDiagnostics.setEnabled(app, false)
        }
    }

    internal fun status(context: Context) = JSONObject().put("protocol",2)
        .put("package",context.packageName).put("versionCode",PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName,0)))
        .put("pid",Process.myPid()).put("interfaceOpen",isOpen)
        .put("enabled",ViewerMemoryDiagnostics.isEnabled).put("performanceSampling",ViewerLoadMetrics.isEnabled)

    /** No navigation, repository refresh, media scan or process restart is invoked here. */
    internal fun handle(context: Context, intent: Intent): JSONObject {
        check(isOpen) { "Interface closed" }
        val folder = File(context.filesDir,"viewer-memory-diagnostics")
        when (intent.getStringExtra("command")) {
            "status" -> Unit
            "start" -> ViewerMemoryDiagnostics.setEnabled(context,true)
            "stop" -> ViewerMemoryDiagnostics.setEnabled(context,false)
            "capture" -> {
                ViewerMemoryDiagnostics.flush()
                val export = File(folder,"export").apply { mkdirs() }
                val memory = context.getSharedPreferences("viewer_memory",0).all
                File(export,"snapshot.json").writeText(status(context).put("capturedAtMillis",System.currentTimeMillis())
                    .put("viewerMemory",JSONObject(memory)).put("live",ViewerDiagnosticsSnapshot.capture()).toString())
                for (name in listOf("current.log","previous.log")) {
                    val source=File(folder,name);val target=File(export,name)
                    if (source.isFile) source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output,8192) } }
                    else target.delete()
                }
            }
            "read" -> {
                val name=intent.getStringExtra("resource")
                require(name in listOf("snapshot.json","current.log","previous.log"))
                val offset=intent.getLongExtra("offset",0)
                require(offset>=0)
                val file=File(File(folder,"export"),name!!)
                if (!file.isFile) throw FileNotFoundException()
                RandomAccessFile(file,"r").use { input ->
                    require(offset<=input.length())
                    input.seek(offset)
                    val data=ByteArray(minOf(16*1024L,input.length()-offset).toInt())
                    input.readFully(data)
                    return status(context).put("data",Base64.encodeToString(data,Base64.NO_WRAP))
                        .put("next",offset+data.size).put("eof",offset+data.size>=input.length())
                }
            }
            else -> throw IllegalArgumentException("Unsupported command")
        }
        return status(context)
    }
}
