package com.abuzahra.admin.ui.files

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abuzahra.admin.data.api.ApiResult
import com.abuzahra.admin.data.api.SendCommandRequest
import com.abuzahra.admin.data.model.Device
import com.abuzahra.admin.data.model.RemoteFile
import com.abuzahra.admin.util.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

class FilesViewModel(private val preferences: Preferences) : ViewModel() {

    private val _devices = MutableLiveData<List<Device>>()
    val devices: MutableLiveData<List<Device>> = _devices

    private val _files = MutableLiveData<ApiResult<List<RemoteFile>>>()
    val files: MutableLiveData<ApiResult<List<RemoteFile>>> = _files

    private val gson = Gson()

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
            _files.postValue(ApiResult.Loading)
            try {
                val api = preferences.getApiService()
                val request = SendCommandRequest(
                    command = "list_files",
                    deviceId = deviceId,
                    params = mapOf("arg" to path)
                )
                val responseBody = api.sendCommandRaw(request)
                val jsonString = responseBody.string()

                // Try to parse the response as a list of RemoteFile
                val fileList = try {
                    val type = object : TypeToken<List<RemoteFile>>() {}.type
                    gson.fromJson<List<RemoteFile>>(jsonString, type) ?: emptyList()
                } catch (e: Exception) {
                    // Server might wrap the result in an object with a "result" or "data" field
                    try {
                        val wrapper = gson.fromJson<Map<String, Any>>(jsonString, Map::class.java)
                        val resultData = wrapper["result"] ?: wrapper["data"]
                        if (resultData is List<*>) {
                            gson.fromJson(
                                gson.toJson(resultData),
                                object : TypeToken<List<RemoteFile>>() {}.type
                            ) ?: emptyList()
                        } else {
                            emptyList()
                        }
                    } catch (e2: Exception) {
                        emptyList()
                    }
                }

                _files.postValue(ApiResult.Success(fileList))
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    _files.postValue(ApiResult.Error("انتهت صلاحية الجلسة", 401))
                } else {
                    _files.postValue(ApiResult.Error("خطأ: ${e.code()}"))
                }
            } catch (e: Exception) {
                _files.postValue(ApiResult.Error(e.message ?: "خطأ في الاتصال"))
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
