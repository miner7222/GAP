package io.github.miner7222.gap

import io.github.miner7222.gap.DeviceCompatibility.CompatibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityTest {

    @Test
    fun rejectsMissingOrOldZuiVersionBeforeCheckingSoc() {
        assertEquals(
            CompatibilityStatus.UNSUPPORTED_OS_VERSION,
            DeviceCompatibility.evaluate("", "SM8750P"),
        )
        assertEquals(
            CompatibilityStatus.UNSUPPORTED_OS_VERSION,
            DeviceCompatibility.evaluate("16.9.999", "SM8750P"),
        )
        assertEquals(
            CompatibilityStatus.UNSUPPORTED_OS_VERSION,
            DeviceCompatibility.evaluate("not-a-version", "SM8750P"),
        )
    }

    @Test
    fun acceptsZuiVersionSeventeenOrNewer() {
        assertTrue(DeviceCompatibility.isSupportedZuiVersion("17.0"))
        assertTrue(DeviceCompatibility.isSupportedZuiVersion("17"))
        assertTrue(DeviceCompatibility.isSupportedZuiVersion("17.0.123"))
        assertTrue(DeviceCompatibility.isSupportedZuiVersion("18.0"))
        assertFalse(DeviceCompatibility.isSupportedZuiVersion("16.999"))
    }

    @Test
    fun allowsOnlySupportedSocModelsAfterOsPasses() {
        assertEquals(
            CompatibilityStatus.SUPPORTED,
            DeviceCompatibility.evaluate("17.0", "SM8750P"),
        )
        assertEquals(
            CompatibilityStatus.SUPPORTED,
            DeviceCompatibility.evaluate("17.0", " sm8850p "),
        )
        assertEquals(
            CompatibilityStatus.UNSUPPORTED_SOC,
            DeviceCompatibility.evaluate("17.0", "SM8550"),
        )
    }

    @Test
    fun detectsSm8850pNativeLsrService() {
        assertTrue(DeviceCompatibility.hasNativeLsrService("SM8850P"))
        assertFalse(DeviceCompatibility.hasNativeLsrService("SM8750P"))
        assertFalse(DeviceCompatibility.hasNativeLsrService(""))
    }
}
