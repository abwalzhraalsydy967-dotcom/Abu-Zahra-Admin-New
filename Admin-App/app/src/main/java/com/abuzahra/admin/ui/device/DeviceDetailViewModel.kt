package com.abuzahra.admin.ui.device

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abuzahra.admin.data.api.ApiResult
import com.abuzahra.admin.data.api.SendCommandRequest
import com.abuzahra.admin.data.model.*
import com.abuzahra.admin.util.Preferences
import kotlinx.coroutines.launch

class DeviceDetailViewModel(private val preferences: Preferences) : ViewModel() {

    private val _device = MutableLiveData<Device>()
    val device: MutableLiveData<Device> = _device

    private val _commandHistory = MutableLiveData<ApiResult<List<Command>>>()
    val commandHistory: MutableLiveData<ApiResult<List<Command>>> = _commandHistory

    private val _events = MutableLiveData<ApiResult<List<Event>>>()
    val events: MutableLiveData<ApiResult<List<Event>>> = _events

    private val _commandResult = MutableLiveData<ApiResult<String>>()
    val commandResult: MutableLiveData<ApiResult<String>> = _commandResult

    private val _currentCategory = MutableLiveData(CommandDefinitions.Category.DATA)
    val currentCategory: MutableLiveData<CommandDefinitions.Category> = _currentCategory

    private var deviceId: String = ""

    fun setDevice(device: Device) {
        _device.value = device
        deviceId = device.id
        loadData()
    }

    fun loadData() {
        loadCommandHistory()
        loadEvents()
    }

    private fun loadCommandHistory() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            try {
                val api = preferences.getApiService()
                val commands = api.getCommands()
                // Filter commands for this device
                val deviceCommands = commands.filter { it.deviceId == deviceId }
                _commandHistory.postValue(ApiResult.Success(deviceCommands))
            } catch (e: Exception) {
                _commandHistory.postValue(ApiResult.Error(e.message ?: "خطأ"))
            }
        }
    }

    private fun loadEvents() {
        viewModelScope.launch {
            try {
                val api = preferences.getApiService()
                val allEvents = api.getEvents()
                val deviceEvents = allEvents.filter { it.deviceId == deviceId }
                _events.postValue(ApiResult.Success(deviceEvents))
            } catch (e: Exception) {
                _events.postValue(ApiResult.Error(e.message ?: "خطأ"))
            }
        }
    }

    fun setCategory(category: CommandDefinitions.Category) {
        _currentCategory.value = category
    }

    fun getCommandsForCategory(): List<CommandDefinitions.CommandDef> {
        return CommandDefinitions.commandsByCategory[currentCategory.value]
            ?: CommandDefinitions.commandsByCategory[CommandDefinitions.Category.DATA]
            ?: emptyList()
    }

    fun sendCommand(commandKey: String) {
        if (deviceId.isBlank()) return

        viewModelScope.launch {
            _commandResult.postValue(ApiResult.Loading)
            try {
                val api = preferences.getApiService()
                val request = SendCommandRequest(
                    command = commandKey,
                    deviceId = deviceId
                )
                val response = api.sendCommand(request)
                if (response.status == "success" || response.status == "delivered") {
                    _commandResult.postValue(ApiResult.Success("تم إرسال الأمر بنجاح"))
                    // Refresh command history
                    loadCommandHistory()
                } else {
                    _commandResult.postValue(ApiResult.Error(response.message.ifEmpty { "فشل إرسال الأمر" }))
                }
            } catch (e: retrofit2.HttpException) {
                _commandResult.postValue(ApiResult.Error("خطأ: ${e.code()}"))
            } catch (e: Exception) {
                _commandResult.postValue(ApiResult.Error("خطأ في الاتصال: ${e.message}"))
            }
        }
    }

    fun takeScreenshot() {
        sendCommand("screenshot")
    }

    fun getLocation() {
        sendCommand("get_location")
    }

    fun getBatteryInfo() {
        sendCommand("get_battery")
    }
}
