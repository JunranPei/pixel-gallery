package com.pixel.gallery.integration

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class VideoPlayerInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            result.putString("stream", "\n" + VideoPlayerChecks(this).exercise() + "\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
