import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load release keystore signing configuration (with environment variable fallback)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val releaseStoreFilePath: String? = keystoreProperties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
val releaseStoreFile: File? = releaseStoreFilePath?.let { rootProject.file(it) }
val releaseStorePassword: String? = keystoreProperties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
val releaseKeyPassword: String? = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")

android {
    namespace = "com.gallery.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gallery.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null && releaseStoreFile.exists()) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
}

dependencies {
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-security"))
    implementation(project(":feature:feature-gallery"))
    implementation(project(":feature:feature-viewer"))
    implementation(project(":feature:feature-vault"))
    implementation(project(":feature:feature-editor"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.biometric)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coil (image loading — app-wide singleton)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
