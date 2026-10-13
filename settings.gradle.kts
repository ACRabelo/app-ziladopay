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
        // Repositório Maven oficial do PlugPag 4.x (mesmo usado no demo do PagBank)
        maven { url = uri("https://github.com/pagseguro/plugpag/raw/master/4.x/android") }
        // Exigido por dependências do PlugPag 4.x
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "totem-prototipo"
include(":app")
