package com.abuzahra.admin.ui.dashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.abuzahra.admin.R
import com.abuzahra.admin.data.api.Result
import com.abuzahra.admin.data.model.Device
import com.abuzahra.admin.databinding.ActivityDashboardBinding
import com.abuzahra.admin.ui.device.DeviceDetailActivity
import com.abuzahra.admin.ui.files.FilesActivity
import com.abuzahra.admin.ui.logs.LogsActivity
import com.abuzahra.admin.ui.login.LoginActivity
import com.abuzahra.admin.ui.settings.SettingsActivity
import com.abuzahra.admin.util.Preferences
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(Preferences.getInstance(this))
    }
    private val deviceAdapter: DeviceAdapter by lazy {
        DeviceAdapter { device -> navigateToDeviceDetail(device) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupFilters()
        setupBottomNav()
        observeViewModel()
        handleBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logout -> {
                showLogoutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.dashboard)

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@DashboardActivity)
            adapter = deviceAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.setSearchQuery(binding.etSearch.text.toString().trim())
                true
            } else {
                false
            }
        }
        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            viewModel.setSearchQuery(text.toString().trim())
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener {
            viewModel.setFilter(DashboardViewModel.FilterType.ALL)
        }
        binding.chipOnline.setOnClickListener {
            viewModel.setFilter(DashboardViewModel.FilterType.ONLINE)
        }
        binding.chipOffline.setOnClickListener {
            viewModel.setFilter(DashboardViewModel.FilterType.OFFLINE)
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> true
                R.id.nav_files -> {
                    startActivity(Intent(this, FilesActivity::class.java))
                    false
                }
                R.id.nav_logs -> {
                    startActivity(Intent(this, LogsActivity::class.java))
                    false
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> false
            }
        }
    }

    private fun observeViewModel() {
        viewModel.devices.observe(this) { result ->
            binding.loadingOverlay.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            when (result) {
                is Result.Loading -> {
                    binding.loadingOverlay.visibility = View.VISIBLE
                }
                is Result.Success -> {
                    val devices = result.data
                    deviceAdapter.submitList(devices)
                    updateEmptyState(devices.isEmpty())
                }
                is Result.Error -> {
                    updateEmptyState(true)
                    if (result.code == 401) {
                        showSessionExpired()
                    } else {
                        Snackbar.make(
                            binding.coordinator,
                            result.message,
                            Snackbar.LENGTH_LONG
                        ).setAction(R.string.retry) { viewModel.refresh() }.show()
                    }
                }
            }
        }

        viewModel.stats.observe(this) { result ->
            if (result is Result.Success) {
                binding.tvTotalDevices.text = result.data.devicesCount.toString()
                binding.tvOnlineDevices.text = result.data.onlineCount.toString()
                binding.tvOfflineDevices.text = result.data.offlineCount.toString()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun navigateToDeviceDetail(device: Device) {
        startActivity(DeviceDetailActivity.newIntent(this, device))
    }

    private fun showLogoutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.logout()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSessionExpired() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.session_expired)
            .setMessage("يرجى تسجيل الدخول مرة أخرى")
            .setPositiveButton(R.string.ok) { _, _ ->
                Preferences.getInstance(this).clear()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun handleBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bottomNav.selectedItemId != R.id.nav_dashboard) {
                    binding.bottomNav.selectedItemId = R.id.nav_dashboard
                } else {
                    showLogoutDialog()
                }
            }
        })
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, DashboardActivity::class.java)
        }
    }
}

class DashboardViewModelFactory(private val preferences: Preferences) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(preferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}