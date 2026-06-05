package io.github.miner7222.gap

sealed interface XposedServiceState {
    data object Waiting : XposedServiceState
    data object Unavailable : XposedServiceState
    data class Bound(
        val missingScopes: List<String>,
    ) : XposedServiceState
}

object XposedServiceBannerPresenter {
    fun resolve(state: XposedServiceState): XposedServiceBannerState {
        return when (state) {
            XposedServiceState.Unavailable -> XposedServiceBannerState.ActivationRequired
            XposedServiceState.Waiting -> XposedServiceBannerState.Hidden
            is XposedServiceState.Bound -> {
                if (state.missingScopes.isEmpty()) {
                    XposedServiceBannerState.Hidden
                } else {
                    XposedServiceBannerState.MissingScopes(
                        missingScopes = state.missingScopes,
                        displayScopes = state.missingScopes.joinToString { "[$it]" },
                    )
                }
            }
        }
    }
}

sealed class XposedServiceBannerState(
    val showScopeRequestAction: Boolean,
) {
    data object Hidden : XposedServiceBannerState(showScopeRequestAction = false)
    data object ActivationRequired : XposedServiceBannerState(showScopeRequestAction = false)
    data class MissingScopes(
        val missingScopes: List<String>,
        val displayScopes: String,
    ) : XposedServiceBannerState(showScopeRequestAction = true)
}
