package dev.gpssync.for_tesla_probe

import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class TeslaAuthData(val accessToken: String = "", val refreshToken: String = "", val vin: String = "", val expiresAt: Long = 0,
                    val clientId: String = DEFAULT_CLIENT_ID) {
    companion object {
        const val DEFAULT_CLIENT_ID = BuildConfig.TESLA_CLIENT_ID
        fun fromJson(json: JSONObject) = TeslaAuthData(json.optString("access_token"), json.optString("refresh_token"), json.optString("vin"), json.optLong("expires_at"), json.optString("client_id", DEFAULT_CLIENT_ID))
        fun import(input: String, refresh: String, vin: String, previous: TeslaAuthData, jwtExpiry: (String) -> Long): TeslaAuthData {
            val raw = input.trim()
            val bundle = if (raw.startsWith("{")) JSONObject(raw) else null
            val token = bundle?.getString("access_token") ?: raw
            require(token.isNotBlank() && token.length <= 32_768 && token.none { it.isWhitespace() }) { "Access Token 또는 로그인 결과 JSON을 입력하세요." }
            val normalizedVin = vin.trim().uppercase(java.util.Locale.US)
            require(normalizedVin.isEmpty() || Regex("[A-HJ-NPR-Z0-9]{17}").matches(normalizedVin)) { "VIN은 17자리입니다." }
            // Never silently associate an old account's refresh token with a replacement access token.
            val rt = bundle?.optString("refresh_token").orEmpty().ifEmpty { refresh.trim() }
            require(rt.length <= 32_768 && rt.none { it.isWhitespace() }) { "Refresh Token을 확인하세요." }
            val expiry = bundle?.optLong("expires_at", 0)?.takeIf { it > 0 }
                ?: jwtExpiry(token).takeIf { it > 0 }
                ?: if (token == previous.accessToken) previous.expiresAt else 0
            return TeslaAuthData(token, rt, normalizedVin, expiry, bundle?.optString("client_id", DEFAULT_CLIENT_ID) ?: previous.clientId)
        }
        fun refreshed(body: String, old: TeslaAuthData, now: Long): TeslaAuthData {
            val json = JSONObject(body)
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val seconds = json.optLong("expires_in")
            require(access.isNotBlank() && refresh.isNotBlank() && seconds in 1..31_536_000) { "갱신 응답이 올바르지 않습니다. 다시 로그인하세요." }
            return TeslaAuthData(access, refresh, old.vin, now + seconds * 1000, old.clientId)
        }
    }
    fun json() = JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken).put("vin", vin).put("expires_at", expiresAt).put("client_id", clientId)
    override fun toString() = "TeslaAuthData([redacted])"
}
class StoredTeslaAuth(val data: TeslaAuthData, val revision: String)
interface TeslaAuthStore {
    fun load(): StoredTeslaAuth
    fun replaceIfCurrent(revision: String, data: TeslaAuthData): Boolean
}
class FleetUnauthorized : Exception("차량 인증이 만료됐습니다.")

class TeslaAuthManager(private val store: TeslaAuthStore, private val renew: (TeslaAuthData) -> TeslaAuthData = TeslaTokenClient::refresh) {
    companion object { private val refreshLock = Any() }
    fun credentials(rejectedToken: String? = null): StoredTeslaAuth = synchronized(refreshLock) {
        val stored = store.load()
        val data = stored.data
        check(data.accessToken.isNotBlank()) { "TeslaAuth에서 인증 정보를 저장하세요." }
        val expired = data.expiresAt > 0 && data.expiresAt <= System.currentTimeMillis() + 60_000
        val rejected = rejectedToken != null && rejectedToken == data.accessToken
        if (!expired && !rejected) return@synchronized stored
        check(data.refreshToken.isNotBlank()) { "자동 갱신에 Refresh Token이 필요합니다. TeslaAuth에서 다시 로그인하고 토큰 묶음을 저장하세요." }
        val updated = renew(data)
        check(store.replaceIfCurrent(stored.revision, updated)) { "인증 정보가 변경되거나 Clear되어 갱신 결과를 저장하지 않았습니다." }
        store.load()
    }
    fun fetchLocation(base: String): VehicleFix {
        var stored = credentials()
        val result = try { FleetLocationClient.fetch(base, stored.data.vin, stored.data.accessToken) }
        catch (_: FleetUnauthorized) {
            stored = credentials(stored.data.accessToken)
            FleetLocationClient.fetch(base, stored.data.vin, stored.data.accessToken)
        }
        check(store.load().revision == stored.revision) { "인증 정보가 변경되어 위치 조회를 취소했습니다." }
        return result
    }
}
object TeslaTokenClient {
    fun refresh(old: TeslaAuthData): TeslaAuthData {
        check(old.clientId.isNotBlank()) { "Client ID가 없습니다. 설정 후 다시 로그인하여 토큰 묶음을 저장하세요." }
        val connection = URL(BuildConfig.TESLA_TOKEN_URL).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = mapOf("grant_type" to "refresh_token", "client_id" to old.clientId, "refresh_token" to old.refreshToken)
                .entries.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            check(status == 200) { if (status == 400 || status == 401) "자동 갱신 권한이 만료되거나 철회됐습니다. TeslaAuth에서 다시 로그인하세요." else "토큰 갱신 실패 (HTTP $status). 저장된 정보는 유지됩니다." }
            val bytes = connection.inputStream.use { it.readBytesWithLimit(1_048_576) }
            return TeslaAuthData.refreshed(String(bytes, Charsets.UTF_8), old, System.currentTimeMillis())
        } finally { connection.disconnect() }
    }
}
