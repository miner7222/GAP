package io.github.miner7222.gap

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GapApplication : Application(), XposedServiceHelper.OnServiceListener {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scopeRequester = XposedScopeRequester()

    override fun onCreate() {
        super.onCreate()
        runCatching {
            XposedServiceHelper.registerListener(this)
        }.onFailure {
            Log.w(TAG, "Failed to register Xposed service listener", it)
        }
    }

    override fun onServiceBind(service: XposedService) {
        executor.execute {
            scopeRequester.requestMissingScopes(service)
        }
    }

    override fun onServiceDied(service: XposedService) {
        Log.i(TAG, "Xposed service died")
    }

    private companion object {
        private const val TAG = "GAP"
    }
}
