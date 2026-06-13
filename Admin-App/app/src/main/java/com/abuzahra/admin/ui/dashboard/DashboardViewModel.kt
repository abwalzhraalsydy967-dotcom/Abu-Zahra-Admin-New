package com.abuzahra.admin.ui.dashboard

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abuzahra.admin.data.api.Result
import com.abuzahra.admin.data.api.StatsResponse
import com.abuzahra.admin.data.model.Device
import com.abuzahra.admin.util.Preferences
import kotlinx.coroutines.launch

class DashboardViewModel(private val preferences: Preferences) : ViewModel() {

    private val _devices = MutableLiveData<Result<List<Device>>>()
    val devices: MutableLiveData<Result<List<Device>>> = _devices

    private val _stats = MutableLiveData<Result<StatsResponse>>()
    val stats: MutableLiveData<Result<StatsResponse>> = _stats

    private val _searchQuery = MutableLiveData("")
    val searchQuery: MutableLiveData<String> = _searchQuery

    private val _filterType = MutableLiveData(FilterType.ALL)
    val filterType: MutableLiveData<FilterType> = _filterType

    private var allDevices: List<Device> = emptyList()

    enum class FilterType { ALL, ONLINE, OFFLINE }

    init {
        loadData()
    }

    fun loadData() {
        loadDevices()
        loadStats()
    }

    fun refresh() {
        loadData()
    }

    private fun loadDevices() {
        viewModelScope.launch {
            try {
                val api = preferences.getApiService()
                val deviceList = api.getDevices()
                allDevices = deviceList
                applyFilters()
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    _devices.postValue(Result.Error("انتهت صلاحية الجلسة", 401))
                } else {
                    _devices.postValue(Result.Error("خطأ في تحميل الأجهزة: ${e.code()}"))
                }
            } catch (e: Exception) {
                _devices.postValue(Result.Error("خطأ في الاتصال: ${e.message}"))
            }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val api = preferences.getApiService()
                val statsResponse = api.getStats()
                _stats.postValue(Result.Success(statsResponse))
            } catch (e: Exception) {
                // Stats are secondary, don't show error
                _stats.postValue(Result.Success(StatsResponse()))
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilters()
    }

    fun setFilter(type: FilterType) {
        _filterType.value = type
        applyFilters()
    }

    private fun applyFilters() {
        val query = _searchQuery.value.orEmpty().lowercase()
        val filter = _filterType.value ?: FilterType.ALL

        val filtered = allDevices.filter { device ->
            val matchesSearch = query.isBlank() ||
                    device.name.lowercase().contains(query) ||
                    device.model.lowercase().contains(query) ||
                    device.imei.contains(query) ||
                    device.phoneNumber.contains(query)

            val matchesFilter = when (filter) {
                FilterType.ALL -> true
                FilterType.ONLINE -> device.isOnline
                FilterType.OFFLINE -> !device.isOnline
            }

            matchesSearch && matchesFilter
        }

        _devices.value = Result.Success(filtered)
    }

    fun logout() {
        preferences.clear()
    }
}