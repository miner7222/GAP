package io.github.miner7222.gap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionStateRuntimeTest {

    @Test
    fun forcesRemovedCompatUltraHdStatesToReadFalse() {
        val runtime = runtime(useCompatibilityLsr = true, exposed = false)

        assertTrue(runtime.shouldForceSavedBooleanReadFalse("com.nexon.nsc.maplemswitchFrameInterpolation"))
        assertTrue(runtime.shouldForceSavedBooleanReadFalse("com.nexon.nsc.maplemswitchSuperResolution"))
    }

    @Test
    fun coercesRemovedCompatSwitchWritesToFalse() {
        val runtime = runtime(useCompatibilityLsr = true, exposed = false)

        assertFalse(runtime.coerceSavedBooleanWrite("com.nexon.nsc.maplemswitchFrameInterpolation", true))
        assertFalse(runtime.coerceSavedBooleanWrite("com.nexon.nsc.maplemswitchSuperResolution", true))
    }

    @Test
    fun keepsSupportedPackageSwitchStateUntouched() {
        val runtime = runtime(useCompatibilityLsr = true, exposed = true)

        assertFalse(runtime.shouldForceSavedBooleanReadFalse("com.nexon.nsc.maplemswitchFrameInterpolation"))
        assertTrue(runtime.coerceSavedBooleanWrite("com.nexon.nsc.maplemswitchFrameInterpolation", true))
    }

    @Test
    fun ignoresNonCompatDevicesAndUnrelatedKeys() {
        val nonCompat = runtime(useCompatibilityLsr = false, exposed = false)
        val compat = runtime(useCompatibilityLsr = true, exposed = false)

        assertFalse(nonCompat.shouldForceSavedBooleanReadFalse("com.nexon.nsc.maplemswitchFrameInterpolation"))
        assertFalse(compat.shouldForceSavedBooleanReadFalse("switch_barrage"))
    }

    @Test
    fun returnsGameHelperSwitchKeysOnlyForRemovedCompatPackages() {
        val runtime = runtime(useCompatibilityLsr = true, exposed = false)

        assertEquals(
            listOf(
                "com.nexon.nsc.maplemswitchSuperResolution",
                "com.nexon.nsc.maplemswitchFrameInterpolation",
            ),
            runtime.disabledSwitchKeys("com.nexon.nsc.maplem"),
        )
    }

    private fun runtime(
        useCompatibilityLsr: Boolean,
        exposed: Boolean,
    ): SuperResolutionStateRuntime {
        return SuperResolutionStateRuntime(
            useCompatibilityLsr = { useCompatibilityLsr },
            shouldExposeSuperResolution = { exposed },
        )
    }
}
