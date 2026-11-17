plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by GitHub Actions: every build gets a higher version than the previous one
val verCode: Int = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
val repo: String = System.getenv("GITHUB_REPOSITORY") ?: ""
val updateUrl: String =
    if (repo.isEmpty()) "" else "https://github.com/$repo/releases/latest/download/update.json"

android {
    namespace = "com.zeta.board"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zeta.board"
        minSdk = 26
        targetSdk = 34
        versionCode = verCode
        versionName = "1.0.$verCode"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    buildFeatures { buildConfig = true }

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
}
