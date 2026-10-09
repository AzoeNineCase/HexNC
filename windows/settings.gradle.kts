pluginManagement {
    repositories {
        // Maven Central first: this machine cannot reach plugins.gradle.org (connection refused),
        // and all four plugins used here publish their markers to Central anyway.
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "HexNC-For-Windows"
