package com.pixel.gallery.ui.viewer

import androidx.compose.runtime.*

internal class VideoOutputReadiness {
    var generation by mutableIntStateOf(0)
        private set
    var readyGeneration by mutableIntStateOf(-1)
        private set
    private var bufferedGeneration by mutableIntStateOf(-1)
    var texture by mutableStateOf(false)
        private set
    private var output: Any? = null

    fun bind(output: Any, texture: Boolean) {
        if (this.output === output && this.texture == texture) return
        this.output = output
        this.texture = texture
        generation++
        readyGeneration = -1
        bufferedGeneration = -1
    }

    fun owns(output: Any) = this.output === output

    fun owns(output: Any, generation: Int) = owns(output) && this.generation == generation

    fun unbind(output: Any) {
        if (!owns(output)) return
        this.output = null
        readyGeneration = -1
        bufferedGeneration = -1
    }

    fun rendered(output: Any) {
        if (owns(output)) {
            readyGeneration = generation
            if (texture) bufferedGeneration = generation
        }
    }

    fun bufferAvailable(output: Any, generation: Int) {
        if (owns(output, generation)) bufferedGeneration = generation
    }

    fun readyAfter(previousGeneration: Int, texture: Boolean) =
        this.texture == texture && generation > previousGeneration &&
            readyGeneration == generation && bufferedGeneration == generation
}
