package io.github.miner7222.gap

import android.util.Log
import io.github.libxposed.service.XposedService

internal class XposedScopeRequester {

    fun requestMissingScopes(service: XposedService): List<String> {
        return runCatching {
            val missingScopes = XposedScopeRequestPlanner.missingScopes(service.scope)
            if (missingScopes.isEmpty()) {
                Log.i(TAG, "LSPosed scope already contains required GAP targets")
                return emptyList()
            }

            Log.i(TAG, "Requesting missing LSPosed scopes for GAP: ${missingScopes.joinToString()}")
            service.requestScope(missingScopes, ScopeRequestCallback(missingScopes))
            missingScopes
        }.getOrElse {
            Log.w(TAG, "Failed to request LSPosed scope for GAP", it)
            emptyList()
        }
    }

    private class ScopeRequestCallback(
        private val requestedScopes: List<String>,
    ) : XposedService.OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) {
            Log.i(TAG, "LSPosed scope request approved: requested=$requestedScopes approved=$approved")
        }

        override fun onScopeRequestFailed(message: String) {
            Log.w(TAG, "LSPosed scope request failed for $requestedScopes: $message")
        }
    }

    private companion object {
        private const val TAG = "GAP"
    }
}
