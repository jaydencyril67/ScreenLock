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

        // One UI 8 design system and its Samsung Experience Support Library (SESL) modules.
        // Credentials are supplied by GitHub Actions secrets; never store tokens in source.
        maven {
            name = "OneUISESLAndroidX"
            url = uri("https://maven.pkg.github.com/tribalfs/sesl-androidx")
            credentials {
                username = providers.gradleProperty("ONEUI_GH_USERNAME")
                    .orElse(providers.environmentVariable("ONEUI_GH_USERNAME")).getOrElse("")
                password = providers.gradleProperty("ONEUI_GH_TOKEN")
                    .orElse(providers.environmentVariable("ONEUI_GH_TOKEN")).getOrElse("")
            }
        }
        maven {
            name = "OneUISESLMaterial"
            url = uri("https://maven.pkg.github.com/tribalfs/sesl-material-components-android")
            credentials {
                username = providers.gradleProperty("ONEUI_GH_USERNAME")
                    .orElse(providers.environmentVariable("ONEUI_GH_USERNAME")).getOrElse("")
                password = providers.gradleProperty("ONEUI_GH_TOKEN")
                    .orElse(providers.environmentVariable("ONEUI_GH_TOKEN")).getOrElse("")
            }
        }
        maven {
            name = "OneUIDesign"
            url = uri("https://maven.pkg.github.com/tribalfs/oneui-design")
            credentials {
                username = providers.gradleProperty("ONEUI_GH_USERNAME")
                    .orElse(providers.environmentVariable("ONEUI_GH_USERNAME")).getOrElse("")
                password = providers.gradleProperty("ONEUI_GH_TOKEN")
                    .orElse(providers.environmentVariable("ONEUI_GH_TOKEN")).getOrElse("")
            }
        }
    }
}

rootProject.name = "ScreenLock"
include(":app")
