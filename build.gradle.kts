plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("maven-publish")
}

group = "com.ktsdk"
version = "0.1.0"

repositories {
    mavenCentral()
}

publishing{
    publications{
        create<MavenPublication>("mavenJava"){from(components["java"])
        groupId="com.ktsdk.ai"
        artifactId="gemini-sdk"
        version="0.1.0"
        }
    }
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.21")
    implementation("io.ktor:ktor-client-core:3.2.2")
    implementation("io.ktor:ktor-client-cio:3.2.2")
    implementation("io.ktor:ktor-client-websockets:3.2.2")
//    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}