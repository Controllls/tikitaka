plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    useLibrary("android.car", false)
    namespace = "com.example.hd_project"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.hd_project"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
        buildConfigField("boolean", "PLAYBACK_DIAGNOSTICS", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Isolated instrumentation installs preserve ongoing recordings in the user's app.
            if (providers.gradleProperty("isolatedVerification").orNull == "true") applicationIdSuffix = ".verification"
            buildConfigField("String", "MOMO_API_URL", "\"${providers.gradleProperty("momoApiUrl").getOrElse("http://10.0.2.2:8765/")}\"")
        }
        release {
            buildConfigField("String", "MOMO_API_URL", "\"${providers.gradleProperty("momoApiUrl").getOrElse("")}\"")
            optimization {
                // Scoped optimization produced an IllegalAccessError in Kotlin text
                // classes during WorkManager startup on Pleos Android 14.
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(libs.litert)
    implementation(libs.pleos.vehicle)
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    listOf("transformer", "effect", "exoplayer", "ui").forEach {
        implementation("androidx.media3:media3-$it:1.9.0")
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
