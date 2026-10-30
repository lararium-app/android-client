plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningPropertyNames = listOf(
    "LARARIUM_UPLOAD_STORE_FILE",
    "LARARIUM_UPLOAD_STORE_PASSWORD",
    "LARARIUM_UPLOAD_KEY_ALIAS",
    "LARARIUM_UPLOAD_KEY_PASSWORD"
)
val configuredReleaseSigningProperties = releaseSigningPropertyNames.filter {
    providers.gradleProperty(it).isPresent
}

if (configuredReleaseSigningProperties.isNotEmpty() &&
    configuredReleaseSigningProperties.size != releaseSigningPropertyNames.size
) {
    val missingProperties = releaseSigningPropertyNames - configuredReleaseSigningProperties.toSet()
    throw GradleException(
        "Release signing is incomplete. Configure all signing properties or none for a local build. " +
            "Missing: ${missingProperties.joinToString()}"
    )
}

val hasReleaseSigning = configuredReleaseSigningProperties.size == releaseSigningPropertyNames.size

android {
    namespace = "app.lararium"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lararium"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.9.10-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        testOptions {
            // Pure-JVM tests exercise classes that log via android.util.Log;
            // unmocked framework methods must no-op, not crash the runner.
            unitTests.isReturnDefaultValues = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(providers.gradleProperty("LARARIUM_UPLOAD_STORE_FILE").get())
                storePassword = providers.gradleProperty("LARARIUM_UPLOAD_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("LARARIUM_UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("LARARIUM_UPLOAD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            // When the stable upload key is provided (CI), debug builds are
            // signed with it too, so installed builds can update in place —
            // machine-local debug keys change per machine and break updates.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Allows local release builds without checking a private keystore into the project.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

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
        jniLibs {
            useLegacyPackaging = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.28.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    // JVM unit tests otherwise get android.jar's stubbed org.json (every call
    // throws "not mocked"); this puts the real implementation on the TEST
    // classpath only — the APK keeps the platform one.
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
