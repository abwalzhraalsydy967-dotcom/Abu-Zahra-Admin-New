package com.abuzahra.admin.data.api

import com.abuzahra.admin.data.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface ApiService {

    @POST("api/web/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("api/web/stats")
    suspend fun getStats(): StatsResponse

    @GET("api/web/devices")
    suspend fun getDevices(): List<Device>

    @GET("api/web/commands")
    suspend fun getCommands(): List<Command>

    @POST("api/web/send_command")
    suspend fun sendCommand(
        @Body request: SendCommandRequest
    ): SendCommandResponse

    @GET("api/web/events")
    suspend fun getEvents(): List<Event>

    @POST("api/web/send_command")
    suspend fun sendCommandRaw(
        @Body request: SendCommandRequest
    ): okhttp3.ResponseBody

    @Streaming
    @GET
    suspend fun downloadFile(@Url url: String): ResponseBody
}

object ApiClient {

    private const val DEFAULT_BASE_URL = "https://alsydyabwalzhra.online/"

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private fun getUnsafeOkHttpClient(token: String? = null): OkHttpClient {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val builder = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()

                    if (!token.isNullOrEmpty()) {
                        requestBuilder.addHeader("Authorization", "Bearer $token")
                    }

                    requestBuilder.addHeader("Accept", "application/json")
                    requestBuilder.addHeader("Content-Type", "application/json")

                    val request = requestBuilder.build()
                    chain.proceed(request)
                }

            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun create(baseUrl: String = DEFAULT_BASE_URL, token: String? = null): ApiService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(getUnsafeOkHttpClient(token))
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ApiService::class.java)
    }

    fun createWithToken(baseUrl: String = DEFAULT_BASE_URL, token: String): ApiService {
        return create(baseUrl, token)
    }

    /**
     * Upload a file to a device
     */
    suspend fun uploadFile(
        baseUrl: String,
        token: String,
        deviceId: String,
        remotePath: String,
        file: File,
        onProgress: ((Float) -> Unit)? = null
    ): ApiResult<String> {
        return try {
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val client = getUnsafeOkHttpClient(token).newBuilder()
                .addNetworkInterceptor { chain ->
                    val originalResponse = chain.proceed(chain.request())
                    val responseBody = originalResponse.body
                    if (responseBody != null) {
                        val contentType = responseBody.contentType()
                        val contentLength = responseBody.contentLength()
                        val bufferedSource = responseBody.source()

                        object : ResponseBody() {
                            override fun contentType(): MediaType? = contentType
                            override fun contentLength(): Long = contentLength

                            override fun source(): okio.Source = bufferedSource
                        }
                    } else {
                        responseBody
                    }
                    originalResponse
                }
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("path", remotePath)
                .addFormDataPart(
                    "file", file.name,
                    file.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${normalizedUrl}api/web/device/$deviceId/upload")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                ApiResult.Success(response.body?.string() ?: "تم الرفع بنجاح")
            } else {
                ApiResult.Error("فشل رفع الملف: ${response.code}", response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "فشل رفع الملف")
        }
    }
}