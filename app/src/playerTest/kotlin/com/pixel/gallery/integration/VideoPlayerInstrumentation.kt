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
            val report = when (suite) {
                "motion-power" -> MotionPowerChecks(this).exercise()
                "pip" -> VideoPipControlChecks(this).exercise()
                else -> VideoPlayerChecks(this).exercise()
            }
            result.putString("stream", "\n$report\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
