package dev.gpssync.for_tesla_probe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class TeslaAuthActivity : ComponentActivity() {
    private val settings by lazy { TeslaAuthSettings(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private var access by mutableStateOf("")
    private var refresh by mutableStateOf("")
    private var vin by mutableStateOf("")
    private var expiry by mutableStateOf(0L)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var previous = TeslaAuthData()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Screen() } }
    }
    override fun onStart() {
        super.onStart()
        load()
        if (access.isNotBlank() && refresh.isNotBlank() && expiry > 0 && expiry <= System.currentTimeMillis() + 60_000) renew(false)
    }
    override fun onDestroy() { executor.shutdownNow(); access = ""; refresh = ""; super.onDestroy() }
    private fun load() {
        runCatching { settings.load().data }.onSuccess {
            previous = it; access = it.accessToken; refresh = it.refreshToken; vin = it.vin; expiry = it.expiresAt
        }.onFailure { message = it.message ?: "읽기 실패" }
    }
    private fun save() {
        runCatching { settings.save(TeslaAuthData.import(access, refresh, vin, previous, TeslaAuthSettings::jwtExpiry)) }
            .onSuccess { load(); message = "저장했습니다. Clear 전까지 이 기기에 유지됩니다." }
            .onFailure { message = if (it is org.json.JSONException) "로그인 결과 JSON 전체 또는 Access Token을 입력하세요." else it.message ?: "저장 실패" }
    }
    private fun renew(force: Boolean) {
        if (busy) return
        busy = true; message = "토큰 갱신 확인 중…"
        executor.execute {
            val result = runCatching {
                val rejected = if (force) settings.load().data.accessToken else null
                TeslaAuthManager(settings).credentials(rejected)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                // Always reload current storage; Clear may have run while the network request was in flight.
                load()
                message = if (result.isSuccess) "최신 토큰을 저장했습니다." else (result.exceptionOrNull()?.message ?: "갱신 실패")
            }
        }
    }
    @Composable private fun Screen() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TeslaAuth", style = MaterialTheme.typography.headlineSmall)
                Text("Access Token과 VIN을 기기에 암호화해 저장합니다. 앱을 닫아도 유지되며 GPS 테스트에서 자동으로 불러옵니다.")
                OutlinedButton(onClick = {
                    runCatching {
                        check(BuildConfig.TESLA_LOGIN_URL.isNotBlank()) { "로그인 주소가 설정되지 않았습니다. README의 프로젝트 설정 후 앱을 다시 설치하세요." }
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.TESLA_LOGIN_URL)))
                    }
                        .onFailure { message = if (it is IllegalStateException) it.message.orEmpty() else "웹 브라우저를 열 수 없습니다." }
                }) { Text("Tesla 로그인 · 토큰 묶음 받기") }
                Text("로그인 결과의 ‘자동 갱신용 토큰 묶음(JSON)’을 Access Token 칸에 붙여 넣고 저장하세요. 기존 Access Token만 저장할 수도 있지만 자동 갱신에는 Refresh Token이 필요합니다.")
                OutlinedTextField(access, { access = it; refresh = "" }, enabled = !busy, label = { Text("Access Token 또는 토큰 묶음 JSON") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("auth-access"))
                OutlinedTextField(vin, { vin = it }, enabled = !busy, singleLine = true, label = { Text("VIN") }, modifier = Modifier.fillMaxWidth().testTag("auth-vin"))
                OutlinedTextField(refresh, { refresh = it }, enabled = !busy, label = { Text("Refresh Token (자동 갱신용)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("auth-refresh"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy, onClick = { save() }, modifier = Modifier.testTag("auth-save")) { Text("Save") }
                    OutlinedButton(onClick = {
                        runCatching { settings.clear() }.onSuccess {
                            previous = TeslaAuthData(); access = ""; refresh = ""; vin = ""; expiry = 0
                            stopService(Intent(this@TeslaAuthActivity, MockGpsService::class.java))
                            message = "저장된 인증 정보와 VIN을 삭제했습니다."
                        }.onFailure { message = "삭제에 실패했습니다." }
                    }, modifier = Modifier.testTag("auth-clear")) { Text("Clear") }
                }
                OutlinedButton(enabled = !busy && previous.refreshToken.isNotBlank(), onClick = { renew(true) }) { Text("지금 토큰 갱신") }
                Text(if (expiry > 0) "Access Token 만료: ${DateFormat.getDateTimeInstance().format(Date(expiry))}" else "만료 시각 미확인: API 인증 오류 시 갱신을 시도합니다.")
                Text("위치 조회 시 만료가 임박했거나 인증 오류(401)가 발생하면 자동 갱신하고 저장합니다. Refresh Token까지 만료되거나 권한이 철회되면 다시 로그인해야 합니다.")
                if (message.isNotEmpty()) Text(message)
                TextButton(onClick = { finish() }) { Text("닫기") }
            }
        }
    }
}
