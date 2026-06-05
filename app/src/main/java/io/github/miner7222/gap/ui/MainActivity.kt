package io.github.miner7222.gap.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.miner7222.gap.BuildConfig
import io.github.miner7222.gap.DeviceCompatibility
import io.github.miner7222.gap.DeviceCompatibility.CompatibilityStatus
import io.github.miner7222.gap.GapApplication
import io.github.miner7222.gap.R
import io.github.miner7222.gap.SupportedPackageList
import io.github.miner7222.gap.XposedServiceBannerState
import io.github.miner7222.gap.XposedServiceBannerPresenter
import io.github.miner7222.gap.XposedServiceState
import io.github.miner7222.gap.databinding.ActivityMainBinding
import io.github.miner7222.gap.databinding.DialogAboutBinding
import java.util.LinkedHashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), GapApplication.XposedServiceStateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PackageListAdapter
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var baselinePackages: Set<String> = emptySet()
    private var entries: List<PackageEntry> = emptyList()
    private var visibleEntries: List<PackageEntry> = emptyList()
    private var selectedPackages = LinkedHashSet<String>()
    private var currentQuery = ""
    private var showNotInstalledPackages = false
    private var showSystemApps = false
    private var shownUpdateTag: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode(readSavedThemeMode())
        super.onCreate(savedInstanceState)
        if (!checkDeviceCompatibility()) {
            return
        }

        // The layout draws edge-to-edge, so the activity owns status/navigation
        // bar padding instead of relying on a theme with fixed system insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAppearance()

        adapter = PackageListAdapter(packageManager) { entry ->
            if (entry.selected) {
                selectedPackages += entry.packageName
            } else {
                selectedPackages -= entry.packageName
            }
            updateSummary()
        }

        binding.toolbar.title = getString(R.string.app_name)
        binding.toolbar.subtitle = getString(R.string.app_subtitle)
        binding.toolbar.inflateMenu(R.menu.main_actions)
        binding.toolbar.setOnMenuItemClickListener { handleToolbarMenuItem(it.itemId) }
        binding.packageRefresh.setOnRefreshListener { refreshPackages() }
        binding.packageList.layoutManager = LinearLayoutManager(this)
        binding.packageList.adapter = adapter
        binding.searchInput.doAfterTextChanged {
            currentQuery = it?.toString().orEmpty()
            applyFilter()
        }
        binding.saveButton.setOnClickListener { saveSelection() }
        binding.lsposedScopeRequestButton.setOnClickListener {
            (application as? GapApplication)?.requestMissingXposedScopes()
        }
        syncMenuState(isBusy = false)
        installSystemBarInsets()

        requestRootAndLoadPackages()
        if (savedInstanceState == null) {
            checkForUpdates(manual = false)
        }
    }

    override fun onStart() {
        super.onStart()
        GapApplication.addXposedServiceStateListener(this, notifyImmediately = true)
    }

    override fun onStop() {
        GapApplication.removeXposedServiceStateListener(this)
        super.onStop()
    }

    private fun checkDeviceCompatibility(): Boolean {
        val status = DeviceCompatibility.evaluate(
            AppSystemProperties.get(DeviceCompatibility.ZUI_VERSION_PROPERTY),
            AppSystemProperties.get(DeviceCompatibility.SOC_MODEL_PROPERTY),
        )
        val messageRes = when (status) {
            CompatibilityStatus.SUPPORTED -> return true
            CompatibilityStatus.UNSUPPORTED_OS_VERSION -> R.string.unsupported_os_version
            CompatibilityStatus.UNSUPPORTED_SOC -> R.string.unsupported_soc
        }
        showToast(getString(messageRes))
        finish()
        return false
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onXposedServiceStateChanged(state: XposedServiceState) {
        runOnUiThread {
            if (!::binding.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            renderLsposedBanner(XposedServiceBannerPresenter.resolve(state))
        }
    }

    private fun requestRootAndLoadPackages() {
        setBusy(true)
        executor.execute {
            val hasRootAccess = runCatching { RootShell.hasAccess() }.getOrDefault(false)
            val moduleInstalled = if (hasRootAccess) {
                runCatching { PackageManagerController.isLsrPortModuleInstalled() }
            } else {
                null
            }
            runOnUiThread {
                if (!hasRootAccess) {
                    setBusy(false)
                    showRootAccessDialog()
                    return@runOnUiThread
                }

                if (moduleInstalled?.getOrDefault(false) == true) {
                    loadPackages()
                } else {
                    setBusy(false)
                    showLsrPortMissingDialog()
                }
            }
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        executor.execute {
            val release = UpdateChecker.fetchLatestRelease()
            if (release == null) {
                if (manual) {
                    runOnUiThread {
                        showToast(getString(R.string.update_check_failed))
                    }
                }
                return@execute
            }

            if (!UpdateChecker.isNewerRelease(release.tagName, BuildConfig.VERSION_NAME)) {
                if (manual) {
                    runOnUiThread {
                        showToast(getString(R.string.update_check_no_update))
                    }
                }
                return@execute
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (!manual && shownUpdateTag == release.tagName) {
                    return@runOnUiThread
                }
                shownUpdateTag = release.tagName
                showUpdateDialog(release)
            }
        }
    }

    private fun refreshPackages() {
        if (busy) {
            binding.packageRefresh.isRefreshing = false
            return
        }
        loadPackages(showRefreshSpinner = true, preserveSelection = true)
    }

    private fun loadPackages(showRefreshSpinner: Boolean = false, preserveSelection: Boolean = false) {
        val retainedSelection = if (preserveSelection) LinkedHashSet(selectedPackages) else null
        setBusy(true, showRefreshSpinner)
        executor.execute {
            runCatching {
                PackageManagerController.loadEntries(this)
            }.onSuccess { (baseline, loadedEntries) ->
                runOnUiThread {
                    baselinePackages = baseline
                    entries = loadedEntries
                    if (retainedSelection != null) {
                        val loadedPackageNames = loadedEntries.mapTo(LinkedHashSet()) { it.packageName }
                        selectedPackages = LinkedHashSet(
                            retainedSelection.filter { packageName -> loadedPackageNames.contains(packageName) },
                        )
                        entries.forEach { entry ->
                            entry.selected = selectedPackages.contains(entry.packageName)
                        }
                    } else {
                        selectedPackages = LinkedHashSet(
                            loadedEntries
                                .asSequence()
                                .filter { it.selected }
                                .map { it.packageName }
                                .toList(),
                        )
                    }
                    applyFilter()
                    setBusy(false)
                }
            }.onFailure { error ->
                runOnUiThread {
                    setBusy(false)
                    showToast(
                        getString(
                            R.string.load_failed_message,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun applyFilter() {
        val query = SupportedPackageList.normalizeQuery(currentQuery)
        val filtered = entries.filter { entry ->
            // Hidden by default so the list behaves like a launcher picker, but
            // still allow explicit inspection of saved packages that are missing.
            if (!showNotInstalledPackages && !entry.installed) {
                return@filter false
            }

            if (!showSystemApps && entry.systemApp) {
                return@filter false
            }

            if (query.isBlank()) {
                return@filter true
            }

            entry.label.lowercase().contains(query) || entry.packageName.lowercase().contains(query)
        }

        visibleEntries = filtered
        adapter.submitList(filtered)
        binding.emptyState.isVisible = filtered.isEmpty()
        updateSummary()
    }

    private fun handleToolbarMenuItem(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_refresh_packages -> {
                refreshPackages()
                true
            }

            R.id.action_show_not_installed -> {
                showNotInstalledPackages = !showNotInstalledPackages
                syncMenuState(isBusy = busy)
                applyFilter()
                true
            }

            R.id.action_show_system_apps -> {
                showSystemApps = !showSystemApps
                syncMenuState(isBusy = busy)
                applyFilter()
                true
            }

            R.id.action_restore_defaults -> {
                resetToBaseline()
                true
            }

            R.id.action_theme -> {
                showThemeDialog()
                true
            }

            R.id.action_about -> {
                showAboutDialog()
                true
            }

            else -> false
        }
    }

    private fun installSystemBarInsets() {
        val toolbarTopPadding = binding.toolbar.paddingTop
        val buttonBarBottomPadding = binding.buttonBar.paddingBottom
        val packageListBottomPadding = binding.packageList.paddingBottom
        val emptyStateBottomPadding = binding.emptyState.paddingBottom

        // Keep the action bar and bottom buttons above the system bars while
        // letting the RecyclerView continue underneath for edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = toolbarTopPadding + systemBars.top)
            binding.buttonBar.updatePadding(bottom = buttonBarBottomPadding + systemBars.bottom)
            binding.packageList.updatePadding(bottom = packageListBottomPadding + systemBars.bottom)
            binding.emptyState.updatePadding(bottom = emptyStateBottomPadding + systemBars.bottom)
            applySystemBarAppearance()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applySystemBarAppearance() {
        val isLightTheme = (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ) != Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = isLightTheme
            isAppearanceLightNavigationBars = isLightTheme
        }
    }

    private fun resetToBaseline() {
        // Baseline means "what the device would use without GAP's runtime override".
        selectedPackages = LinkedHashSet(baselinePackages)
        entries.forEach { entry ->
            entry.selected = selectedPackages.contains(entry.packageName)
        }
        applyFilter()
    }

    private fun saveSelection() {
        // Saving can rewrite the runtime whitelist and restart services, so keep
        // the work on the background executor and only publish the result on UI.
        val packagesToSave = LinkedHashSet(selectedPackages)
        setBusy(true)
        executor.execute {
            runCatching {
                PackageManagerController.saveSelection(this, packagesToSave)
            }.onSuccess { result ->
                runOnUiThread {
                    setBusy(false)
                    val messageRes = if (result.overlayEnabled) {
                        R.string.save_success_custom
                    } else {
                        R.string.save_success_default
                    }
                    showToast(getString(messageRes))
                }
            }.onFailure { error ->
                runOnUiThread {
                    setBusy(false)
                    showToast(
                        getString(
                            R.string.save_failed_message,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun updateSummary() {
        val selectedCount = visibleEntries.count { it.selected }
        binding.summary.text = getString(R.string.selection_summary, selectedCount)
        binding.saveButton.isEnabled = !busy
        syncMenuState(isBusy = busy)
    }

    private fun setBusy(isBusy: Boolean, showRefreshSpinner: Boolean = false) {
        busy = isBusy
        binding.progressIndicator.isVisible = isBusy && !showRefreshSpinner
        binding.packageRefresh.isRefreshing = isBusy && showRefreshSpinner
        binding.packageRefresh.isEnabled = !isBusy || showRefreshSpinner
        binding.saveButton.isEnabled = !isBusy
        binding.searchLayout.isEnabled = !isBusy
        syncMenuState(isBusy = isBusy)
    }

    private fun renderLsposedBanner(banner: XposedServiceBannerState) {
        when (banner) {
            XposedServiceBannerState.Hidden -> {
                binding.lsposedScopeRequestButton.isVisible = false
                setLsposedBannerVisible(false)
            }

            XposedServiceBannerState.ActivationRequired -> {
                binding.lsposedActivationBannerText.text = getString(R.string.lsposed_module_disabled_banner)
                binding.lsposedScopeRequestButton.isVisible = false
                setLsposedBannerVisible(true)
            }

            is XposedServiceBannerState.MissingScopes -> {
                binding.lsposedActivationBannerText.text =
                    getString(R.string.lsposed_scope_missing_banner, banner.displayScopes)
                binding.lsposedScopeRequestButton.isVisible = true
                setLsposedBannerVisible(true)
            }
        }
    }

    private fun setLsposedBannerVisible(visible: Boolean) {
        if (binding.lsposedActivationBanner.isVisible == visible) return
        val transition = TransitionSet()
            .addTransition(ChangeBounds())
            .addTransition(Fade())
            .setDuration(BANNER_TRANSITION_DURATION_MS)
            .setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
        TransitionManager.beginDelayedTransition(binding.root, transition)
        binding.lsposedActivationBanner.isVisible = visible
    }

    private fun syncMenuState(isBusy: Boolean) {
        binding.toolbar.menu.findItem(R.id.action_show_not_installed)?.apply {
            isChecked = showNotInstalledPackages
            isEnabled = !isBusy
        }
        binding.toolbar.menu.findItem(R.id.action_show_system_apps)?.apply {
            isChecked = showSystemApps
            isEnabled = !isBusy
        }
        binding.toolbar.menu.findItem(R.id.action_restore_defaults)?.isEnabled =
            !isBusy && selectedPackages != baselinePackages
    }

    private fun showRootAccessDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_required_title)
            .setMessage(R.string.root_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.retry_button) { _, _ ->
                requestRootAndLoadPackages()
            }
            .setNegativeButton(R.string.close_button) { _, _ ->
                finish()
            }
            .show()
    }

    private fun showLsrPortMissingDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lsrport_missing_title)
            .setMessage(R.string.lsrport_missing_message)
            .setCancelable(false)
            .setPositiveButton(R.string.download_button) { _, _ ->
                openLsrPortReleases()
            }
            .setNegativeButton(R.string.exit_button) { _, _ ->
                finish()
            }
            .show()
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(
                getString(
                    R.string.update_available_message,
                    BuildConfig.VERSION_NAME,
                    release.tagName,
                ),
            )
            .setPositiveButton(R.string.update_open_github) { _, _ ->
                openGitHubReleases()
            }
            .setNegativeButton(R.string.update_no_button, null)
            .show()
    }

    private fun showThemeDialog() {
        val modes = themeModes()
        val currentMode = readSavedThemeMode()
        val selectedIndex = modes.indexOf(currentMode).takeIf { it >= 0 } ?: 0
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(labels, selectedIndex, null)
            .setNegativeButton(R.string.cancel_button, null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                saveThemeMode(modes[position])
                dialog.dismiss()
                applyThemeMode(modes[position])
            }
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        val aboutBinding = DialogAboutBinding.inflate(layoutInflater)
        aboutBinding.aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        aboutBinding.aboutUpdateCheck.paintFlags =
            aboutBinding.aboutUpdateCheck.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(aboutBinding.root)
            .setPositiveButton(R.string.close_button, null)
            .create()
        aboutBinding.aboutUpdateCheck.setOnClickListener {
            dialog.dismiss()
            checkForUpdates(manual = true)
        }
        aboutBinding.githubButton.setOnClickListener {
            openGitHubRepository()
        }
        dialog.show()
    }

    private fun openGitHubReleases() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_URL))
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) {
                error.printStackTrace()
            }
            showToast(getString(R.string.update_open_failed))
        }
    }

    private fun openGitHubRepository() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPOSITORY_URL))
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) {
                error.printStackTrace()
            }
            showToast(getString(R.string.github_open_failed))
        }
    }

    private fun openLsrPortReleases() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LsrPortModule.RELEASES_URL))
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) {
                error.printStackTrace()
            }
            showToast(getString(R.string.lsrport_download_open_failed))
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun readSavedThemeMode(): Int {
        val mode = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
            .getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        return mode.takeIf { it in themeModes() } ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun saveThemeMode(mode: Int) {
        getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_THEME_MODE, mode)
            .apply()
    }

    private fun applyThemeMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun themeModes(): IntArray {
        return intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )
    }

    companion object {
        private const val UI_PREFS_NAME = "ui_preferences"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val BANNER_TRANSITION_DURATION_MS = 260L
    }
}
