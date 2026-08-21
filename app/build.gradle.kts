plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.robremy.hrblebridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.robremy.hrblebridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Twee varianten uit dezelfde broncode: "standard" is de gewone bridge-
    // only build (zoals nu), "withPwa" bundelt daarnaast de PWA-bestanden
    // (index.html/features.js/sw.js/manifest/icons) uit de HBmonitor-repo in
    // app/src/main/assets/www/ en serveert ze vanaf HrHttpServer zelf via
    // BuildConfig.BUNDLE_PWA (zie HrHttpServer.kt). Bedoeld voor apparaten
    // waar Chrome's Local Network Access-permissieprompt op vastloopt
    // (bevestigd op mobiele Chrome: permissiestatus blijft op "prompt"
    // hangen zonder ooit een popup te tonen) — door de PWA vanaf hetzelfde
    // private-netwerk-origin (http://<bridge-ip>:8787) te laden i.p.v.
    // vanaf de publieke GitHub Pages-origin, komt LNA nooit in beeld.
    // Clients zonder LNA-problemen blijven gewoon GitHub Pages gebruiken;
    // voor hen verandert er niets.
    flavorDimensions += "pwa"
    productFlavors {
        create("standard") {
            dimension = "pwa"
            buildConfigField("boolean", "BUNDLE_PWA", "false")
        }
        create("withPwa") {
            dimension = "pwa"
            applicationIdSuffix = ".withpwa"
            versionNameSuffix = "-withpwa"
            buildConfigField("boolean", "BUNDLE_PWA", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Embedded HTTP-server (HrHttpServer), vervangt het losse
    // hr_sync_server.py Termux-proces. org.json (JSONObject/JSONArray)
    // komt al mee met de Android SDK, dus geen extra JSON-dependency nodig.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
