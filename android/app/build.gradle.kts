import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Public build configuration only. Never put tokens or CLIENT_SECRET here.
val teslaDefaults = Properties().apply {
    rootProject.file("../config/tesla.defaults.properties").inputStream().use { load(it) }
}
val teslaLocal = Properties().apply {
    val file = rootProject.file("tesla.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun teslaValue(key: String): String = providers.environmentVariable(key).orNull
    ?: teslaLocal.getProperty(key) ?: teslaDefaults.getProperty(key).orEmpty()
val teslaKeys = teslaDefaults.stringPropertyNames()
val teslaValues = teslaKeys.associateWith(::teslaValue)
for ((key, value) in teslaValues) {
    require(value.none { it == '\n' || it == '\r' || it.code < 32 }) { "$key must be a single line" }
    if (key.endsWith("_URL") && key != "TESLA_LOGIN_URL") require(value.isNotBlank()) { "$key must not be blank" }
    if (key.endsWith("_URL") && value.isNotEmpty()) {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$key must be an HTTPS URL without credentials, query or fragment"
        }
        if (key.startsWith("TESLA_FLEET_")) require(uri.path.isNullOrEmpty()) { "$key must be an origin without trailing slash" }
    }
}
require(teslaValues["TESLA_DEFAULT_REGION"] in listOf("NA", "EU")) { "TESLA_DEFAULT_REGION must be NA or EU" }
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.gpssync.for_tesla_probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.gpssync.for_tesla_probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        teslaValues.forEach { (key, value) -> buildConfigField("String", key, javaString(value)) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.fragment:fragment:1.8.9")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
