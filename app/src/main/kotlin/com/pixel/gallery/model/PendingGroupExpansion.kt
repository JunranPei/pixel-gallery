package com.pixel.gallery.model

/** UI overrides remain until their own write completes, including rapid repeated taps. */
internal class PendingGroupExpansion {
    data class Request(val id: String, val collapsed: Boolean, val sequence: Long)
    private var sequence = 0L
    private val pending = mutableMapOf<String, Request>()
    fun begin(id: String, displayed: AlbumGrouping): Request =
        Request(id, id !in displayed.collapsedIds, ++sequence).also { pending[id] = it }
    fun finish(request: Request) {
        if (pending[request.id] == request) pending.remove(request.id)
    }
    fun apply(state: AlbumGrouping): AlbumGrouping {
        val collapsed = state.collapsedIds.toMutableSet()
        pending.values.forEach { if (it.collapsed) collapsed.add(it.id) else collapsed.remove(it.id) }
        return state.copy(collapsedIds = collapsed)
    }
}
