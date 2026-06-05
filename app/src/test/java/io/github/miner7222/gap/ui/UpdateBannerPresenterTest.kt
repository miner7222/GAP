package io.github.miner7222.gap.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateBannerPresenterTest {

    @Test
    fun showsAvailableBannerWhenReleaseIsNewer() {
        val state = UpdateBannerPresenter.resolve(
            currentVersionName = "v1.0.0",
            release = ReleaseInfo(tagName = "v1.0.1", body = ""),
        )

        assertEquals(
            UpdateBannerState.Available(
                currentVersionName = "v1.0.0",
                latestVersionName = "v1.0.1",
            ),
            state,
        )
    }

    @Test
    fun hidesBannerWhenReleaseIsNotNewer() {
        val state = UpdateBannerPresenter.resolve(
            currentVersionName = "v1.0.1",
            release = ReleaseInfo(tagName = "v1.0.1", body = ""),
        )

        assertEquals(UpdateBannerState.Hidden, state)
    }
}
