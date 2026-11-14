import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Opt-in features for personal builds, off in the public release: set them in local.properties or
 * with -P (e.g. `pitstop.youtubeDownloads=true`).
 * - pitstop.youtubeDownloads: allow saving YouTube / YouTube Music videos to the device.
 * - pitstop.googleCloud: Google Drive features that need your own Google Cloud OAuth client (see README).
 */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun optIn(name: String): Boolean = (findProperty(name) ?: localProps.getProperty(name))?.toString()?.toBoolean() ?: false

android {
    namespace = "app.pitstop.video"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.pitstop.video"
        // Android 8.0+: older phones, tablets and most aftermarket Android head units.
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0"
        buildConfigField("boolean", "YOUTUBE_DOWNLOADS", optIn("pitstop.youtubeDownloads").toString())
        buildConfigField("boolean", "GOOGLE_CLOUD", optIn("pitstop.googleCloud").toString())
    }

    // Public releases are signed with a fixed key from the environment (CI secrets), so updates install
    // over older versions. Without it, release builds fall back to the debug key for personal use.
    val releaseKeystore = System.getenv("PITSTOP_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("PITSTOP_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PITSTOP_KEY_ALIAS")
            keyPassword = System.getenv("PITSTOP_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by NewPipeExtractor.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.car.app)
    implementation(libs.car.app.projected)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.newpipe.extractor)
    implementation(libs.nanojson)
    implementation(libs.play.services.auth)
    implementation(libs.zxing.core)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
