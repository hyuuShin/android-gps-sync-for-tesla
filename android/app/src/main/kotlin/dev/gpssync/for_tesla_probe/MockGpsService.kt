package dev.gpssync.for_tesla_probe

import android.app.*
import android.content.Intent
import android.location.Criteria
import com.google.android.gms.location.LocationServices
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import android.location.Location
import android.location.LocationManager
import android.os.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Calls are protected by the mock-location app-op; failures terminate the test and clean up.
@android.annotation.SuppressLint("MissingPermission")
class MockGpsService : Service() {
    companion object {
        var running by mutableStateOf(false); private set
        var message by mutableStateOf("모의 위치 중지됨"); private set
        var currentFix by mutableStateOf<VehicleFix?>(null); private set
        const val STOP = "stop"
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: LocationManager
    private val installed = mutableListOf<String>()
    private var deadline = 0L
    private var destroyed = false
    private var fusedEnabled = false
    private var fusedRequested = false
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val tick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() >= deadline) { message = "5분 테스트가 종료됐습니다."; stopSelf(); return }
            try {
                val fix = currentFix ?: return
                for (provider in installed) manager.setTestProviderLocation(provider, Location(provider).apply {
                    latitude = fix.latitude; longitude = fix.longitude
                    accuracy = 3f; time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
                if (fusedEnabled) fused.setMockLocation(Location("fused").apply {
                    latitude = fix.latitude; longitude = fix.longitude; accuracy = 3f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }).addOnFailureListener {
                    if (!destroyed) { message = "Google 위치 공급자에 모의 위치를 적용하지 못했습니다."; stopSelf() }
                }
                handler.postDelayed(this, 1000)
            } catch (_: Exception) {
                message = "모의 위치 적용에 실패했습니다. 개발자 옵션의 모의 위치 앱 선택을 확인하세요."
                stopSelf()
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { message = "모의 위치를 중지했습니다."; stopSelf(); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel("mock-gps", "지도 위치 테스트", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 4201, Intent(this, MockGpsService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 4202, Intent(this, GpsTestActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION") val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "mock-gps") else Notification.Builder(this)
            startForeground(4200, builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("차량 좌표로 모의 GPS 테스트 중")
                .setContentText("조회한 위치를 최대 5분간 유지합니다.")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "중지", stop).build()).build())
            val fix = VehicleFix(intent?.getDoubleExtra("latitude", Double.NaN) ?: Double.NaN,
                intent?.getDoubleExtra("longitude", Double.NaN) ?: Double.NaN, intent?.getLongExtra("timestamp", 0) ?: 0).validate()
            handler.removeCallbacks(tick)
            removeProviders()
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                @Suppress("DEPRECATION", "WrongConstant")
                manager.addTestProvider(provider, false, false, false, false, false, false, false, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                installed.add(provider)
                manager.setTestProviderEnabled(provider, true)
            }
            currentFix = fix
            deadline = SystemClock.elapsedRealtime() + 300_000
            running = true
            message = "모의 GPS 적용 중 · 5분 후 자동 중지"
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
                message = "Google 위치 공급자 설정 중…"
                handler.postDelayed({ if (!destroyed && !fusedEnabled) { message = "모의 위치 설정 시간 초과"; stopSelf() } }, 10_000)
                fusedRequested = true
                fused.setMockMode(true).addOnSuccessListener {
                    if (destroyed) return@addOnSuccessListener
                    fusedEnabled = true
                    message = "GPS · 네트워크 · Google 모의 위치 적용 중 · 5분 후 자동 중지"
                    handler.post(tick)
                }.addOnFailureListener {
                    if (!destroyed) { message = "Google 모의 위치 설정 실패. 개발자 옵션의 모의 위치 앱을 확인하세요."; stopSelf() }
                }
            } else {
                message = "GPS · 네트워크 모의 위치 적용 중 (Google Play 서비스 없음)"
                handler.post(tick)
            }
        } catch (e: Exception) {
            message = if (e is SecurityException) "개발자 옵션 → 모의 위치 앱에서 Tesla GPS Sync를 선택하고 정확한 위치 권한을 허용하세요." else (e.message ?: "모의 GPS를 시작하지 못했습니다.")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun removeProviders() {
        installed.forEach { runCatching { manager.removeTestProvider(it) } }
        installed.clear()
    }
    override fun onDestroy() {
        destroyed = true
        if (message.contains("적용 중") || message.contains("설정 중")) message = "모의 위치를 중지했습니다."
        handler.removeCallbacksAndMessages(null)
        if (fusedRequested) runCatching { fused.setMockMode(false).addOnFailureListener { message = "Google 모의 위치 해제 실패. 개발자 옵션에서 모의 위치 앱을 해제하세요." } }.onFailure { message = "모의 위치 권한이 변경됐습니다. 개발자 옵션을 확인하세요." }
        fusedEnabled = false
        removeProviders()
        running = false
        currentFix = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
