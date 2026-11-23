package com.inditech.dpc.data.api

import com.inditech.dpc.data.model.AckRequest
import com.inditech.dpc.data.model.EnrollmentRequest
import com.inditech.dpc.data.model.EnrollmentResponse
import com.inditech.dpc.data.model.HeartbeatRequest
import com.inditech.dpc.data.model.HeartbeatResponse
import com.inditech.dpc.data.model.TenantConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** REST contract — see backend/README.md ("Core REST API Endpoints"). */
interface PlatformApi {
    @POST("api/v1/devices/enroll")
    suspend fun enroll(@Body body: EnrollmentRequest): Response<EnrollmentResponse>

    @GET("api/v1/tenants/{tenant_id}/config")
    suspend fun tenantConfig(
        @Path("tenant_id") tenantId: String,
        @Header("Authorization") bearer: String,
        @Header("If-None-Match") etag: String? = null,
    ): Response<TenantConfig>

    @POST("api/v1/devices/{device_id}/heartbeat")
    suspend fun heartbeat(
        @Path("device_id") deviceId: String,
        @Header("Authorization") bearer: String,
        @Body body: HeartbeatRequest,
    ): Response<HeartbeatResponse>

    @POST("api/v1/devices/{device_id}/commands/{command_id}/ack")
    suspend fun ack(
        @Path("device_id") deviceId: String,
        @Path("command_id") commandId: String,
        @Header("Authorization") bearer: String,
        @Body body: AckRequest,
    ): Response<Unit>
}

val PlatformJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

object ApiClientFactory {
    /** `baseUrl` is the API origin (the api_gateway_url from provisioning, minus its /api/v1 path). */
    fun create(baseUrl: String, debug: Boolean = false): PlatformApi {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (debug) {
                    addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(okHttp)
            .addConverterFactory(PlatformJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlatformApi::class.java)
    }

    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val origin = Regex("^(https?://[^/]+)").find(trimmed)?.value ?: trimmed
        return "$origin/"
    }
}
