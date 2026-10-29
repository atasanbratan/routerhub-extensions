pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Hack on router-api and an extension at the same time: check the app repository out next to this
// one and build with  ./gradlew -Prouterhub.sdk=../routerhub <tasks>
// The checkout then replaces the published router-api, with no release and no re-publish in between.
providers.gradleProperty("routerhub.sdk").orNull?.let { sdk ->
    includeBuild(sdk) {
        dependencySubstitution {
            substitute(module("com.github.atasanbratan:routerhub")).using(project(":router-api"))
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // router-api is the contract between the RouterHub app and its extensions. The app repository
        // publishes it through JitPack, the same way Mihon distributes its extensions-lib.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.atasanbratan") }
        }
    }
}

rootProject.name = "routerhub-extensions"

include(":extensions:tplink-adsl")
include(":extensions:tenda-ac")
include(":extensions:openwrt-ssh")
include(":extensions:demo")
