package com.pixel.gallery.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeTonalSpot
import org.junit.Assert.*
import org.junit.Test

class SeedColorSchemeTest {
    @Test fun storedColorsSupportLegacyPresetsAndContinuousHueSeeds() {
        ManualColorSeeds.forEachIndexed { index, seed -> assertEquals(seed, resolveManualColorSeed(index)) }
        for (hue in listOf(0.0, 60.0, 179.0, 270.0, 360.0)) {
            val seed = Hct.from(hue, 36.0, 50.0).toInt()
            assertEquals(seed, resolveManualColorSeed(seed))
            for (dark in listOf(false, true)) {
                assertEquals(seedColorScheme(seed, dark).primary, manualColorScheme(seed, dark).primary)
            }
        }
    }

    @Test fun seedRolesMatchOfficialMaterialAlgorithm() {
        for (seed in ManualColorSeeds + listOf(0xFF12AB34.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            for (dark in listOf(false, true)) {
                val expected = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
                val actual = seedColorScheme(seed, dark)
                assertEquals(expected.primary, actual.primary.toArgb())
                assertEquals(expected.onPrimary, actual.onPrimary.toArgb())
                assertEquals(expected.secondaryContainer, actual.secondaryContainer.toArgb())
                assertEquals(expected.surfaceContainer, actual.surfaceContainer.toArgb())
                assertEquals(expected.onSurface, actual.onSurface.toArgb())
                assertEquals(expected.error, actual.error.toArgb())
                assertEquals(expected.tertiaryFixed, actual.tertiaryFixed.toArgb())
            }
        }
    }
}
