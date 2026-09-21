package dev.gpssync.for_tesla_probe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class TeslaAuthUiTest {
    @get:Rule val compose = createAndroidComposeRule<TeslaAuthActivity>()
    @Test fun credentialFieldsAndActionsAreReachable() {
        compose.onNodeWithTag("auth-access").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth-vin").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth-refresh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth-save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth-clear").performScrollTo().assertIsDisplayed()
    }
}
