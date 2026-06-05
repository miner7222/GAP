package io.github.miner7222.gap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedScopeRebootBannerPresenterTest {

    @Test
    fun showsRebootBannerWhenSystemScopeIsApprovedAndNoScopeIsMissing() {
        assertTrue(
            XposedScopeRebootBannerPresenter.shouldShow(
                XposedServiceState.Bound(
                    missingScopes = emptyList(),
                    scopeRebootRequired = true,
                ),
            ),
        )
    }

    @Test
    fun hidesRebootBannerWhenMissingScopesStillNeedAction() {
        assertFalse(
            XposedScopeRebootBannerPresenter.shouldShow(
                XposedServiceState.Bound(
                    missingScopes = listOf("com.zui.game.service"),
                    scopeRebootRequired = true,
                ),
            ),
        )
    }

    @Test
    fun hidesRebootBannerWhenModuleServiceIsUnavailable() {
        assertFalse(XposedScopeRebootBannerPresenter.shouldShow(XposedServiceState.Unavailable))
        assertFalse(XposedScopeRebootBannerPresenter.shouldShow(XposedServiceState.Waiting))
    }
}
