package io.github.miner7222.gap

import android.content.Context
import android.os.IBinder
import com.zui.server.lsr.LsrService

internal class LsrRuntime {
    @Volatile
    private var systemContext: Context? = null
    @Volatile
    private var fallbackLsrBinder: IBinder? = null

    fun resolveProcessApplicationContext(): Context? {
        return runCatching {
            ReflectCompat.callStaticMethod(
                ReflectCompat.findClass("android.app.ActivityThread", null),
                "currentApplication",
            ) as? Context
        }.getOrElse {
            AndroidInternals.log("Failed to resolve process application context", it)
            null
        }
    }

    fun resolveSystemContext(instance: Any? = null): Context? {
        (instance?.let {
            runCatching { ReflectCompat.getObjectField(it, "mContext") as? Context }.getOrNull()
        } ?: systemContext)?.let { context ->
            rememberSystemContext(context)
            return context
        }
        return resolveProcessApplicationContext()?.also(::rememberSystemContext)
    }

    fun ensureRegistered(systemServer: Any?) {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return
        }
        runCatching {
            val context = ReflectCompat.getObjectField(systemServer, "mSystemContext") as? Context ?: return
            rememberSystemContext(context)
            LsrServiceRegistry.ensureRegistered(context)
        }.onFailure {
            AndroidInternals.log("Failed to register lenovosr from modern Xposed hook", it)
        }
    }

    fun resolveServiceManagerResult(methodName: String, serviceName: Any?, currentResult: Any?): Any? {
        if (serviceName != LsrService.LSR_SERVICE) {
            return currentResult
        }
        return if (currentResult == null) {
            val binder = getOrCreateFallbackLsrBinder()
            AndroidInternals.log("ServiceManager.$methodName(lenovosr) -> fallback binder=$binder")
            binder
        } else {
            AndroidInternals.log("ServiceManager.$methodName(lenovosr) -> original result=$currentResult")
            currentResult
        }
    }

    private fun rememberSystemContext(context: Context) {
        if (systemContext == null) {
            systemContext = context.applicationContext ?: context
        }
    }

    private fun getOrCreateFallbackLsrBinder(): IBinder? {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return null
        }
        // In system_server the binder that failed to register is retained by LsrServiceRegistry.
        LsrServiceRegistry.getFallbackBinder()?.let { return it }

        // In client processes, construct a local in-process service instead.
        fallbackLsrBinder?.let { return it }
        synchronized(this) {
            fallbackLsrBinder?.let { return it }
            val context = resolveProcessApplicationContext() ?: return null
            val service = LsrService(context)
            service.onStartLocal()
            val binder: IBinder = LsrService.BinderService(service)
            fallbackLsrBinder = binder
            AndroidInternals.log("Created fallback local LsrService binder")
            return binder
        }
    }
}
