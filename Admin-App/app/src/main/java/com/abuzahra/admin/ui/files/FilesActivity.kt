package com.abuzahra.admin.ui.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.abuzahra.admin.R
import com.abuzahra.admin.data.api.ApiClient
import com.abuzahra.admin.data.api.ApiResult
import com.abuzahra.admin.data.model.RemoteFile
import com.abuzahra.admin.databinding.ActivityFilesBinding
import com.abuzahra.admin.ui.login.LoginActivity
import com.abuzahra.admin.util.Preferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch as coroutineLaunch
import java.io.File

class FilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilesBinding
    private val prefs: Preferences by lazy { Preferences.getInstance(this) }

    private var currentPath = "/"
    private var deviceId: String = ""

    private val fileAdapter: FileListAdapter by lazy {
        FileListAdapter(
            onFileClick = { file ->
                if (file.isDirectory) {
                    navigateToDirectory(file.path)
                }
            },
            onDownloadClick = { file ->
                downloadFile(file)
            },
            onParentClick = {
                navigateUp()
            }
        )
    }

    // Use a simple approach for loading - we handle it directly since the FilesActivity needs a deviceId
    // We'll pick the first online device or ask user to select
    private val viewModel: FilesViewModel by viewModels {
        FilesViewModelFactory(prefs)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openFilePicker()
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { uploadFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeViewModel()
        viewModel.loadDevices()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.files)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swipeRefresh.setOnRefreshListener {
            if (deviceId.isNotBlank()) {
                viewModel.loadFiles(deviceId, currentPath)
            }
        }

        // Navigate button
        binding.etPath.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                val newPath = binding.etPath.text.toString().trim()
                if (newPath.isNotBlank()) {
                    navigateToDirectory(newPath)
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupRecyclerView() {
        binding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@FilesActivity)
            adapter = fileAdapter
        }
    }

    private fun setupFab() {
        binding.fabUpload.setOnClickListener {
            if (deviceId.isBlank()) {
                Snackbar.make(
                    binding.root,
                    "لا يوجد جهاز محدد. يرجى الاتصال بجهاز أولاً.",
                    Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    openFilePicker()
                }
            } else {
                openFilePicker()
            }
        }
    }

    private fun openFilePicker() {
        pickFileLauncher.launch("*/*")
    }

    private fun uploadFile(uri: android.net.Uri) {
        Snackbar.make(binding.root, "جاري رفع الملف...", Snackbar.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).coroutineLaunch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val result = ApiClient.uploadFile(
                    prefs.serverUrl,
                    prefs.token ?: "",
                    deviceId,
                    currentPath,
                    tempFile
                )

                when (result) {
                    is ApiResult.Success -> {
                        runOnUiThread {
                            Snackbar.make(
                                binding.root,
                                "تم رفع الملف بنجاح",
                                Snackbar.LENGTH_SHORT
                            ).show()
                            viewModel.loadFiles(deviceId, currentPath)
                        }
                    }
                    is ApiResult.Error -> {
                        runOnUiThread {
                            Snackbar.make(
                                binding.root,
                                result.message,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                    else -> {}
                }

                tempFile.delete()
            } catch (e: Exception) {
                runOnUiThread {
                    Snackbar.make(
                        binding.root,
                        "خطأ في رفع الملف: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun downloadFile(file: RemoteFile) {
        Snackbar.make(binding.root, "جاري تحميل ${file.name}...", Snackbar.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).coroutineLaunch {
            try {
                val api = prefs.getApiService()
                val url = "${prefs.serverUrl}api/upload/${file.path}"
                val body = api.downloadFile(url)

                // Save to Downloads folder
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadsDir, file.name)

                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread {
                    Snackbar.make(
                        binding.root,
                        "تم التحميل: ${destFile.absolutePath}",
                        Snackbar.LENGTH_LONG
                    ).setAction("فتح") {
                        openFile(destFile)
                    }.show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Snackbar.make(
                        binding.root,
                        "فشل التحميل: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "لا يمكن فتح الملف", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun navigateToDirectory(path: String) {
        currentPath = path
        binding.etPath.setText(path)
        if (deviceId.isNotBlank()) {
            viewModel.loadFiles(deviceId, path)
        }
    }

    private fun navigateUp() {
        if (currentPath == "/") return
        val parent = currentPath.substringBeforeLast("/").ifEmpty { "/" }
        navigateToDirectory(parent)
    }

    private fun observeViewModel() {
        viewModel.devices.observe(this) { devices ->
            if (devices.isEmpty()) {
                updateEmptyState(true)
                return@observe
            }
            if (deviceId.isBlank()) {
                // Auto-select first online device, or first device
                val target = devices.firstOrNull { it.isOnline } ?: devices.first()
                deviceId = target.id
                Snackbar.make(
                    binding.root,
                    "عرض ملفات: ${target.name.ifEmpty { target.model }}",
                    Snackbar.LENGTH_SHORT
                ).show()
                viewModel.loadFiles(deviceId, currentPath)
            }
        }

        viewModel.files.observe(this) { result ->
            binding.swipeRefresh.isRefreshing = false
            binding.loadingOverlay.visibility = View.GONE

            when (result) {
                is ApiResult.Loading -> {
                    binding.loadingOverlay.visibility = View.VISIBLE
                }
                is ApiResult.Success -> {
                    val sorted = result.data.sortedWith(
                        compareByDescending<RemoteFile> { it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
                    fileAdapter.submitList(sorted)
                    fileAdapter.setShowParent(currentPath != "/")
                    updateEmptyState(sorted.isEmpty())
                }
                is ApiResult.Error -> {
                    if (result.code == 401) {
                        showSessionExpired()
                    } else {
                        Snackbar.make(
                            binding.root,
                            result.message,
                            Snackbar.LENGTH_LONG
                        ).setAction(R.string.retry) {
                            viewModel.loadFiles(deviceId, currentPath)
                        }.show()
                    }
                    updateEmptyState(true)
                }
            }
        }
    }

    private fun showDevicePicker(devices: List<com.abuzahra.admin.data.model.Device>) {
        val names = devices.map { "${it.name.ifEmpty { it.model }} (${if (it.isOnline) "متصل" else "غير متصل"})" }
        MaterialAlertDialogBuilder(this)
            .setTitle("اختر جهازاً")
            .setItems(names.toTypedArray()) { _, which ->
                deviceId = devices[which].id
                viewModel.loadFiles(deviceId, currentPath)
            }
            .show()
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvFiles.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showSessionExpired() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.session_expired)
            .setMessage("يرجى تسجيل الدخول مرة أخرى")
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.clear()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setCancelable(false)
            .show()
    }

}