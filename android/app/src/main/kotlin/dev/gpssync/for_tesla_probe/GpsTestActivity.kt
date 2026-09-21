package dev.gpssync.for_tesla_probe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class GpsTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var hasToken by mutableStateOf(false)
    private val authSettings by lazy { TeslaAuthSettings(this) }
    private var vin by mutableStateOf("")
    private var region by mutableStateOf(FleetLocationClient.defaultRegion)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var fix by mutableStateOf<VehicleFix?>(null)
    private var observed by mutableStateOf<Location?>(null)
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { observed = location }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) observe() else error = "정확한 위치 권한을 허용해야 모의 GPS를 사용할 수 있습니다."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Authentication is managed by the encrypted TeslaAuth store.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Screen() } }
    }
    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun onStart() {
        super.onStart()
        runCatching { authSettings.load().data }.onSuccess { hasToken = it.accessToken.isNotBlank(); vin = it.vin }.onFailure { hasToken = false; vin = ""; error = it.message }
        observe()
    }
    override fun onStop() { getSystemService(LocationManager::class.java).removeUpdates(listener); super.onStop() }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private fun observe() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(LocationManager::class.java)
        manager.removeUpdates(listener)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { manager.requestLocationUpdates(provider, 1000, 0f, listener) }
        }
    }
    private fun requestPermissions() {
        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.launch(requested.toTypedArray())
    }
    private fun fetchAndApply() {
        if (!hasLocationPermission()) { requestPermissions(); return }
        val appOps = getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mockAllowed = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
        if (!mockAllowed) { error = "개발자 옵션 → 모의 위치 앱에서 Tesla GPS Sync를 먼저 선택하세요."; return }
        val manager = getSystemService(LocationManager::class.java)
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { error = "휴대폰의 위치 기능을 먼저 켜 주세요."; return }
        val base = FleetLocationClient.regions.getValue(region)
        busy = true; error = null; fix = null
        executor.execute {
            val result = runCatching { TeslaAuthManager(authSettings).fetchLocation(base) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.onSuccess { value ->
                    fix = value
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        error = "화면을 벗어나 모의 GPS 시작을 취소했습니다. 다시 조회하세요."
                        return@onSuccess
                    }
                    try {
                        val intent = Intent(this, MockGpsService::class.java)
                            .putExtra("latitude", value.latitude).putExtra("longitude", value.longitude).putExtra("timestamp", value.timestamp)
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                        observe()
                    } catch (_: Exception) { error = "모의 GPS를 시작하지 못했습니다. 위치 권한·설정을 확인하세요." }
                }.onFailure { e ->
                    error = if (e is java.io.IOException) "네트워크 연결 또는 차량 응답 시간을 확인하세요." else e.message ?: "위치 조회 실패"
                }
            }
        }
    }
    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { error = "이 기능을 열 수 있는 앱이 없습니다." }
    }
    @Composable private fun Screen() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("차량 GPS 지도 테스트", style = MaterialTheme.typography.headlineSmall)
                Text("차량 위치를 한 번 조회해 폰의 모의 GPS에 적용합니다. 조회한 좌표는 최대 5분간 유지되며, 차량 이동을 자동 추적하지 않습니다.")
                TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("① 개발자 옵션 → 모의 위치 앱 → Tesla GPS Sync") }
                OutlinedButton(onClick = { requestPermissions() }) { Text("② 정확한 위치·알림 권한 허용") }
                OutlinedButton(onClick = { open(Intent(this@GpsTestActivity, TeslaAuthActivity::class.java)) }) { Text("③ TeslaAuth · 인증 정보 설정") }
                Text(if (hasToken) "저장된 Access Token 사용 · 만료 시 자동 갱신" else "TeslaAuth에서 토큰을 저장하세요.")
                Text("저장된 VIN: ${vin.ifBlank { "미설정" }}", modifier = Modifier.testTag("fleet-vin"))
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(enabled = !busy && !MockGpsService.running, onClick = { expanded = true }) { Text(region) }
                    DropdownMenu(expanded, { expanded = false }) {
                        FleetLocationClient.regions.keys.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { region = name; expanded = false }) }
                    }
                }
                Button(enabled = !busy && !MockGpsService.running && hasToken && vin.isNotBlank(),
                    onClick = { fetchAndApply() }, modifier = Modifier.fillMaxWidth().testTag("fleet-apply")) {
                    Text(if (busy) "차량 위치 조회 중…" else "차량 위치 조회 → 모의 GPS 시작")
                }
                OutlinedButton(enabled = MockGpsService.running, onClick = { stopService(Intent(this@GpsTestActivity, MockGpsService::class.java)) }, modifier = Modifier.testTag("mock-stop")) { Text("모의 GPS 중지 · 실제 위치로 복귀") }
                Text(MockGpsService.message)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val displayFix = MockGpsService.currentFix ?: fix
                displayFix?.let { point ->
                    Text("차량: ${point.latitude}, ${point.longitude}\n차량 측정: ${DateFormat.getDateTimeInstance().format(Date(point.timestamp))}")
                    OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}"))) }) { Text("지도에서 좌표 확인") }
                }
                observed?.let { location ->
                    @Suppress("DEPRECATION") val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
                    Text("기기 수신: ${location.latitude}, ${location.longitude}\n공급자: ${location.provider} · 모의 위치: $mock\n수신: ${DateFormat.getTimeInstance().format(Date(location.time))}")
                }
                Text("지도 핀과 지도 앱의 ‘내 위치’를 함께 확인하세요. 중지 후 실제 위치를 다시 잡는 데 시간이 걸릴 수 있습니다. 모의 위치를 무시하는 앱도 있습니다.")
                TextButton(onClick = { finish() }) { Text("닫기 (진행 중인 모의 GPS는 알림에서 중지)") }
            }
        }
    }
}
