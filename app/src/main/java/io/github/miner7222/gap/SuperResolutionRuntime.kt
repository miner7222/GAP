package io.github.miner7222.gap

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.LinkedHashSet

internal class SuperResolutionRuntime(
    private val resolveSystemContext: () -> Context?,
    private val resolveProcessApplicationContext: () -> Context?,
) {
    @Volatile
    private var cachedPackages: Set<String>? = null
    @Volatile
    private var cachedSignature: String? = null

    fun resolveRegisteredPackagesOrOriginal(source: String, callOriginal: () -> Any?): Any? {
        val packages = resolveSupportedPackages()
        if (packages != null) {
            AndroidInternals.log("Resolved ${packages.size} SR whitelist packages for $source")
            return packages
        }

        AndroidInternals.log("Falling back to original super resolution package list for $source")
        return runCatching(callOriginal).getOrElse {
            AndroidInternals.log("Failed to call original method for $source", it)
            emptyList<String>()
        }
    }

    fun resolveArrayEntries(originalEntries: Array<String>): Array<String>? {
        val packages = resolveSupportedPackages() ?: return null
        val originalByPackage = LinkedHashMap<String, String>(originalEntries.size)

        originalEntries.forEach { entry ->
            val packageName = entry.substringBefore('#').trim()
            if (packageName.isNotBlank()) {
                originalByPackage[packageName] = entry
            }
        }

        val normalized = LinkedHashSet<String>(packages.size)
        packages.forEach { packageName ->
            if (packageName.isBlank()) return@forEach
            normalized += originalByPackage[packageName]
                ?: "$packageName$DEFAULT_SUPER_RESOLUTION_ARRAY_FLAGS"
        }

        return normalized.toTypedArray()
    }

    fun shouldExpose(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == GAME_HELPER_PACKAGE) {
            return false
        }

        val packages = resolveSupportedPackages() ?: return true
        return packageName in packages
    }

    private fun resolveSupportedPackages(): List<String>? {
        return runCatching {
            readSupportedPackagesFromSettings()?.let { packages ->
                val signature = "settings:${packages.joinToString(",")}"
                val cached = cachedPackages
                if (cached != null && cachedSignature == signature) {
                    return@runCatching ArrayList(cached)
                }

                cachedPackages = LinkedHashSet(packages)
                cachedSignature = signature
                return@runCatching packages
            }

            val whitelistFile = File(SupportedPackageList.ACTIVE_LIST_PATH)
            val signature = buildString {
                append("file:")
                append(whitelistFile.exists())
                append(':')
                append(whitelistFile.length())
                append(':')
                append(whitelistFile.lastModified())
            }
            val cached = cachedPackages
            if (cached != null && cachedSignature == signature) {
                return@runCatching ArrayList(cached)
            }

            val packages = SupportedPackageList.readPackages(whitelistFile)
                .filterTo(ArrayList()) { packageName ->
                    packageName.isNotBlank() && packageName != GAME_HELPER_PACKAGE
                }
            if (packages.isEmpty()) {
                cachedPackages = null
                cachedSignature = null
                return@runCatching null
            }

            cachedPackages = LinkedHashSet(packages)
            cachedSignature = signature
            packages
        }.getOrElse {
            AndroidInternals.log("Failed to resolve SR whitelist packages", it)
            null
        }
    }

    private fun readSupportedPackagesFromSettings(): List<String>? {
        val context = resolveSystemContext() ?: resolveProcessApplicationContext() ?: return null
        val rawValue = runCatching {
            Settings.Global.getString(context.contentResolver, SupportedPackageList.RUNTIME_SETTINGS_KEY)
        }.getOrElse {
            AndroidInternals.log("Failed to read runtime SR whitelist from Settings.Global", it)
            null
        } ?: return null

        return SupportedPackageList.parsePackages(rawValue)
            .filterTo(ArrayList()) { packageName ->
                packageName.isNotBlank() && packageName != GAME_HELPER_PACKAGE
            }
    }

    private companion object {
        private const val GAME_HELPER_PACKAGE = "com.zui.game.service"
        private const val DEFAULT_SUPER_RESOLUTION_ARRAY_FLAGS = "#1#1"
    }
}
