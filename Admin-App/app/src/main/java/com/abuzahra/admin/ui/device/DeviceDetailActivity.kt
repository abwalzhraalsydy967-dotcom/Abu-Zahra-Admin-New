package com.abuzahra.admin.ui.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.abuzahra.admin.R
import com.abuzahra.admin.data.api.Result
import com.abuzahra.admin.data.model.CommandDefinitions
import com.abuzahra.admin.data.model.Device
import com.abuzahra.admin.data.model.Event
import com.abuzahra.admin.databinding.ActivityDeviceDetailBinding
import com.abuzahra.admin.ui.login.LoginActivity
import com.abuzahra.admin.util.Preferences
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDetailBinding
    private val viewModel: DeviceDetailViewModel by viewModels {
        DeviceDetailViewModelFactory(Preferences.getInstance(this))
    }

    private val commandAdapter: CommandAdapter by lazy {
        CommandAdapter { commandDef ->
            MaterialAlertDialogBuilder(this)
                .setTitle("إرسال أمر")
                .setMessage("هل تريد إرسال أمر: ${commandDef.name}؟")
                .setPositiveButton(R.string.confirm) { _, _ ->
                    viewModel.sendCommand(commandDef.key)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private val commandHistoryAdapter: EventAdapter by lazy {
        EventAdapter { command ->
            // Show command result details
            val message = if (command.result != null) {
                "${command.command}\n\nالنتيجة: ${command.result}"
            } else {
                "${command.command}\n\nالحالة: ${command.displayStatus}"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("تفاصيل الأمر")
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private val eventsAdapter: EventAdapter by lazy {
        EventAdapter { _ -> }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val device = intent.getSerializableExtra(EXTRA_DEVICE) as? Device
        if (device == null) {
            finish()
            return
        }

        setupToolbar(device.name.ifEmpty { device.model })
        setupViews(device)
        observeViewModel()
        viewModel.setDevice(device)
    }

    private fun setupToolbar(title: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViews(device: Device) {
        // Device info
        binding.tvDeviceName.text = device.name.ifEmpty { device.model }
        binding.tvDeviceModel.text = device.model
        binding.tvBattery.text = device.displayBattery
        binding.tvOsVersion.text = device.osVersion.ifEmpty { device.androidVersion.ifEmpty { "N/A" } }
        binding.tvIpAddress.text = device.ipAddress.ifEmpty { "N/A" }
        binding.tvLastSeen.text = device.displayLastSeen

        // Status
        val statusText = if (device.isOnline) getString(R.string.online) else getString(R.string.offline)
        binding.tvStatus.text = statusText
        val statusColor = if (device.isOnline) R.color.online_color else R.color.offline_color
        binding.statusDot.setBackgroundColor(getColor(statusColor))

        // Quick actions
        binding.btnScreenshot.setOnClickListener { viewModel.takeScreenshot() }
        binding.btnLocation.setOnClickListener { viewModel.getLocation() }
        binding.btnBatteryInfo.setOnClickListener { viewModel.getBatteryInfo() }

        // Command category chips
        setupCategoryChips()

        // Swipe to refresh
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadData()
        }

        // Commands grid
        binding.rvCommands.apply {
            layoutManager = GridLayoutManager(this@DeviceDetailActivity, 3)
            adapter = commandAdapter
        }

        // Command history
        binding.rvCommandHistory.apply {
            layoutManager = LinearLayoutManager(this@DeviceDetailActivity)
            adapter = commandHistoryAdapter
        }

        // Events
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(this@DeviceDetailActivity)
            adapter = eventsAdapter
        }
    }

    private fun setupCategoryChips() {
        val chips = mapOf(
            binding.chipCatData to CommandDefinitions.Category.DATA,
            binding.chipCatControl to CommandDefinitions.Category.CONTROL,
            binding.chipCatFiles to CommandDefinitions.Category.FILES,
            binding.chipCatSecurity to CommandDefinitions.Category.SECURITY,
            binding.chipCatMonitor to CommandDefinitions.Category.MONITOR
        )

        chips.forEach { (chip, category) ->
            chip.setOnClickListener {
                // Uncheck all, check clicked
                chips.values.forEach { c ->
                    val id = when (c) {
                        CommandDefinitions.Category.DATA -> binding.chipCatData.id
                        CommandDefinitions.Category.CONTROL -> binding.chipCatControl.id
                        CommandDefinitions.Category.FILES -> binding.chipCatFiles.id
                        CommandDefinitions.Category.SECURITY -> binding.chipCatSecurity.id
                        CommandDefinitions.Category.MONITOR -> binding.chipCatMonitor.id
                    }
                    val chipView = binding.chipCommandCategory.findViewById<Chip>(id)
                    chipView?.isChecked = (c == category)
                }
                viewModel.setCategory(category)
                commandAdapter.submitList(viewModel.getCommandsForCategory())
            }
        }
    }

    private fun observeViewModel() {
        viewModel.currentCategory.observe(this) {
            commandAdapter.submitList(viewModel.getCommandsForCategory())
        }

        viewModel.commandHistory.observe(this) { result ->
            when (result) {
                is Result.Success -> commandHistoryAdapter.submitList(result.data)
                else -> {}
            }
        }

        viewModel.events.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    // Convert events to commands for the adapter (reuse layout)
                    val eventCommands = result.data.map { event ->
                        Command(
                            id = event.id,
                            command = event.displayEvent,
                            status = "info",
                            createdAt = event.timestamp,
                            result = event.details
                        )
                    }
                    eventsAdapter.submitList(eventCommands)
                }
                else -> {}
            }
        }

        viewModel.commandResult.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    // Could show a small progress indicator
                }
                is Result.Success -> {
                    Snackbar.make(binding.coordinator, result.data, Snackbar.LENGTH_SHORT).show()
                }
                is Result.Error -> {
                    if (result.code == 401) {
                        showSessionExpired()
                    } else {
                        Snackbar.make(
                            binding.coordinator,
                            result.message,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
            binding.swipeRefresh.isRefreshing = false
        }
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

    companion object {
        const val EXTRA_DEVICE = "extra_device"

        fun newIntent(context: Context, device: Device): Intent {
            return Intent(context, DeviceDetailActivity::class.java).apply {
                putExtra(EXTRA_DEVICE, device)
            }
        }
    }
}

class DeviceDetailViewModelFactory(private val preferences: Preferences) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceDetailViewModel(preferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}