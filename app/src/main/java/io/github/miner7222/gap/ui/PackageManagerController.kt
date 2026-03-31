package io.github.miner7222.gap.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.miner7222.gap.BuildConfig
import io.github.miner7222.gap.SupportedPackageList
import java.io.File
import java.util.LinkedHashSet

data class PackageEntry(
    val label: String,
    val packageName: String,
    val applicationInfo: ApplicationInfo?,
    val installed: Boolean,
    var selected: Boolean,
)

data class SavePackagesResult(
    val overlayEnabled: Boolean,
)

object PackageManagerController {

    private const val PREFS_NAME = "package_manager"
    private const val PREF_BASELINE_PACKAGES = "baseline_packages"

    fun ensureBaselinePackages(context: Context): Set<String> {
        // Cache the device-default list the first time GAP runs so "Reset" can
        // return to stock behavior even after the active list has been overridden.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getStringSet(PREF_BASELINE_PACKAGES, null)?.let { stored ->
            return LinkedHashSet(stored)
        }

        val baseline = SupportedPackageList.readActivePackages()
        prefs.edit().putStringSet(PREF_BASELINE_PACKAGES, baseline).apply()
        return baseline
    }

    fun loadEntries(context: Context): Pair<Set<String>, List<PackageEntry>> {
        val baselinePackages = ensureBaselinePackages(context)
        val activePackages = readSelectedPackages()
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(0)

        val entriesByPackage = linkedMapOf<String, PackageEntry>()
        installedApps
            .asSequence()
            .filter { appInfo -> appInfo.packageName != BuildConfig.APPLICATION_ID }
            // Launcher entries are the useful default, but keep packages that are
            // already active so the user can disable them even if they are hidden
            // or currently uninstalled.
            .filter { appInfo ->
                packageManager.getLaunchIntentForPackage(appInfo.packageName) != null ||
                    activePackages.contains(appInfo.packageName)
            }
            .forEach { appInfo ->
                val packageName = appInfo.packageName
                entriesByPackage[packageName] = PackageEntry(
                    label = appInfo.loadLabel(packageManager).toString().takeIf { it.isNotBlank() } ?: packageName,
                    packageName = packageName,
                    applicationInfo = appInfo,
                    installed = true,
                    selected = activePackages.contains(packageName),
                )
            }

        activePackages.forEach { packageName ->
            if (entriesByPackage.containsKey(packageName)) {
                return@forEach
            }

            entriesByPackage[packageName] = PackageEntry(
                label = packageName,
                packageName = packageName,
                applicationInfo = null,
                installed = false,
                selected = true,
            )
        }

        val sortedEntries = entriesByPackage.values.sortedWith(
            compareByDescending<PackageEntry> { it.selected }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName.lowercase() },
        )

        return baselinePackages to sortedEntries
    }

    private fun readSelectedPackages(): Set<String> {
        // Prefer GAP's runtime override when it exists; otherwise fall back to
        // the device's currently active /system/etc/gpp_app_list.
        val result = RootShell.run(
            """
            if [ -f '${SupportedPackageList.MODULE_LIST_PATH}' ]; then
              cat '${SupportedPackageList.MODULE_LIST_PATH}'
            elif [ -f '${SupportedPackageList.ACTIVE_LIST_PATH}' ]; then
              cat '${SupportedPackageList.ACTIVE_LIST_PATH}'
            fi
            """.trimIndent(),
        )
        if (result.exitCode != 0) {
            throw IllegalStateException(
                result.output.ifBlank { "Could not read active gpp_app_list (exit ${result.exitCode})" },
            )
        }
        return SupportedPackageList.parsePackages(result.output)
    }

    fun saveSelection(context: Context, selectedPackages: Set<String>): SavePackagesResult {
        val baselinePackages = ensureBaselinePackages(context)
        val useOverlay = selectedPackages != baselinePackages
        val listFile = File(context.cacheDir, "gpp_app_list.generated")
        val scriptFile = File(context.cacheDir, "apply_gpp_app_list.sh")

        if (useOverlay) {
            listFile.writeText(SupportedPackageList.buildFileContents(selectedPackages))
        } else if (listFile.exists()) {
            listFile.delete()
        }

        scriptFile.writeText(buildRootScript(listFile.absolutePath, useOverlay))
        scriptFile.setExecutable(true)

        val result = RootShell.run("sh ${scriptFile.absolutePath}")
        if (result.exitCode != 0) {
            throw IllegalStateException(result.output.ifBlank { "Root command failed with exit code ${result.exitCode}" })
        }

        return SavePackagesResult(overlayEnabled = useOverlay)
    }

    private fun buildRootScript(tempFilePath: String, useOverlay: Boolean): String {
        val writeOverlay = if (useOverlay) {
            """
            mkdir -p /data/adb/modules/${SupportedPackageList.MODULE_ID}/system/etc
            cat '${tempFilePath}' > '${SupportedPackageList.MODULE_LIST_PATH}'
            chmod 0644 '${SupportedPackageList.MODULE_LIST_PATH}'
            """.trimIndent()
        } else {
            "rm -f '${SupportedPackageList.MODULE_LIST_PATH}'"
        }

        return """
            |#!/system/bin/sh
            |set -eu
            |
            |if [ ! -d /data/adb/modules/${SupportedPackageList.MODULE_ID} ]; then
            |  echo "Magisk module not found: /data/adb/modules/${SupportedPackageList.MODULE_ID}" >&2
            |  exit 2
            |fi
            |
            |$writeOverlay
            |
            |# service.sh also performs this bind on boot, but GAP reapplies it
            |# immediately so the change takes effect without a reboot.
            |unbind_active_list() {
            |  if grep -q ' /system/etc/gpp_app_list ' /proc/mounts; then
            |    umount /system/etc/gpp_app_list 2>/dev/null || umount -l /system/etc/gpp_app_list 2>/dev/null || true
            |  fi
            |}
            |
            |bind_active_list() {
            |  unbind_active_list
            |  if ! mount -o bind '${SupportedPackageList.MODULE_LIST_PATH}' /system/etc/gpp_app_list 2>/dev/null; then
            |    mount --bind '${SupportedPackageList.MODULE_LIST_PATH}' /system/etc/gpp_app_list
            |  fi
            |}
            |
            |if [ -f '${SupportedPackageList.MODULE_LIST_PATH}' ]; then
            |  bind_active_list
            |else
            |  unbind_active_list
            |fi
            |
            |# Restart the GPP userspace so it re-reads the active whitelist and
            |# startup-only FRC property, then force-stop Game Helper to drop any
            |# cached state tied to the old list.
            |if pidof gppservice >/dev/null 2>&1; then
            |  kill $(pidof gppservice) || true
            |  sleep 1
            |fi
            |
            |start vendor.vppservice 2>/dev/null || true
            |chcon u:object_r:vendor_gppservice_exec:s0 /system/bin/gppservice 2>/dev/null || true
            |setprop vendor.gpp.create_frc_extension 1
            |runcon u:r:vendor_gppservice:s0 /system/bin/gppservice >/dev/null 2>&1 &
            |sleep 1
            |if ! pidof gppservice >/dev/null 2>&1; then
            |  nohup /system/bin/gppservice >/dev/null 2>&1 &
            |fi
            |
            |am force-stop ${SupportedPackageList.GAME_HELPER_PACKAGE} || true
            """.trimMargin()
    }
}

internal data class RootShellResult(
    val exitCode: Int,
    val output: String,
)

internal object RootShell {

    fun hasAccess(): Boolean {
        val result = run("id")
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    internal fun run(command: String): RootShellResult {
        // Keep the helper intentionally tiny: GAP only needs one-shot root shell
        // commands and always captures stdout/stderr together for UI display.
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        return RootShellResult(exitCode = exitCode, output = output)
    }
}
