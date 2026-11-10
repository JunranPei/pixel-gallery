package com.pixel.gallery.integration

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class VideoPlayerInstrumentation : Instrumentation() {
    private var suite: String? = null
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite"); super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            result.putString("stream", "\n" + (if (suite == "pip") VideoPipControlChecks(this).exercise() else VideoPlayerChecks(this).exercise()) + "\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
