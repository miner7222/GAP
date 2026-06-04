package io.github.miner7222.gap

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private companion object {
        private const val TAG = "GAP"
        private const val GAME_HELPER_PACKAGE = "com.zui.game.service"
    }

    private val systemHooksInstalled = AtomicBoolean(false)
    private val gameHooksInstalled = AtomicBoolean(false)
    @Volatile
    private var cachedGameHelperClassLoader: ClassLoader? = null
    private val lsrRuntime = LsrRuntime()
    private val superResolutionRuntime = SuperResolutionRuntime(
        resolveSystemContext = lsrRuntime::resolveSystemContext,
        resolveProcessApplicationContext = lsrRuntime::resolveProcessApplicationContext,
    )
    private val aiSoundRuntime = AiSoundRuntime(
        resolveGameHelperClassLoader = ::resolveGameHelperClassLoader,
    )
    private val gameEnhancementRuntime = GameEnhancementRuntime(
        resolveGameHelperClassLoader = ::resolveGameHelperClassLoader,
    )
    private val floatingBarRuntime = FloatingBarRuntime(
        resolveGameHelperClassLoader = ::resolveGameHelperClassLoader,
        isBaldurBoard = ::isBaldurBoard,
        shouldExposeSuperResolution = { packageName -> superResolutionRuntime.shouldExpose(packageName) },
    )
    private val superResolutionHooks = SuperResolutionHooks(
        superResolutionRuntime = superResolutionRuntime,
        floatingBarRuntime = floatingBarRuntime,
    )
    private val romFeatureRuntime = RomFeatureRuntime(
        resolveGameHelperClassLoader = ::resolveGameHelperClassLoader,
        isBaldurBoard = ::isBaldurBoard,
    )
    private val romFeatureHooks = RomFeatureHooks(
        romFeatureRuntime = romFeatureRuntime,
        isBaldurBoard = ::isBaldurBoard,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        AndroidInternals.log(
            "Loaded GAP module in ${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion",
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching {
            HookScope(this, param.classLoader).applySystemHooks()
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install system_server hooks", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != GAME_HELPER_PACKAGE || !param.isFirstPackage) {
            return
        }
        runCatching {
            HookScope(this, param.classLoader).applyGameHelperHooks()
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install Game Helper hooks", it)
        }
    }

    private fun HookScope.applySystemHooks() {
        if (!systemHooksInstalled.compareAndSet(false, true)) return

        if (AndroidInternals.useCompatibilityLsr()) {
            // NOTE: Do NOT install ServiceManager.getService fallback in system_server.
            // It would intercept the getService() check inside LsrServiceRegistry.ensureRegistered(),
            // making it think the service is already registered and preventing actual registration.

            // Register the compatibility Binder service early in system_server
            // so Game Helper can bind to lenovosr on non-Baldur devices.
            afterMethod("com.android.server.SystemServer", "startBootstrapServices", parameterCount = 1) {
                lsrRuntime.ensureRegistered(instanceOrNull)
            }
            afterMethod("com.android.server.SystemServer", "startCoreServices", parameterCount = 1) {
                lsrRuntime.ensureRegistered(instanceOrNull)
            }
            afterMethod("com.android.server.SystemServer", "startOtherServices", parameterCount = 1) {
                lsrRuntime.ensureRegistered(instanceOrNull)
            }
        } else {
            AndroidInternals.log("Skipping compatibility lenovosr bootstrap hooks")
        }

        AndroidInternals.log("Installed modern Xposed system_server hooks")
    }

    private fun HookScope.applyGameHelperHooks() {
        if (!gameHooksInstalled.compareAndSet(false, true)) return

        // Cache the Game Helper classloader for helpers that run after the
        // initial package hook, such as SR state sync and feature list patching.
        cachedGameHelperClassLoader = appClassLoader

        if (AndroidInternals.useCompatibilityLsr()) {
            // If ServiceManager registration is blocked, keep a local binder path so
            // Game Helper can still talk to the reconstructed compatibility service.
            installLsrServiceManagerFallback()
        } else {
            AndroidInternals.log("Using stock/native lenovosr; compatibility fallback binder disabled")
        }

        if (!isBaldurBoard()) {
            // Make Game Helper follow the Baldur feature path on non-Baldur devices.
            replaceMethodWithTrue("com.zui.util.DeviceUtils", "isBaldur")
        }

        // Normalize the floating-bar feature inventory itself, then backstop the
        // runtime feature checks that consult it later.
        romFeatureHooks.install(this)
        // Keep SR device support enabled while swapping the stock
        // whitelist inputs to the active gpp_app_list view.
        superResolutionHooks.install(this)
        // Normalize AI sound package gating so both PUBG package names work
        // regardless of the device's ROW region flag.
        installAiSoundEnhancementHooks()
        // Wide Vision and 4D vibration still keep separate regional lists in
        // stock Game Helper, so merge them at runtime.
        installWideVisionHooks()
        installVibrationSupportHooks()

        AndroidInternals.log("Installed modern Xposed game helper hooks")
    }

    private fun HookScope.installAiSoundEnhancementHooks() {
        hookAiSoundItemInitialization()
        hookAiSoundToggleHandler()
        hookAiSoundFloatingNotice()
    }

    private fun HookScope.installWideVisionHooks() {
        val className = "com.zui.ugame.gamesetting.ui.options.content.widevision.MoreGameViewModel"
        val methodName = "<init>"
        if (!hasMethodWithParamCount(className, methodName, 2, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        afterMethod(className, methodName, parameterCount = 2) {
            gameEnhancementRuntime.mergeWideVisionKnownGames(instanceOrNull)
        }
    }

    private fun HookScope.installVibrationSupportHooks() {
        val className = "com.zui.game.service.vibrate.VibrationToolKt"
        val methodName = "isGameSupport4dVibration"
        if (!hasMethodWithParamCount(className, methodName, 2, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        replaceMethod(className, methodName, parameterCount = 2) {
            val context = args.firstOrNull() as? Context
            val packageName = args.getOrNull(1) as? String
            val original = runCatching {
                @Suppress("UNCHECKED_CAST")
                (callOriginal() as? List<Any?>) ?: emptyList()
            }.getOrElse {
                AndroidInternals.log("Failed to call original $className#$methodName", it)
                emptyList()
            }

            if (context == null) {
                return@replaceMethod original.mapNotNull { it as? String }
            }

            gameEnhancementRuntime.mergeVibrationSupportKeys(context, packageName, original)
        }
    }

    private fun HookScope.hookAiSoundItemInitialization() {
        val className = "com.zui.game.service.sys.item.ItemAISoundEnhancement"
        val methodName = "initFromSavedState"
        if (!hasMethodWithParamCount(className, methodName, 2, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        afterMethod(className, methodName, parameterCount = 2) {
            val context = args.firstOrNull() as? Context ?: return@afterMethod
            val packageName = args.getOrNull(1) as? String ?: return@afterMethod
            if (!aiSoundRuntime.isSupportedPackage(packageName)) return@afterMethod

            val enabled = aiSoundRuntime.readSetting(context, defaultValue = 1) == 1
            aiSoundRuntime.applyState(instanceOrNull, context, enabled)
            result = if (enabled) 0 else 1
        }
    }

    private fun HookScope.hookAiSoundToggleHandler() {
        val className = "com.zui.game.service.sys.item.ItemAISoundEnhancement\$initFromSavedState\$1"
        if (!hasMethodWithParamCount(className, "onNoClick", 0, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#onNoClick/0 in Game Helper")
            return
        }

        replaceMethod(className, "onNoClick") {
            val packageName = aiSoundRuntime.resolveCallbackPackage(instanceOrNull)
                ?: return@replaceMethod callOriginal()
            if (!aiSoundRuntime.isSupportedPackage(packageName)) {
                return@replaceMethod callOriginal()
            }

            val context = aiSoundRuntime.resolveCallbackContext(instanceOrNull)
                ?: return@replaceMethod callOriginal()
            val item = aiSoundRuntime.resolveCallbackItem(instanceOrNull)
                ?: return@replaceMethod callOriginal()
            aiSoundRuntime.toggle(item, context)
            null
        }

        replaceMethod(className, "onToast") {
            val packageName = aiSoundRuntime.resolveCallbackPackage(instanceOrNull)
            if (packageName != null && aiSoundRuntime.isSupportedPackage(packageName)) {
                return@replaceMethod null
            }
            callOriginal()
        }
    }

    private fun HookScope.hookAiSoundFloatingNotice() {
        val className = "com.zui.game.service.ui.FloatingGameNoticController"
        val methodName = "checkAISoundEnhancementEnable"
        if (!hasMethodWithParamCount(className, methodName, 1, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/1 in Game Helper")
            return
        }

        replaceMethod(className, methodName, parameterCount = 1) {
            val packageName = args.firstOrNull() as? String ?: return@replaceMethod callOriginal()
            if (!aiSoundRuntime.isSupportedPackage(packageName)) {
                return@replaceMethod callOriginal()
            }

            val context = runCatching {
                ReflectCompat.getObjectField(instanceOrNull, "mContext") as? Context
            }.getOrNull() ?: return@replaceMethod callOriginal()

            aiSoundRuntime.readSetting(context, defaultValue = 1) == 1 && aiSoundRuntime.isFeatureOpened()
        }
    }

    private fun isBaldurBoard(): Boolean {
        return AndroidInternals.isBaldurBoard()
    }

    private fun resolveGameHelperClassLoader(): ClassLoader? {
        return cachedGameHelperClassLoader
            ?: lsrRuntime.resolveProcessApplicationContext()?.javaClass?.classLoader
    }

    private fun hasMethodWithParamCount(
        className: String,
        methodName: String,
        paramCount: Int,
        classLoader: ClassLoader?,
    ): Boolean {
        return runCatching {
            val resolvedClassLoader = classLoader
                ?: lsrRuntime.resolveProcessApplicationContext()?.javaClass?.classLoader
                ?: Thread.currentThread().contextClassLoader

            ReflectCompat.findClass(className, resolvedClassLoader).declaredMethods.any {
                it.name == methodName && it.parameterTypes.size == paramCount
            }
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // ServiceManager fallback: intercept getService / checkService so callers
    // can still obtain lenovosr when SELinux blocks addService().
    // -------------------------------------------------------------------------

    private fun HookScope.installLsrServiceManagerFallback() {
        afterMethod("android.os.ServiceManager", "getService", String::class.java) {
            result = lsrRuntime.resolveServiceManagerResult("getService", args.firstOrNull(), result)
        }
        afterMethod("android.os.ServiceManager", "checkService", String::class.java) {
            result = lsrRuntime.resolveServiceManagerResult("checkService", args.firstOrNull(), result)
        }
    }
}
