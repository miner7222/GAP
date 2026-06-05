package io.github.miner7222.gap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedServiceBannerPresenterTest {

    @Test
    fun showsActivationBannerWhenXposedServiceIsUnavailable() {
        assertTrue(
            XposedServiceBannerPresenter.shouldShowActivationBanner(
                XposedServiceState.UNAVAILABLE,
            ),
        )
    }

    @Test
    fun hidesActivationBannerWhileWaitingOrBound() {
        assertFalse(
            XposedServiceBannerPresenter.shouldShowActivationBanner(
                XposedServiceState.WAITING,
            ),
        )
        assertFalse(
            XposedServiceBannerPresenter.shouldShowActivationBanner(
                XposedServiceState.BOUND,
            ),
        )
    }
}
