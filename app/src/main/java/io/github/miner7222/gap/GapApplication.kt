package io.github.miner7222.gap

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GapApplication : Application(), XposedServiceHelper.OnServiceListener {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scopeRequester = XposedScopeRequester()
    @Volatile
    private var hasBoundXposedService = false
    @Volatile
    private var currentXposedService: XposedService? = null
    @Volatile
    private var currentMissingScopes: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        runCatching {
            XposedServiceHelper.registerListener(this)
        }.onFailure {
            Log.w(TAG, "Failed to register Xposed service listener", it)
        }
        mainHandler.postDelayed({
            if (!hasBoundXposedService) {
                updateXposedServiceState(XposedServiceState.Unavailable)
            }
        }, XPOSED_SERVICE_BIND_TIMEOUT_MS)
    }

    override fun onServiceBind(service: XposedService) {
        hasBoundXposedService = true
        currentXposedService = service
        executor.execute {
            val missingScopes = scopeRequester.missingScopes(service)
            currentMissingScopes = missingScopes
            updateXposedServiceState(XposedServiceState.Bound(missingScopes))
        }
    }

    override fun onServiceDied(service: XposedService) {
        hasBoundXposedService = false
        currentXposedService = null
        currentMissingScopes = emptyList()
        Log.i(TAG, "Xposed service died")
        updateXposedServiceState(XposedServiceState.Unavailable)
    }

    fun requestMissingXposedScopes() {
        val service = currentXposedService
        if (service == null) {
            updateXposedServiceState(XposedServiceState.Unavailable)
            return
        }

        executor.execute {
            val scopesToRequest = currentMissingScopes.ifEmpty {
                scopeRequester.missingScopes(service)
            }
            if (scopesToRequest.isEmpty()) {
                currentMissingScopes = emptyList()
                updateXposedServiceState(XposedServiceState.Bound(emptyList()))
                return@execute
            }

            scopeRequester.requestScopes(
                service = service,
                scopes = scopesToRequest,
                onApproved = { approved ->
                    val remainingScopes = currentMissingScopes.filterNot { approved.contains(it) }
                    currentMissingScopes = remainingScopes
                    updateXposedServiceState(XposedServiceState.Bound(remainingScopes))
                },
                onFailed = {
                    Log.w(TAG, "LSPosed scope request failed: $it")
                },
            )
        }
    }

    private fun updateXposedServiceState(state: XposedServiceState) {
        if (xposedServiceState == state) return
        xposedServiceState = state
        mainHandler.post {
            for (listener in xposedServiceStateListeners) {
                listener.onXposedServiceStateChanged(state)
            }
        }
    }

    interface XposedServiceStateListener {
        fun onXposedServiceStateChanged(state: XposedServiceState)
    }

    companion object {
        private const val TAG = "GAP"
        private const val XPOSED_SERVICE_BIND_TIMEOUT_MS = 1500L
        @Volatile
        private var xposedServiceState: XposedServiceState = XposedServiceState.Waiting
        private val xposedServiceStateListeners = CopyOnWriteArraySet<XposedServiceStateListener>()

        fun addXposedServiceStateListener(
            listener: XposedServiceStateListener,
            notifyImmediately: Boolean,
        ) {
            xposedServiceStateListeners += listener
            if (notifyImmediately) {
                listener.onXposedServiceStateChanged(xposedServiceState)
            }
        }

        fun removeXposedServiceStateListener(listener: XposedServiceStateListener) {
            xposedServiceStateListeners -= listener
        }
    }
}
