package com.abuzahra.admin.ui.files

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abuzahra.admin.data.api.Result
import com.abuzahra.admin.data.model.Device
import com.abuzahra.admin.data.model.RemoteFile
import com.abuzahra.admin.util.Preferences
import kotlinx.coroutines.launch

class FilesViewModel(private val preferences: Preferences) : ViewModel() {

    private val _devices = MutableLiveData<List<Device>>()
    val devices: MutableLiveData<List<Device>> = _devices

    private val _files = MutableLiveData<Result<List<RemoteFile>>>()
    val files: MutableLiveData<Result<List<RemoteFile>>> = _files

    fun loadDevices() {
        viewModelScope.launch {
            try {
                val api = preferences.getApiService()
                val deviceList = api.getDevices()
                _devices.postValue(deviceList)
            } catch (e: Exception) {
                _devices.postValue(emptyList())
            }
        }
    }

    fun loadFiles(deviceId: String, path: String = "/") {
        viewModelScope.launch {
            _files.postValue(Result.Loading)
            try {
                val api = preferences.getApiService()
                val fileList = api.getFiles(deviceId, path)
                _files.postValue(Result.Success(fileList))
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    _files.postValue(Result.Error("انتهت صلاحية الجلسة", 401))
                } else {
                    _files.postValue(Result.Error("خطأ: ${e.code()}"))
                }
            } catch (e: Exception) {
                _files.postValue(Result.Error(e.message ?: "خطأ في الاتصال"))
            }
        }
    }
}

class FilesViewModelFactory(private val preferences: Preferences) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FilesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FilesViewModel(preferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}