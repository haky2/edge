import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

val keystoreProps = Properties()
val keystorePropsFile = file("keystore/keystore.properties")
if (keystorePropsFile.exists()) keystoreProps.load(keystorePropsFile.inputStream())

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(projects.sharedUI)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.compose.uiToolingPreview)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.haky.edge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            storeFile = if (keystoreProps.isNotEmpty()) file(keystoreProps["storeFile"] as String) else null
            storePassword = keystoreProps["storePassword"] as? String
            keyAlias = keystoreProps["keyAlias"] as? String
            keyPassword = keystoreProps["keyPassword"] as? String
        }
    }

    defaultConfig {
        applicationId = "com.haky.edge"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.2"

        val baseUrl = localProps.getProperty("EDGE_BASE_URL", "")
        val apiToken = localProps.getProperty("EDGE_API_TOKEN", "")
        buildConfigField("String", "EDGE_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "EDGE_API_TOKEN", "\"$apiToken\"")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
