package io.github.miner7222.gap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessBannerPresenterTest {

    @Test
    fun showsBannerWhenRootAccessIsMissing() {
        assertTrue(RootAccessBannerPresenter.shouldShowRootBanner(hasRootAccess = false))
    }

    @Test
    fun hidesBannerWhenRootAccessIsGranted() {
        assertFalse(RootAccessBannerPresenter.shouldShowRootBanner(hasRootAccess = true))
    }
}
