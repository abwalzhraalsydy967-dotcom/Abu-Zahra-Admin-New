package com.abuzahra.admin.ui.login

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abuzahra.admin.data.api.ApiClient
import com.abuzahra.admin.data.api.LoginRequest
import com.abuzahra.admin.data.api.LoginResponse
import com.abuzahra.admin.data.api.ApiResult
import com.abuzahra.admin.util.Preferences
import kotlinx.coroutines.launch

class LoginViewModel(private val preferences: Preferences) : ViewModel() {

    private val _loginResult = MutableLiveData<ApiResult<LoginResponse>>()
    val loginResult: MutableLiveData<ApiResult<LoginResponse>> = _loginResult

    fun login(username: String, password: String, serverUrl: String) {
        if (username.isBlank() || password.isBlank()) {
            _loginResult.value = ApiResult.Error("يرجى إدخال اسم المستخدم وكلمة المرور")
            return
        }

        _loginResult.value = ApiResult.Loading

        viewModelScope.launch {
            try {
                // Save server URL first
                preferences.serverUrl = serverUrl.ifBlank { "https://alsydyabwalzhra.online/" }

                // Create API client without token for login
                val api = ApiClient.create(preferences.serverUrl)

                val response = api.login(LoginRequest(username, password))

                if (response.token.isNotEmpty()) {
                    preferences.token = response.token
                    _loginResult.postValue(ApiResult.Success(response))
                } else {
                    _loginResult.postValue(ApiResult.Error(response.message.ifEmpty { "فشل تسجيل الدخول" }))
                }
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    401 -> _loginResult.postValue(ApiResult.Error("اسم المستخدم أو كلمة المرور غير صحيحة", 401))
                    403 -> _loginResult.postValue(ApiResult.Error("تم رفض الوصول", 403))
                    else -> _loginResult.postValue(ApiResult.Error("خطأ في الخادم: ${e.code()}", e.code()))
                }
            } catch (e: java.net.SocketTimeoutException) {
                _loginResult.postValue(ApiResult.Error("انتهت مهلة الاتصال بالخادم"))
            } catch (e: java.net.UnknownHostException) {
                _loginResult.postValue(ApiResult.Error("لا يمكن الوصول إلى الخادم"))
            } catch (e: javax.net.ssl.SSLException) {
                _loginResult.postValue(ApiResult.Error("خطأ في شهادة الأمان"))
            } catch (e: Exception) {
                _loginResult.postValue(ApiResult.Error("خطأ في الاتصال: ${e.message}"))
            }
        }
    }

    val isLoggedIn: Boolean get() = preferences.isLoggedIn
    val serverUrl: String get() = preferences.serverUrl
}