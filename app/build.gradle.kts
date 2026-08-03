import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// AGP 8.5.2 + Kotlin 1.9.24 + Compose compiler 1.5.14 are not certified
// on JDK 21 (which is what the Android Studio JBR ships). Pin the Kotlin
// toolchain to 17 so Gradle auto-downloads the right JDK.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.expenselens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.expenselens"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    // Read the OpenAI key + model from local.properties (gitignored). If absent,
    // the build still succeeds but the smart-extraction path is a no-op at
    // runtime — the on-device parser is the always-on fallback.
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { stream -> localProps.load(stream) }
    }
    val openaiKey = (localProps.getProperty("openai.api.key") ?: "").trim()
    val openaiModel = (localProps.getProperty("openai.model") ?: "gpt-4o").trim()
    val paddleProductId = (localProps.getProperty("paddle.product.id") ?: "").trim()
    val paddlePriceId = (localProps.getProperty("paddle.price.id") ?: "").trim()
    // Paddle Billing v2 doesn't support hosted checkouts for native mobile
    // apps — the dashboard gates those to "app-to-web" and "non-mobile
    // desktop" only. We open the marketing-site pricing page in a Custom
    // Tab and the Paddle.js inline checkout handles the rest. Override
    // here for staging / alt deployments.
    val paddlePricingUrl = (localProps.getProperty("paddle.pricing.url")
        ?: "https://prasannaverse13.github.io/pricing.html").trim()
    val paddlePriceUsd = (localProps.getProperty("paddle.price.usd") ?: "4.99").trim()
    val paddlePortalUrl = (localProps.getProperty("paddle.portal.url") ?: "").trim()
    // Optional server-side Paddle Billing v2 API key (pdl_…). When
    // set, the Settings screen offers a "Verify with Paddle" action
    // that confirms an active subscription against Paddle's servers
    // and flips is_premium — useful when the deep-link return from
    // success.html was blocked or missed.
    val paddleApiKey = (localProps.getProperty("paddle.api.key") ?: "").trim()

    // Supabase — primary data store as of v1.1. Replaces Google Drive.
    // Both URL and anon key are public-by-design (the anon key is
    // embedded in every Supabase client app; RLS is the security
    // boundary, not the key secrecy).
    val supabaseUrl = (localProps.getProperty("supabase.url") ?: "").trim()
    val supabaseAnonKey = (localProps.getProperty("supabase.anon.key") ?: "").trim()
    // Google Web OAuth Client ID — for the Supabase signInWithIdToken
    // (Google) flow. Blank falls back to "no cloud sync" mode (local
    // Room DB only, same as the pre-Supabase behaviour).
    val googleWebClientId = (localProps.getProperty("google.web.client.id") ?: "").trim()

    defaultConfig {
        // (block above already set applicationId etc.; we add buildConfigField here)
        buildConfigField("String", "OPENAI_API_KEY", "\"$openaiKey\"")
        buildConfigField("String", "OPENAI_MODEL", "\"$openaiModel\"")
        buildConfigField("String", "PADDLE_PRODUCT_ID", "\"$paddleProductId\"")
        buildConfigField("String", "PADDLE_PRICE_ID", "\"$paddlePriceId\"")
        buildConfigField("String", "PADDLE_PRICING_URL", "\"$paddlePricingUrl\"")
        buildConfigField("String", "PADDLE_PRICE_USD", "\"$paddlePriceUsd\"")
        buildConfigField("String", "PADDLE_PORTAL_URL", "\"$paddlePortalUrl\"")
        buildConfigField("String", "PADDLE_API_KEY", "\"$paddleApiKey\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/license.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/notice.txt",
            "META-INF/ASL2.0",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.tesseract4android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.coil.compose)

    implementation(libs.pdfbox.android)
    implementation(libs.poi.ooxml)
    implementation(libs.opencsv)

    implementation(libs.androidx.datastore.preferences)

    // Splash screen API
    implementation(libs.androidx.core.splashscreen)

    // Google Sign-In (used for Google Drive backup)
    implementation(libs.play.services.auth)

    // Encrypted storage for OAuth tokens
    implementation(libs.androidx.security.crypto)

    // Paddle Premium subscription uses a hosted checkout (Chrome Custom Tab)
    // — no SDK needed. The deep link return is handled in MainActivity.
    implementation(libs.androidx.browser)

    // Supabase — primary data store. BOM aligns module versions.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.auth)
    // Ktor + serialization — transitive, but pinned to match supabase-kt 2.6.0.
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
