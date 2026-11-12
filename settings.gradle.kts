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
        maven {
            url = uri("https://sadami3066.github.io/deepdeep-maven")
            content {
                includeGroup("io.github.sadami3066.deepdeep")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "materials"
