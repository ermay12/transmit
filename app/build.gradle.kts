plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

/** Commits up to HEAD; 1 outside a git checkout. GitHub Actions needs a full clone (fetch-depth: 0) for this. */
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "app.transmit"
    compileSdk = 36

    defaultConfig {
        // The Google Play listing's package. The Kotlin namespace stays app.transmit.
        applicationId = "com.ermay12.transmit"
        minSdk = 31
        targetSdk = 36
        // Counts up with every commit, so each build installs over the last, whether built locally or by GitHub Actions.
        versionCode = commitCount
        versionName = "1.0.0-beta8"
    }

    signingConfigs {
        getByName("debug") {
            // GitHub Actions points this at the same keystore as local builds, which use ~/.android/debug.keystore.
            providers.environmentVariable("TRANSMIT_KEYSTORE").orNull?.let { storeFile = file(it) }
        }
        // The Google Play upload key: set TRANSMIT_UPLOAD_KEYSTORE (a PKCS12 file with alias "upload") and
        // TRANSMIT_UPLOAD_PASSWORD to sign release builds for Play. Without them, release builds use the debug key.
        providers.environmentVariable("TRANSMIT_UPLOAD_KEYSTORE").orNull?.let { path ->
            create("upload") {
                storeFile = file(path)
                storePassword = providers.environmentVariable("TRANSMIT_UPLOAD_PASSWORD").get()
                keyAlias = "upload"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // For Google Play, the upload key. Otherwise the debug key, so sideloaded builds and `installRelease` work.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The Google Messages protocol (app/src/main/proto/gm, from libgm). Full protobuf-java rather than
// lite: the pblite JSON encoding the servers use is driven by field descriptors.
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.exifinterface)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.anthropic.java)
    implementation(libs.protobuf.java)
    implementation(libs.zxing.core)
    implementation(libs.androidx.webkit)
    // Subscriptions. Google Play only offers subscription setup for apps whose uploaded build includes this library.
    implementation(libs.billing)
    // The free trial's device check (a standard request whose requestHash binds ANDROID_ID).
    implementation(libs.play.integrity)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
