import java.util.Base64

plugins {
    id("com.android.library") version "8.13.0"
    kotlin("android") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.choffmann"
version = "1.0.0"

val libraryArtifactId = "chat-ws-android"
val projectUrl = "https://github.com/choffmann/chat-ws-android"

android {
    namespace = "io.github.choffmann.chatwsandroid"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

mavenPublishing {
  coordinates("io.github.choffmann", "chat-ws-android", "1.0.0")

  pom {
    name.set(libraryArtifactId)
    description.set("Ktor WebSocket client for the chat-room server")
    inceptionYear.set("2025")
    url.set("https://github.com/choffmann/chat-ws-android/")
    licenses {
      license {
        name.set("MIT")
        url.set("https://opensource.org/license/mit")
      }
    }
    developers {
      developer {
        id.set("choffmann")
        name.set("Cedrik Hoffmann")
        url.set("https://github.com/choffmann/")
      }
    }
    scm {
      url.set("https://github.com/choffmann/chat-ws-android/")
      connection.set("scm:git:git://github.com/choffmann/chat-ws-android.git")
      developerConnection.set("scm:git:ssh://git@github.com/choffmann/chat-ws-android.git")
    }
  }
}

val ktor = "3.4.3"
dependencies {
    api("io.ktor:ktor-client-core:$ktor")
    api("io.ktor:ktor-client-okhttp:$ktor")
    api("io.ktor:ktor-client-websockets:$ktor")
    api("io.ktor:ktor-client-content-negotiation:$ktor")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    api("io.ktor:ktor-client-logging:$ktor")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
}
