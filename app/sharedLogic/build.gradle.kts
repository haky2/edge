import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization) // @Serializable 처리용 컴파일러 플러그인
    alias(libs.plugins.sqldelight)           // .sq → 타입세이프 Kotlin 쿼리 코드 생성
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }
    
    androidLibrary {
       namespace = "com.haky.edge.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        commonMain.dependencies {
            // 네트워킹: 백엔드(/quote 등) 호출. 엔진은 플랫폼별로 아래에서 주입.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // 로컬 DB: 공통 런타임(생성된 쿼리 API가 의존)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // 플랫폼별 Ktor 엔진 + SQLDelight 드라이버.
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.driver.native)   // iOS SQLite 드라이버
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.driver.android)   // Android SQLite 드라이버
        }
    }
}

// SQLDelight: .sq 스키마 → 타입세이프 코드 생성. DB 클래스명/패키지 지정.
sqldelight {
    databases {
        create("EdgeDb") {
            packageName.set("com.haky.edge.db")
        }
    }
}