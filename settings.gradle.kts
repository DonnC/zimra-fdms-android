pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The root project is the library itself. The simulator is an optional
// companion library for development and tests; the example app consumes both.
rootProject.name = "zimra-fdms-android"
include(":simulator")
