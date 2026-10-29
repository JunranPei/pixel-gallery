package com.pixel.gallery.ui.viewer

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

class ViewerTaskWindowTest {
    @Test fun lateInitializationNestedWindowsAndRepeatedCloseRestoreSettings() {
        val window = ViewerTaskWindow()
        val first = window.enter()
        val source = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
        val disk = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
        try {
            window.attach(source, disk, 2)
            assertEquals(4, source.corePoolSize); assertEquals(3, disk.corePoolSize)
            val second = window.enter()
            first.close(); first.close()
            assertEquals(4, source.maximumPoolSize); assertEquals(3, disk.maximumPoolSize)
            second.close()
            assertEquals(2, source.corePoolSize); assertEquals(2, source.maximumPoolSize)
            assertEquals(1, disk.corePoolSize); assertEquals(1, disk.maximumPoolSize)
        } finally { first.close(); source.shutdownNow(); disk.shutdownNow() }
    }

    @Test fun largerConfiguredPoolIsNeverReducedByViewerWindow() {
        val source = Executors.newFixedThreadPool(8) as ThreadPoolExecutor
        val disk = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
        try {
            val window = ViewerTaskWindow()
            window.attach(source, disk, 8)
            window.enter().use {
                assertEquals(8, source.corePoolSize)
                assertEquals(3, disk.corePoolSize)
            }
            assertEquals(8, source.corePoolSize); assertEquals(1, disk.corePoolSize)
        } finally { source.shutdownNow(); disk.shutdownNow() }
    }

    @Test fun exceptionalExitRestoresAndClosedPoolsDoNotBreakDisposal() {
        val source = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
        val disk = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
        val window = ViewerTaskWindow()
        window.attach(source, disk, 1)
        try { window.enter().use { error("injected") } } catch (_: IllegalStateException) {}
        assertEquals(1, source.maximumPoolSize); assertEquals(1, disk.maximumPoolSize)
        val lease = window.enter()
        source.shutdownNow(); disk.shutdownNow()
        lease.close()
    }
}
