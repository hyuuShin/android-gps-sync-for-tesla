package dev.gpssync.for_tesla_probe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class GpsUiTest {
    @get:Rule val compose = createAndroidComposeRule<GpsTestActivity>()
    @Test fun locationTestRequiresCredentialsAndDoesNotStartAutomatically() {
        compose.onNodeWithText("차량 GPS 지도 테스트").assertIsDisplayed()
        compose.onNodeWithTag("fleet-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("mock-stop").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("fleet-apply").performScrollTo().assertIsNotEnabled()
    }
}
