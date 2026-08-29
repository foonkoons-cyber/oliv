plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.depthmaker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.depthmaker.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"

        // Empty by default so "not configured" is distinguishable from "server
        // down" — a placeholder host here just produces a DNS failure that
        // looks like the phone has no internet.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"\"")
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("DEPTHMAKER_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("DEPTHMAKER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEPTHMAKER_KEY_ALIAS")
                keyPassword = System.getenv("DEPTHMAKER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the release key when the CI env vars are present,
            // otherwise with the debug key so the APK is always installable.
            signingConfig = if (System.getenv("DEPTHMAKER_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    debugImplementation(libs.androidx.ui.tooling)
}
