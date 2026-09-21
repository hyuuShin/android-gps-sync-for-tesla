package dev.gpssync.for_tesla_probe

import org.junit.Assert.*
import org.junit.Test

class TeslaAuthTest {
    private class Store(data: TeslaAuthData) : TeslaAuthStore {
        var current = StoredTeslaAuth(data, "initial")
        override fun load() = current
        override fun replaceIfCurrent(revision: String, data: TeslaAuthData): Boolean {
            if (current.revision != revision) return false
            current = StoredTeslaAuth(data, "updated")
            return true
        }
    }
    private val expired = TeslaAuthData("old-access", "old-refresh", "AAAAAAAAAAAAAAAAA", 1)
    private fun rotated(old: TeslaAuthData) = TeslaAuthData.refreshed("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""", old, System.currentTimeMillis())
    @Test fun expiryRotatesBothTokensPreservesVinAndAvoidsDuplicateRefresh() {
        val store = Store(expired); var calls = 0
        val manager = TeslaAuthManager(store) { calls++; rotated(it) }
        val result = manager.credentials().data
        assertEquals("new-access", result.accessToken)
        assertEquals("new-refresh", result.refreshToken)
        assertEquals(expired.vin, result.vin)
        manager.credentials("old-access")
        assertEquals(1, calls)
    }
    @Test fun clearDuringRefreshCannotRestoreCredentials() {
        val store = Store(expired)
        val manager = TeslaAuthManager(store) {
            store.current = StoredTeslaAuth(TeslaAuthData(), "cleared")
            rotated(it)
        }
        assertThrows(IllegalStateException::class.java) { manager.credentials() }
        assertEquals("", store.load().data.accessToken)
    }
    @Test fun failedRefreshPreservesSavedCredentials() {
        val store = Store(expired)
        assertThrows(IllegalStateException::class.java) { TeslaAuthManager(store) { error("offline") }.credentials() }
        assertSame(expired, store.load().data)
    }
    @Test fun rejectedTokenRefreshesEvenWithUnknownExpiry() {
        val store = Store(TeslaAuthData("access", "refresh"))
        assertEquals("new-access", TeslaAuthManager(store, ::rotated).credentials("access").data.accessToken)
    }
    @Test fun importsBundleAndDoesNotReuseOldRefreshToken() {
        val imported = TeslaAuthData.import("""{"access_token":"new","refresh_token":"new-r","expires_at":123456}""", "", "aaaaaaaaaaaaaaaaa", expired) { 0 }
        assertEquals("new-r", imported.refreshToken)
        assertEquals(expired.vin, imported.vin)
        assertEquals(123456L, imported.expiresAt)
        assertEquals("", TeslaAuthData.import("new-access", "", "", expired) { 0 }.refreshToken)
    }
    @Test fun importedClientIdOverridesBuildDefaultAndMissingIdCannotRefresh() {
        val data = TeslaAuthData.import("""{"access_token":"new","refresh_token":"new-r","client_id":"another-application"}""", "", "", expired) { 0 }
        assertEquals("another-application", data.clientId)
        assertEquals("another-application", rotated(data).clientId)
        assertEquals("another-application", TeslaAuthData.fromJson(data.json()).clientId)
        assertThrows(IllegalStateException::class.java) {
            TeslaTokenClient.refresh(TeslaAuthData("test", "test-refresh", clientId = ""))
        }
    }
}
