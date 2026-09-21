package dev.gpssync.for_tesla_probe

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class TeslaAuthStorageTest {
    @Test fun encryptedPersistenceReloadAndClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "auth_test_${UUID.randomUUID()}"
        val settings = TeslaAuthSettings(context, name)
        try {
            settings.save(TeslaAuthData("test-access-secret", "test-refresh-secret", "AAAAAAAAAAAAAAAAA", 123L))
            val reloaded = TeslaAuthSettings(context, name).load()
            assertEquals("test-access-secret", reloaded.data.accessToken)
            assertEquals("test-refresh-secret", reloaded.data.refreshToken)
            assertEquals("AAAAAAAAAAAAAAAAA", reloaded.data.vin)
            val persisted = context.getSharedPreferences(name, 0).all.toString()
            assertFalse(persisted.contains("test-access-secret"))
            assertFalse(persisted.contains("test-refresh-secret"))
            assertFalse(persisted.contains("AAAAAAAAAAAAAAAAA"))
            settings.clear()
            assertEquals("", TeslaAuthSettings(context, name).load().data.accessToken)
            assertFalse(settings.replaceIfCurrent(reloaded.revision, reloaded.data))
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${name}_aes_v1") }
        }
    }
}
