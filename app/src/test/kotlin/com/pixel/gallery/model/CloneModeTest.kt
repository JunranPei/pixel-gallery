package com.pixel.gallery.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneModeTest {
    @Test
    fun onlyAutoStorageValueEnablesAutomaticClones() {
        assertTrue(CloneMode.automaticEnabledFromStoredValue(CloneMode.AUTO.name))
        assertFalse(CloneMode.automaticEnabledFromStoredValue(CloneMode.DISABLED.name))
        assertFalse(CloneMode.automaticEnabledFromStoredValue(CloneMode.MANUAL.name))
        assertFalse(CloneMode.automaticEnabledFromStoredValue(null))
    }
}
