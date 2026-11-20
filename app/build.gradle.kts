plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Push notifications (Firebase Cloud Messaging) switch on when app/google-services.json is present.
// Without that file the app still builds and works; it just uses the 15-minute update check only.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Set by GitHub Actions: every build gets a higher version than the previous one
val verCode: Int = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
// Sticker API key. Override from CI with -PstickerKey=... (GitHub secret STICKER_KEY) to keep it out of the repo.
val stickerKey: String = (project.findProperty("stickerKey") as String?)?.takeIf { it.isNotBlank() } ?: "wxa_f_26c1633298"
// Wallpaper server address (the zeta-wallpaper-server you deploy). Must be https://.
// Set it here, or from CI with -PwallpaperUrl=... (GitHub repository variable WALLPAPER_URL).
val wallpaperUrl: String = ((project.findProperty("wallpaperUrl") as String?)?.takeIf { it.isNotBlank() } ?: "https://YOUR-WALLPAPER-SERVER.example.com")
    .trim().trimEnd('/').replace("\"", "")
val repo: String = (project.findProperty("githubRepo") as String?) ?: System.getenv("GITHUB_REPOSITORY") ?: ""

android {
    namespace = "com.zeta.board"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zeta.board"
        minSdk = 26
        targetSdk = 34
        versionCode = verCode
        versionName = "1.0.$verCode"
        buildConfigField("String", "REPO", "\"$repo\"")
        buildConfigField("String", "STICKER_KEY", "\"$stickerKey\"")
        buildConfigField("String", "WALLPAPER_URL", "\"$wallpaperUrl\"")
    }

    buildFeatures { buildConfig = true }

    // keep the emoji font uncompressed (PNG data does not compress; lets it be read quickly)
    androidResources { noCompress += "ttf" }

    // Same key on every build, so new APKs can update the installed app
    signingConfigs {
        create("zeta") {
            storeFile = file("$rootDir/zeta.keystore")
            storeType = "pkcs12"
            storePassword = "zetaboard"
            keyAlias = "zeta"
            keyPassword = "zetaboard"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("zeta") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zeta")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
}
