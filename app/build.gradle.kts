import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.personal.doomblocker"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.personal.doomblocker"
        minSdk = 34
        targetSdk = 36
        versionCode = 24
        versionName = "3.1.3"
        val syncConfig = Properties().apply {
            val configFile = rootProject.file("sync.properties")
            if (configFile.isFile) configFile.inputStream().use(::load)
        }
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "SYNC_URL", quoted(syncConfig.getProperty("url", "")))
        buildConfigField("String", "SYNC_PUBLIC_KEY", quoted(syncConfig.getProperty("publishableKey", "")))
    }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "mode"
    productFlavors {
        create("production") {
            dimension = "mode"
            resValue("bool", "lab_mode", "false")
            resValue("integer", "time_unit_millis", "60000")
            resValue("string", "app_name", "Doom Blocker")
        }
        create("lab") {
            dimension = "mode"
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            resValue("bool", "lab_mode", "true")
            resValue("integer", "time_unit_millis", "10000")
            resValue("string", "app_name", "Doom Blocker Lab")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        getByName("debug") {
            val suppliedPath = providers.environmentVariable("DOOM_BLOCKER_KEYSTORE").orNull
            val workspaceKey = rootProject.file("../../work/toolchain/android-user/debug.keystore")
            val selectedKey = suppliedPath?.let { rootProject.file(it) } ?: workspaceKey.takeIf { it.isFile }
            if (selectedKey != null) {
                require(selectedKey.isFile) { "DOOM_BLOCKER_KEYSTORE dosyası bulunamadı: $selectedKey" }
                storeFile = selectedKey
                storePassword = providers.environmentVariable("DOOM_BLOCKER_STORE_PASSWORD").orNull ?: "android"
                keyAlias = providers.environmentVariable("DOOM_BLOCKER_KEY_ALIAS").orNull ?: "androiddebugkey"
                keyPassword = providers.environmentVariable("DOOM_BLOCKER_KEY_PASSWORD").orNull ?: "android"
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    lint { abortOnError = true }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
