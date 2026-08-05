pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":app")
include(":ssiv-pixel")
include(":indexed-jpeg")
include(":indexed-png")
include(":indexed-tiff")
include(":indexed-webp")
include(":indexed-raw")
project(":ssiv-pixel").projectDir = file("third_party/ssiv-pixel")
