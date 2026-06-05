package io.github.miner7222.gap

import android.util.Log
import io.github.libxposed.service.XposedService

internal class XposedScopeRequester {

    fun missingScopes(service: XposedService): List<String> {
        return runCatching {
            XposedScopeRequestPlanner.missingScopes(service.scope)
        }.getOrElse {
            Log.w(TAG, "Failed to read LSPosed scope for GAP", it)
            emptyList()
        }
    }

    fun requestScopes(
        service: XposedService,
        scopes: List<String>,
        onApproved: (List<String>) -> Unit = {},
        onFailed: (String) -> Unit = {},
    ): List<String> {
        if (scopes.isEmpty()) {
            Log.i(TAG, "LSPosed scope already contains required GAP targets")
            return emptyList()
        }
        return runCatching {
            Log.i(TAG, "Requesting missing LSPosed scopes for GAP: ${scopes.joinToString()}")
            service.requestScope(scopes, ScopeRequestCallback(scopes, onApproved, onFailed))
            scopes
        }.getOrElse {
            Log.w(TAG, "Failed to request LSPosed scope for GAP", it)
            onFailed(it.message ?: it.javaClass.simpleName)
            emptyList()
        }
    }

    private class ScopeRequestCallback(
        private val requestedScopes: List<String>,
        private val onApproved: (List<String>) -> Unit,
        private val onFailed: (String) -> Unit,
    ) : XposedService.OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) {
            Log.i(TAG, "LSPosed scope request approved: requested=$requestedScopes approved=$approved")
            onApproved(approved)
        }

        override fun onScopeRequestFailed(message: String) {
            Log.w(TAG, "LSPosed scope request failed for $requestedScopes: $message")
            onFailed(message)
        }
    }

    private companion object {
        private const val TAG = "GAP"
    }
}
