plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    id("kotlin-parcelize")
    id("com.google.gms.google-services")
    id("androidx.navigation.safeargs")
}

group = "com.moviebox"
version = "1.0.0"

android {
    namespace = "com.moviebox"

    compileSdk = 34

    defaultConfig {
        applicationId = "com.moviebox"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        javaCompileOptions {
            annotationProcessorOptions {
                arguments(mapOf(
                    "dagger.hilt.android.testing" to true,
                    "dagger.hilt.android.testing" to true
                ))
            }
        }

        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalStdlibApi"
            )
        }

        buildFeatures {
            compose = true
        }

        composeOptions {
            kotlinCompilerExtensionVersion = "1.5.11"
        }

        buildTypes {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        packaging {
            resources {
                excludes += setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE",
                    "META-INF/NOTICE",
                    "META-INF/LICENSE.txt",
                    "META-INF/LICENSE.md",
                    "META-INF/license.txt",
                    "META-INF/license.md"
                )
            }
        }
    }

    dependencies {
        // Android framework
        implementation("androidx.core:core-ktx:1.13.0")
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
        implementation("androidx.activity:activity-ktx:1.7.2")
        implementation("androidx.fragment:fragment-ktx:1.6.0")
        implementation("androidx.preference:preference:1.2.0")
        implementation("androidx.cardview:cardview:1.0.0")
        implementation("androidx.recyclerview:recyclerview:1.3.1")
        implementation("com.google.android.material:material:1.12.0")

        // Hilt dependency injection
        implementation("com.google.dagger:hilt-android:2.48")
        kapt("com.google.dagger:hilt-android-compiler:2.48")
        implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

        // Jetpack Compose UI
        implementation("androidx.compose.ui:ui:1.6.7")
        implementation("androidx.compose.material:material:1.6.7")
        implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
        implementation("androidx.compose.animation:animation:1.6.7")
        implementation("androidx.compose.foundation:foundation:1.6.7")
        implementation("androidx.compose.foundation:foundation-layout:1.6.7")
        implementation("androidx.compose.graphics:graphics:1.6.7")
        implementation("androidx.compose.ui:ui-graphics:1.6.7")
        implementation("androidx.compose.ui:ui-text:1.6.7")
        implementation("androidx.compose.ui:ui-unit:1.6.7")
        implementation("androidx.compose.ui:ui-layout:1.6.7")
        implementation("androidx.compose.ui:ui-viewmodel:1.6.7")
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")

        // ExoPlayer for media playback
        implementation("androidx.media3:media3-exoplayer:1.3.2")

        // Coroutines and Flow
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

        // SQLite / Room for persistent storage
        implementation("androidx.room:room-ktx:2.5.2")
        implementation("androidx.room:room-rxjava2:2.5.2")
        kapt("androidx.room:room-compiler:2.5.2")

        // Photo loading (posters)
        implementation("com.github.bumptech.glide:glide:4.16.0")
        kapt("com.github.bumptech.glide:compiler:4.16.0")

        // JSON handling
        implementation("com.google.code.gson:gson:2.10.1")

        // Testing
        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.ext:ext:1.1.2")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
        androidTestImplementation("androidx.test:rule:1.5.0")
    }
}