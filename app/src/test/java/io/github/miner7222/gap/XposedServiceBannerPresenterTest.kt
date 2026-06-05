package io.github.miner7222.gap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedServiceBannerPresenterTest {

    @Test
    fun showsActivationBannerWhenXposedServiceIsUnavailable() {
        val banner = XposedServiceBannerPresenter.resolve(XposedServiceState.Unavailable)

        assertEquals(XposedServiceBannerState.ActivationRequired, banner)
    }

    @Test
    fun showsMissingScopeBannerWithRequestActionWhenScopesAreMissing() {
        val banner = XposedServiceBannerPresenter.resolve(
            XposedServiceState.Bound(
                missingScopes = listOf("system", "com.zui.game.service"),
            ),
        )

        assertEquals(
            XposedServiceBannerState.MissingScopes(
                missingScopes = listOf("system", "com.zui.game.service"),
                displayScopes = "[system], [com.zui.game.service]",
            ),
            banner,
        )
        assertTrue(banner.showScopeRequestAction)
    }

    @Test
    fun hidesBannerWhileWaitingOrWhenScopeIsComplete() {
        assertEquals(
            XposedServiceBannerState.Hidden,
            XposedServiceBannerPresenter.resolve(XposedServiceState.Waiting),
        )
        assertEquals(
            XposedServiceBannerState.Hidden,
            XposedServiceBannerPresenter.resolve(XposedServiceState.Bound(missingScopes = emptyList())),
        )
    }

    @Test
    fun activationBannerDoesNotShowScopeRequestAction() {
        val banner = XposedServiceBannerPresenter.resolve(XposedServiceState.Unavailable)

        assertFalse(banner.showScopeRequestAction)
    }
}
