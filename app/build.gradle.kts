import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Maps key lives in local.properties (not committed). Empty key = map tiles disabled.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.rameez.fit3zone"
    compileSdk = 37

    defaultConfig {
        // Testers' builds. Debug builds keep the original id (see androidComponents below).
        applicationId = "com.rameez.taal"
        // Android 12+: BLUETOOTH_SCAN can be declared neverForLocation, so no location permission.
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = "1.2.0-beta"
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("MAPS_API_KEY", "")
        // Strava API app (strava.com/settings/api). With STRAVA_TOKEN_URL set, logins go through the token Worker
        // (strava-worker/) and the secret stays off the phone; without it, debug builds fall back to the secret.
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"${localProps.getProperty("STRAVA_CLIENT_ID", "")}\"")
        buildConfigField("String", "STRAVA_TOKEN_URL", "\"${localProps.getProperty("STRAVA_TOKEN_URL", "")}\"")
        // Where "Send feedback" addresses its email (empty = the user picks any app/contact).
        // version.json for testers' "Update available" card (data/Updates.kt); empty = no checks.
        buildConfigField("String", "UPDATE_URL", "\"${localProps.getProperty("UPDATE_URL", "")}\"")
        buildConfigField("String", "FEEDBACK_EMAIL", "\"${localProps.getProperty("FEEDBACK_EMAIL", "")}\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"${localProps.getProperty("STRAVA_CLIENT_SECRET", "")}\"")
    }

    // Release key from local.properties (RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD).
    // Without it, assembleRelease still builds, unsigned.
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // The developer's own phone: same app id as before the rename (history, Strava login, Health Connect
            // records and the Maps key restriction stay valid), its own name and Strava return link.
            resValue("string", "app_name", "Taal dev")
            manifestPlaceholders["redirectScheme"] = "fit3zone"
            buildConfigField("String", "STRAVA_REDIRECT_URI", "\"fit3zone://localhost/strava\"")
        }
        release {
            resValue("string", "app_name", "Taal")
            manifestPlaceholders["redirectScheme"] = "taal"
            buildConfigField("String", "STRAVA_REDIRECT_URI", "\"taal://localhost/strava\"")
            // R8: 31 MB → small enough for the download site (Cloudflare Pages: 25 MB per file). Check a release
            // install on a phone after dependency updates (reflection-heavy code would show up as crashes there).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            // Never ship the Strava secret to other people's phones: release builds must use the token Worker.
            buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { it.applicationId.set("com.rameez.fit3zone") }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // GPS runs + route maps
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.maps.android:maps-compose:9.0.1")
    // Daily health data that Samsung Health shares through Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
