package io.github.miner7222.gap.ui

object UpdateBannerPresenter {
    fun resolve(currentVersionName: String, release: ReleaseInfo): UpdateBannerState {
        return if (UpdateChecker.isNewerRelease(release.tagName, currentVersionName)) {
            UpdateBannerState.Available(
                currentVersionName = currentVersionName,
                latestVersionName = release.tagName,
            )
        } else {
            UpdateBannerState.Hidden
        }
    }
}

sealed interface UpdateBannerState {
    data object Hidden : UpdateBannerState

    data class Available(
        val currentVersionName: String,
        val latestVersionName: String,
    ) : UpdateBannerState
}
