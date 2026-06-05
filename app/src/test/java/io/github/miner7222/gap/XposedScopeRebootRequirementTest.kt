package io.github.miner7222.gap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedScopeRebootRequirementTest {

    @Test
    fun requiresRebootWhenSystemScopeWasApproved() {
        assertTrue(
            XposedScopeRebootRequirement.requiresRebootForApprovedScopes(
                listOf("system", "com.zui.game.service"),
            ),
        )
    }

    @Test
    fun doesNotRequireRebootForRegularPackageScopeOnly() {
        assertFalse(
            XposedScopeRebootRequirement.requiresRebootForApprovedScopes(
                listOf("com.zui.game.service"),
            ),
        )
    }

    @Test
    fun keepsPendingStateOnlyWithinSameBoot() {
        assertTrue(XposedScopeRebootRequirement.isPendingForBoot(pendingAtElapsedRealtime = 10_000L, now = 20_000L))
        assertFalse(XposedScopeRebootRequirement.isPendingForBoot(pendingAtElapsedRealtime = 10_000L, now = 2_000L))
        assertFalse(XposedScopeRebootRequirement.isPendingForBoot(pendingAtElapsedRealtime = -1L, now = 20_000L))
    }
}
