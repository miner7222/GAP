package io.github.miner7222.gap

enum class XposedServiceState {
    WAITING,
    BOUND,
    UNAVAILABLE,
}

object XposedServiceBannerPresenter {
    fun shouldShowActivationBanner(state: XposedServiceState): Boolean {
        return state == XposedServiceState.UNAVAILABLE
    }
}
