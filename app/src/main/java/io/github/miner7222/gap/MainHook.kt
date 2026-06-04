package io.github.miner7222.gap

import android.content.Context
import android.content.res.Resources
import android.os.IBinder
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import com.zui.server.lsr.LsrService
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private companion object {
        private const val TAG = "GAP"
        private const val GAME_HELPER_PACKAGE = "com.zui.game.service"
        private const val GAME_RESOLUTION_APPS_ARRAY = "game_resolution_apps"
    }

    private val systemHooksInstalled = AtomicBoolean(false)
    private val gameHooksInstalled = AtomicBoolean(false)
    @Volatile
    private var systemContext: Context? = null
    @Volatile
    private var fallbackLsrBinder: IBinder? = null
    @Volatile
    private var cachedGameHelperClassLoader: ClassLoader? = null
    private val superResolutionRuntime = SuperResolutionRuntime(
        resolveSystemContext = ::resolveSystemContext,
        resolveProcessApplicationContext = ::resolveProcessApplicationContext,
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
    private val romFeatureRuntime = RomFeatureRuntime(
        resolveGameHelperClassLoader = ::resolveGameHelperClassLoader,
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
                ensureLsrRegistered()
            }
            afterMethod("com.android.server.SystemServer", "startCoreServices", parameterCount = 1) {
                ensureLsrRegistered()
            }
            afterMethod("com.android.server.SystemServer", "startOtherServices", parameterCount = 1) {
                ensureLsrRegistered()
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
        installRomFeatureHooks()
        // Settings uses a separate feature registry, so patch it independently.
        installGameSettingFeatureHooks()
        // Keep SR device support enabled while swapping the stock
        // whitelist inputs to the active gpp_app_list view.
        installSuperResolutionAvailabilityHooks()
        // Replace the stock SR whitelist lookups with the active gpp_app_list view.
        installSuperResolutionSupportHooks()
        // Normalize AI sound package gating so both PUBG package names work
        // regardless of the device's ROW region flag.
        installAiSoundEnhancementHooks()
        // Wide Vision and 4D vibration still keep separate regional lists in
        // stock Game Helper, so merge them at runtime.
        installWideVisionHooks()
        installVibrationSupportHooks()

        AndroidInternals.log("Installed modern Xposed game helper hooks")
    }

    private fun HookScope.installRomFeatureHooks() {
        replaceMethod("com.zui.game.service.RomFeatures", "isFeatureOpen", String::class.java) {
            romFeatureRuntime.resolveFeatureOpen(args.firstOrNull() as? String) {
                callOriginal()
            }
        }

        afterMethod("com.zui.game.service.RomFeatures", "getKeyList") {
            romFeatureRuntime.patchRomFeatureKeyList(instanceOrNull)
            result = romFeatureRuntime.readRomFeatureKeyList(instanceOrNull) ?: result
        }

        replaceMethod(
            "com.zui.game.service.sys.item.KeyContainer",
            "isFeatureOpened",
            parameterCount = 1,
        ) {
            romFeatureRuntime.resolveKeyContainerFeatureOpen(instanceOrNull) {
                callOriginal()
            }
        }

        beforeMethod(
            "com.zui.game.service.FeatureKey\$Companion",
            "createByKeys",
            Array<String>::class.java,
        ) {
            val keys = (args.firstOrNull() as? Array<*>)?.mapNotNull { it as? String } ?: return@beforeMethod
            val normalized = romFeatureRuntime.normalizeFeatureKeys(keys)
            if (keys.size != normalized.size || keys.toList() != normalized.toList()) {
                AndroidInternals.log("Normalized FeatureKey list from ${keys.size} to ${normalized.size}")
            }
            args[0] = normalized
        }

        romFeatureRuntime.patchExistingRomFeatureSets()
    }

    private fun HookScope.installGameSettingFeatureHooks() {
        replaceMethod("com.zui.ugame.gamesetting.feature.FeatureList", "list") {
            val original = runCatching {
                @Suppress("UNCHECKED_CAST")
                callOriginal() as? List<Any?>
            }.getOrNull() ?: emptyList()
            romFeatureRuntime.normalizeGameSettingFeatureList(original)
        }

        // The settings screen also keeps a static XML entry, so remove it after inflation.
        afterMethod(
            "com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtension",
            "onCreatePreferences",
            parameterCount = 2,
        ) {
            romFeatureRuntime.removeColorfulLightPreference(instanceOrNull)
        }
        afterMethod("com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtension", "onResume") {
            romFeatureRuntime.removeColorfulLightPreference(instanceOrNull)
        }

        // Backstop the ViewModel cache in case it was built before FeatureList.list() was filtered.
        replaceMethod(
            "com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtensionViewModel",
            "getFeatureList",
        ) {
            val original = runCatching {
                @Suppress("UNCHECKED_CAST")
                callOriginal() as? List<Any?>
            }.getOrNull() ?: emptyList()
            romFeatureRuntime.normalizeGameSettingFeatureList(original)
        }

        replaceMethodWithTrue(
            "com.zui.ugame.gamesetting.feature.FEATURE_SUPER_RESOLUTION",
            "isEnable",
            Context::class.java,
        )

        replaceMethod("com.zui.ugame.gamesetting.feature.FEATURE_COLORFUL_LIGHT", "isEnable", Context::class.java) {
            isBaldurBoard()
        }
        replaceMethod(
            "com.zui.ugame.gamesetting.feature.FEATURE_COLORFUL_LIGHT",
            "onPreferenceTreeClick",
            parameterCount = 3,
        ) {
            if (isBaldurBoard()) callOriginal() else false
        }
    }

    private fun HookScope.installSuperResolutionAvailabilityHooks() {
        replaceMethodWithTrue("com.zui.game.service.di.Settings", "getSupportSuperResolution")

        installSuperResolutionResourceHooks()

        // Backstop the rendered list so stale SR buttons cannot survive after
        // the runtime whitelist changes.
        afterMethod(
            "com.zui.game.service.ui.GameHelperViewController\$getCurrentView\$1\$1",
            "emit",
            parameterCount = 2,
        ) {
            val controller = runCatching {
                ReflectCompat.getObjectField(instanceOrNull, "this\$0")
            }.getOrNull()
            floatingBarRuntime.normalizeItems(controller, "getCurrentView collector")
            // If the LiveData was empty (list not populated yet), schedule a
            // retry after the current message finishes so the original code
            // has a chance to fill the list first.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                floatingBarRuntime.normalizeItems(controller, "getCurrentView collector (deferred)")
            }
        }
    }

    private fun HookScope.installSuperResolutionResourceHooks() {
        replaceMethod("android.content.res.Resources", "getStringArray", Int::class.javaPrimitiveType!!) {
            val resources = instanceOrNull as? Resources ?: return@replaceMethod callOriginal()
            val resId = args.firstOrNull() as? Int ?: return@replaceMethod callOriginal()
            val entryName = runCatching { resources.getResourceEntryName(resId) }.getOrNull()
            val packageName = runCatching { resources.getResourcePackageName(resId) }.getOrNull()

            if (entryName != GAME_RESOLUTION_APPS_ARRAY || packageName != GAME_HELPER_PACKAGE) {
                return@replaceMethod callOriginal()
            }

            val originalEntries = runCatching {
                (callOriginal() as? Array<*>)
                    ?.mapNotNull { it as? String }
                    ?.toTypedArray()
            }.getOrElse {
                AndroidInternals.log("Failed to read stock $GAME_RESOLUTION_APPS_ARRAY entries", it)
                null
            } ?: return@replaceMethod emptyArray<String>()

            val overriddenEntries = superResolutionRuntime.resolveArrayEntries(originalEntries)
            if (overriddenEntries != null) {
                AndroidInternals.log(
                    "Replaced $GAME_RESOLUTION_APPS_ARRAY with ${overriddenEntries.size} runtime entries",
                )
                overriddenEntries
            } else {
                originalEntries
            }
        }
    }

    private fun HookScope.installSuperResolutionSupportHooks() {
        hookSuperResolutionSupportMethod(
            "com.zui.ugame.gamesetting.data.RepositoryImpl",
            "querySuperResolutionSupportPackage",
        )
        hookSuperResolutionSupportMethod(
            "com.zui.ugame.gamesetting.data.source.PreDownloadSourceImpl",
            "querySuperResolutionSupportPackage",
        )
        hookSuperResolutionSupportMethod(
            "com.zui.game.service.util.ConstValueKt",
            "getSuperResolutionSupportPackages",
        )
        hookSuperResolutionSupportMethod(
            "com.zui.game.service.util.ConstValueKt",
            "getSuperResolutionSupportPackagesForAll",
        )
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

    private fun HookScope.hookSuperResolutionSupportMethod(className: String, methodName: String) {
        replaceMethod(className, methodName) {
            superResolutionRuntime.resolveRegisteredPackagesOrOriginal("$className#$methodName") {
                callOriginal()
            }
        }
    }

    private fun isBaldurBoard(): Boolean {
        return AndroidInternals.isBaldurBoard()
    }

    private fun resolveGameHelperClassLoader(): ClassLoader? {
        return cachedGameHelperClassLoader
            ?: resolveProcessApplicationContext()?.javaClass?.classLoader
    }

    private fun hasMethodWithParamCount(
        className: String,
        methodName: String,
        paramCount: Int,
        classLoader: ClassLoader?,
    ): Boolean {
        return runCatching {
            val resolvedClassLoader = classLoader
                ?: resolveProcessApplicationContext()?.javaClass?.classLoader
                ?: Thread.currentThread().contextClassLoader

            ReflectCompat.findClass(className, resolvedClassLoader).declaredMethods.any {
                it.name == methodName && it.parameterTypes.size == paramCount
            }
        }.getOrDefault(false)
    }

    private fun resolveProcessApplicationContext(): Context? {
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

    private fun resolveSystemContext(instance: Any? = null): Context? {
        (instance?.let {
            runCatching { ReflectCompat.getObjectField(it, "mContext") as? Context }.getOrNull()
        } ?: systemContext)?.let { context ->
            rememberSystemContext(context)
            return context
        }
        return resolveProcessApplicationContext()?.also(::rememberSystemContext)
    }

    private fun rememberSystemContext(context: Context) {
        if (systemContext == null) {
            systemContext = context.applicationContext ?: context
        }
    }

    private fun HookCall.ensureLsrRegistered() {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return
        }
        runCatching {
            val systemServer = instance<Any>()
            val context = ReflectCompat.getObjectField(systemServer, "mSystemContext") as? Context ?: return
            rememberSystemContext(context)
            LsrServiceRegistry.ensureRegistered(context)
        }.onFailure {
            AndroidInternals.log("Failed to register lenovosr from modern Xposed hook", it)
        }
    }

    // -------------------------------------------------------------------------
    // ServiceManager fallback: intercept getService / checkService so callers
    // can still obtain lenovosr when SELinux blocks addService().
    // -------------------------------------------------------------------------

    private fun HookScope.installLsrServiceManagerFallback() {
        afterMethod("android.os.ServiceManager", "getService", String::class.java) {
            val svcName = args.firstOrNull()
            if (svcName == LsrService.LSR_SERVICE) {
                if (result == null) {
                    val binder = getOrCreateFallbackLsrBinder()
                    result = binder
                    AndroidInternals.log("ServiceManager.getService(lenovosr) → fallback binder=$binder")
                } else {
                    AndroidInternals.log("ServiceManager.getService(lenovosr) → original result=$result")
                }
            }
        }
        afterMethod("android.os.ServiceManager", "checkService", String::class.java) {
            val svcName = args.firstOrNull()
            if (svcName == LsrService.LSR_SERVICE) {
                if (result == null) {
                    val binder = getOrCreateFallbackLsrBinder()
                    result = binder
                    AndroidInternals.log("ServiceManager.checkService(lenovosr) → fallback binder=$binder")
                } else {
                    AndroidInternals.log("ServiceManager.checkService(lenovosr) → original result=$result")
                }
            }
        }
    }

    private fun getOrCreateFallbackLsrBinder(): IBinder? {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return null
        }
        // In system_server the binder that failed to register is retained by
        // LsrServiceRegistry.
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
