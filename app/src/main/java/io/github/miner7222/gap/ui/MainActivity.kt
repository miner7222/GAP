package io.github.miner7222.gap.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.miner7222.gap.BuildConfig
import io.github.miner7222.gap.R
import io.github.miner7222.gap.SupportedPackageList
import io.github.miner7222.gap.databinding.ActivityMainBinding
import java.util.LinkedHashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PackageListAdapter
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var baselinePackages: Set<String> = emptySet()
    private var entries: List<PackageEntry> = emptyList()
    private var selectedPackages = LinkedHashSet<String>()
    private var currentQuery = ""
    private var showNotInstalledPackages = false
    private var shownUpdateTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The layout draws edge-to-edge, so the activity owns status/navigation
        // bar padding instead of relying on a theme with fixed system insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

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
        binding.packageList.layoutManager = LinearLayoutManager(this)
        binding.packageList.adapter = adapter
        binding.searchInput.doAfterTextChanged {
            currentQuery = it?.toString().orEmpty()
            applyFilter()
        }
        binding.resetButton.setOnClickListener { resetToBaseline() }
        binding.saveButton.setOnClickListener { saveSelection() }
        installSystemBarInsets()

        requestRootAndLoadPackages()
        if (savedInstanceState == null) {
            checkForUpdates()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestRootAndLoadPackages() {
        setBusy(true)
        executor.execute {
            val hasRootAccess = runCatching { RootShell.hasAccess() }.getOrDefault(false)
            runOnUiThread {
                if (hasRootAccess) {
                    loadPackages()
                } else {
                    setBusy(false)
                    showRootAccessDialog()
                }
            }
        }
    }

    private fun checkForUpdates() {
        executor.execute {
            val release = UpdateChecker.fetchLatestRelease() ?: return@execute
            if (!UpdateChecker.isNewerRelease(release.tagName, BuildConfig.VERSION_NAME)) {
                return@execute
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || shownUpdateTag == release.tagName) {
                    return@runOnUiThread
                }
                shownUpdateTag = release.tagName
                showUpdateDialog(release)
            }
        }
    }

    private fun loadPackages() {
        setBusy(true)
        executor.execute {
            runCatching {
                PackageManagerController.loadEntries(this)
            }.onSuccess { (baseline, loadedEntries) ->
                runOnUiThread {
                    baselinePackages = baseline
                    entries = loadedEntries
                    selectedPackages = LinkedHashSet(
                        loadedEntries
                            .asSequence()
                            .filter { it.selected }
                            .map { it.packageName }
                            .toList(),
                    )
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

            if (query.isBlank()) {
                return@filter true
            }

            entry.label.lowercase().contains(query) || entry.packageName.lowercase().contains(query)
        }

        adapter.submitList(filtered)
        binding.emptyState.isVisible = filtered.isEmpty()
        updateSummary()
    }

    private fun handleToolbarMenuItem(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_show_not_installed -> {
                showNotInstalledPackages = !showNotInstalledPackages
                binding.toolbar.menu.findItem(R.id.action_show_not_installed)?.isChecked = showNotInstalledPackages
                applyFilter()
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
            WindowInsetsControllerCompat(window, binding.root).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
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
        val selectedCount = selectedPackages.size
        val baselineCount = baselinePackages.size
        binding.summary.text = getString(R.string.selection_summary, selectedCount, baselineCount)
        binding.saveButton.isEnabled = !binding.progressIndicator.isVisible
        binding.resetButton.isEnabled = !binding.progressIndicator.isVisible && selectedPackages != baselinePackages
    }

    private fun setBusy(isBusy: Boolean) {
        binding.progressIndicator.isVisible = isBusy
        binding.saveButton.isEnabled = !isBusy
        binding.resetButton.isEnabled = !isBusy && selectedPackages != baselinePackages
        binding.searchLayout.isEnabled = !isBusy
    }

    private fun showRootAccessDialog() {
        AlertDialog.Builder(this)
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

    private fun showUpdateDialog(release: ReleaseInfo) {
        val changelog = release.body.ifBlank { getString(R.string.update_no_changes) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, release.tagName))
            .setMessage(
                getString(
                    R.string.update_available_message,
                    BuildConfig.VERSION_NAME,
                    release.tagName,
                    changelog,
                ),
            )
            .setPositiveButton(R.string.update_open_github) { _, _ ->
                openGitHubReleases()
            }
            .setNegativeButton(R.string.update_no_button, null)
            .show()
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
