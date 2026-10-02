pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Chaquopy plugin comes from here
        maven { url = uri("https://chaquo.com/maven") }
    }
}
rootProject.name = "OpenpilotReplay"
include(":app")