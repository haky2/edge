plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
}

group = "com.haky.edge"
version = "0.0.1"

application {
    mainClass.set("com.haky.edge.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.0"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Ktor client (한투 API 호출용)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.5.13")
}
