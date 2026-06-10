pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":app")
