package com.celzero.bravedns.service

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Retrofit and OkHttp integration for Windscribe Pro API.
 * Automates login, listing servers, and dynamic ephemeral WireGuard configuration generation.
 */
interface WindscribeApi {

    @FormUrlEncoded
    @POST("api/v1/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("client_id") clientId: String = "windscribe-android-rethink",
        @Field("session_type") sessionType: String = "wireguard"
    ): Response<WindscribeLoginResponse>

    @GET("api/v1/servers")
    suspend fun getServers(
        @Header("Authorization") token: String
    ): Response<WindscribeServersResponse>

    @FormUrlEncoded
    @POST("api/v1/wireguard/credentials")
    suspend fun getWireGuardCredentials(
        @Header("Authorization") token: String,
        @Field("server_id") serverId: String,
        @Field("pub_key") publicKey: String
    ): Response<WindscribeCredentialsResponse>
}

// Request & Response DTOs
data class WindscribeLoginResponse(
    @SerializedName("session_id") val sessionId: String?,
    @SerializedName("expiry_date") val expiryDate: String?,
    @SerializedName("user_status") val userStatus: String?, // "pro", "free"
    @SerializedName("error") val error: String?
)

data class WindscribeServersResponse(
    @SerializedName("servers") val servers: List<WindscribeServerNode>?
)

data class WindscribeServerNode(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("country_code") val countryCode: String,
    @SerializedName("city") val city: String,
    @SerializedName("pro") val isPro: Boolean,
    @SerializedName("dns_address") val dnsAddress: String?,
    @SerializedName("wg_endpoint") val wgEndpoint: String?
)

data class WindscribeCredentialsResponse(
    @SerializedName("private_key") val privateKey: String?,
    @SerializedName("public_key") val publicKey: String?,
    @SerializedName("address") val ipAddress: String?, // IPv4/IPv6 allocation
    @SerializedName("endpoint") val endpoint: String?, // IP:Port
    @SerializedName("preshared_key") val presharedKey: String?,
    @SerializedName("dns") val dns: String?,
    @SerializedName("error") val error: String?
)

object WindscribeApiInstance {
    private const val BASE_URL = "https://api.windscribe.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val api: WindscribeApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WindscribeApi::class.java)
    }

    /**
     * Helper to generate a valid mockup WireGuard configuration for fallback or offline preview modes.
     */
    fun generateMockupWgConfig(serverName: String, endpoint: String): String {
        return """
            [Interface]
            PrivateKey = YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=
            Address = 10.124.0.2/32, fd00::2/128
            DNS = 10.255.255.3

            [Peer]
            PublicKey = QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY=
            Endpoint = ${endpoint.ifEmpty { "103.156.184.21:443" }}
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
