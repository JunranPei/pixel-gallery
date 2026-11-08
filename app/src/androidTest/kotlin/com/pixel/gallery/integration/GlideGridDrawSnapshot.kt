package com.pixel.gallery.integration

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import java.lang.reflect.Method
import java.lang.reflect.Field

/** Test-only observation of the real Glide draw nodes; no screenshots or UI replacement. */
internal class GlideGridDrawSnapshot {
    private val methods = mutableMapOf<Pair<Class<*>, String>, Method>()
    private val fields = mutableMapOf<Pair<Class<*>, String>, Field>()

    private fun getter(value: Any, name: String): Any? = methods.getOrPut(value.javaClass to name) {
        value.javaClass.methods.first { (it.name == name || it.name.startsWith("$name\$")) && it.parameterCount == 0 }
            .apply { isAccessible = true }
    }.invoke(value)

    private fun field(value: Any, name: String): Any? = fields.getOrPut(value.javaClass to name) {
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }.get(value)

    fun read(decor: View): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        fun visitLayout(layout: Any) {
            val chain = getter(layout, "getNodes")!!
            var node = getter(chain, "getHead")
            while (node != null) {
                if (node.javaClass.name == "com.bumptech.glide.integration.compose.GlideNode") {
                    val coordinates = getter(node, "getCoordinator") as? LayoutCoordinates
                    if (coordinates != null && coordinates.isAttached) {
                        val bounds = coordinates.boundsInWindow()
                        if (bounds.width > 10 && bounds.height > 10 && bounds.right > 0 && bounds.bottom > 0 &&
                            bounds.left < decor.width && bounds.top < decor.height) {
                            val state = field(node, "state")!!
                            val primary = field(node, "primary")
                            result += mapOf("state" to state.javaClass.simpleName,
                                "ready" to (primary != null && state.javaClass.simpleName == "Success"),
                                "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        }
                    }
                }
                node = getter(node, "getChild")
            }
            (getter(layout, "getChildren") as List<*>).forEach { visitLayout(it!!) }
        }
        fun visitView(view: View) {
            if (view.visibility != View.VISIBLE || view.alpha <= .01f) return
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") visitLayout(getter(view, "getRoot")!!)
            if (view is ViewGroup) repeat(view.childCount) { visitView(view.getChildAt(it)) }
        }
        visitView(decor)
        return result
    }
}
