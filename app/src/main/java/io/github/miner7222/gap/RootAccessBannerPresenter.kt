package io.github.miner7222.gap

object RootAccessBannerPresenter {
    fun shouldShowRootBanner(hasRootAccess: Boolean): Boolean = !hasRootAccess
}
