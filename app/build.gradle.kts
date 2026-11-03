import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes ONLY from the environment (GitHub Secrets). Nothing secret is stored in the repo.
val ksFile: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() && File(it).exists() }
val hasSigning = ksFile != null && !System.getenv("KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("KEY_ALIAS").isNullOrBlank() && !System.getenv("KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "app.codeedit"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.codeedit"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.3"
        externalNativeBuild {
            cmake {
                // native ABIs are limited here (not via ndk.abiFilters, which AGP rejects together with splits.abi)
                abiFilters("arm64-v8a", "armeabi-v7a")
                cppFlags += listOf("-std=c++17", "-O2", "-fvisibility=hidden")
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(8) ?: "local"}\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = File(ksFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // the same key as release when CI provides one: a fresh debug key on every CI run would block installing updates over the old build
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

// host build of the TextMate engine for the JVM tests (exported by tools/run_native_tests.sh)
tasks.withType<Test>().configureEach {
    System.getenv("CODEEDIT_HOST_LIB")?.let { systemProperty("codeedit.native.lib", it) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
