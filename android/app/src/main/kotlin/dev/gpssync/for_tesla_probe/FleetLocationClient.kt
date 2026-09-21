package dev.gpssync.for_tesla_probe

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class VehicleFix(val latitude: Double, val longitude: Double, val timestamp: Long) {
    fun validate(now: Long = System.currentTimeMillis()): VehicleFix {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) { "차량 좌표가 올바르지 않습니다." }
        require(timestamp > 0 && timestamp >= now - 300_000 && timestamp <= now + 60_000) { "차량 위치가 5분 이상 오래됐거나 시각이 올바르지 않습니다. 다시 조회하세요." }
        return this
    }
}

object FleetLocationClient {
    val regions = linkedMapOf("한국 · 북미 · 아시아태평양" to BuildConfig.TESLA_FLEET_NA_URL, "유럽" to BuildConfig.TESLA_FLEET_EU_URL)
    val defaultRegion: String = if (BuildConfig.TESLA_DEFAULT_REGION == "EU") regions.keys.last() else regions.keys.first()
    fun parse(body: String, now: Long = System.currentTimeMillis()): VehicleFix {
        val root = JSONObject(body)
        require(root.optString("error").isBlank()) { "차량 위치를 가져오지 못했습니다." }
        val response = root.optJSONObject("response") ?: error("차량 데이터가 없습니다.")
        // Fleet API returns location_data fields in drive_state.
        val data = response.optJSONObject("drive_state") ?: error("차량 위치가 없습니다. vehicle_location 권한을 확인하세요.")
        fun number(key: String): Double = (data.opt(key) as? Number)?.toDouble() ?: error("차량 $key 값이 없습니다.")
        val timestamp = number("timestamp")
        require(timestamp.isFinite() && timestamp % 1.0 == 0.0) { "차량 위치 시각이 올바르지 않습니다." }
        return VehicleFix(number("latitude"), number("longitude"), timestamp.toLong()).validate(now)
    }
    fun fetch(base: String, vin: String, token: String): VehicleFix {
        require(base in regions.values) { "지원하지 않는 API 지역입니다." }
        require(Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin)) { "17자리 VIN을 입력하세요." }
        require(token.isNotBlank() && !token.any { it.isWhitespace() }) { "유효한 사용자 Access Token을 입력하세요." }
        val connection = URL("$base/api/1/vehicles/$vin/vehicle_data?endpoints=location_data").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status == 401) throw FleetUnauthorized()
            check(status == 200) { when (status) {
                401 -> "로그인이 만료됐습니다. 사용자 Access Token을 다시 발급하세요."
                403 -> "차량 정보·차량 위치 권한 또는 앱 등록 상태를 확인하세요."
                404 -> "차량 VIN 또는 API 지역을 확인하세요."
                408 -> "차량이 오프라인이거나 절전 중입니다. 차량이 온라인일 때 다시 시도하세요."
                429 -> "조회 한도에 도달했습니다. 잠시 후 다시 시도하세요."
                else -> "Fleet API 조회 실패 (HTTP $status)."
            } }
            val bytes = connection.inputStream.use { it.readBytesWithLimit(1_048_576) }
            return parse(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

internal fun java.io.InputStream.readBytesWithLimit(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "차량 응답이 너무 큽니다." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
