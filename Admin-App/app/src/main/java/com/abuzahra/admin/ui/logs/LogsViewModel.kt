package com.abuzahra.admin.ui.logs

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abuzahra.admin.data.api.Result
import com.abuzahra.admin.data.model.Event
import com.abuzahra.admin.util.Preferences
import kotlinx.coroutines.launch

class LogsViewModel(private val preferences: Preferences) : ViewModel() {

    private val _events = MutableLiveData<Result<List<Event>>>()
    val events: MutableLiveData<Result<List<Event>>> = _events

    private val _searchQuery = MutableLiveData("")
    private val _filterType = MutableLiveData(FilterType.ALL)

    private var allEvents: List<Event> = emptyList()

    enum class FilterType { ALL, CONNECTION, COMMAND, ALERT }

    fun loadEvents() {
        viewModelScope.launch {
            try {
                val api = preferences.getApiService()
                val eventList = api.getEvents()
                allEvents = eventList
                applyFilters()
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    _events.postValue(Result.Error("انتهت صلاحية الجلسة", 401))
                } else {
                    _events.postValue(Result.Error("خطأ: ${e.code()}"))
                }
            } catch (e: Exception) {
                _events.postValue(Result.Error(e.message ?: "خطأ في الاتصال"))
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

        val filtered = allEvents.filter { event ->
            val matchesSearch = query.isBlank() ||
                    event.displayEvent.lowercase().contains(query) ||
                    event.deviceName.lowercase().contains(query) ||
                    event.details?.lowercase()?.contains(query) == true

            val matchesFilter = when (filter) {
                FilterType.ALL -> true
                FilterType.CONNECTION -> event.eventTypeCategory == "اتصال"
                FilterType.COMMAND -> event.eventTypeCategory == "أوامر"
                FilterType.ALERT -> event.eventTypeCategory == "تنبيهات"
            }

            matchesSearch && matchesFilter
        }

        _events.value = Result.Success(filtered)
    }
}