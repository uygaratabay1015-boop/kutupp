pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Use the named RepositoriesMode to avoid unresolved reference issues in some IDE setups
    repositoriesMode.set(org.gradle.api.initialization.repositories.RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kutup_navigasyon"
