plugins {
    id("com.android.application")
}

val geckoviewVersion: String = providers.gradleProperty("geckoviewVersion").get()
// "+" (latest) is treated as a modern version.
val geckoMajor = geckoviewVersion.substringBefore('.').toIntOrNull() ?: 999

android {
    namespace = "com.taqi.hawall"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.taqi.hawall"
        // GeckoView 144+ requires Android 8 (API 26); 143 and older run on API 21.
        minSdk = if (geckoMajor >= 144) 26 else 21
        // Kept at 33 on purpose: avoids Android 14 foreground-service-type rules; app is sideloaded.
        targetSdk = 33
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

    }

    // One APK per ABI instead of a fat APK: each carries only one copy of Gecko's native libs.
    // Fire tablets usually run a 32-bit userland even on 64-bit chips → armeabi-v7a is the default pick.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "hawall"
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoviewVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // websocket for HA commands
}
