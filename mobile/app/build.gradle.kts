plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.paparazzi)
}

fun prop(name: String, default: String = ""): String = (project.findProperty(name) as String?) ?: default

android {
    namespace = "com.inditech.dpc"
    compileSdk = 35

    defaultConfig {
        // White-label pipeline: -PapplicationId=com.tenant.dpc -PtenantName="Tenant" -PtenantPrimaryColor=#RRGGBB
        applicationId = prop("applicationId", "com.inditech.dpc")
        minSdk = 26
        targetSdk = 35
        versionCode = prop("versionCode", "1").toInt()
        versionName = prop("versionName", "1.0.0")

        buildConfigField("String", "API_BASE_URL", "\"${prop("apiBaseUrl", "https://api.device-platform.com/")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("firebaseProjectId")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("firebaseAppId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("firebaseApiKey")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${prop("firebaseSenderId")}\"")
        resValue("string", "app_name", prop("tenantName", "Inditech Finance"))
        resValue("color", "brand_primary", prop("tenantPrimaryColor", "#0B5FFF"))
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("DPC_KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("DPC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DPC_KEY_ALIAS")
                keyPassword = System.getenv("DPC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("DPC_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.firebase.messaging)
    implementation(libs.paho.mqtt)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
