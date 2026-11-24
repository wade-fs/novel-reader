plugins {
    id("com.android.application")
}

android {
    namespace = "com.novel.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novel.reader"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: System.getenv("KEY_ALIAS") ?: "androidapk"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD") ?: "jjchen"
            val keyPath = System.getenv("SIGNING_KEY_FILE") ?: System.getenv("KEY_FILE") ?: "/home/wade/.ssh/androidapk.jks"
            storeFile = file(keyPath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: System.getenv("STORE_PASSWORD") ?: "jjchen"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
