plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String, default: String) =
    (project.findProperty(name) as String?) ?: default

android {
    namespace = "br.com.totem.prototipo"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.totem.prototipo"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0"

        buildConfigField("String", "LEITOR", "\"${prop("totem.leitor", "PAX-00000000")}\"")
        buildConfigField("String", "TOTEM_URL", "\"${prop("totem.url", "file:///android_asset/totem/index.html")}\"")
        buildConfigField("boolean", "QUIOSQUE", prop("totem.quiosque", "false"))
        buildConfigField("String", "API_URL", "\"${prop("totem.api", "http://10.0.2.2:8000")}\"")
    }

    // Dois sabores:
    //  - simulado: não precisa da Minizinha nem da biblioteca. Serve para testar a tela e a ponte JS.
    //  - plugpag:  usa a biblioteca real (AAR em app/libs) e cobra na Minizinha.
    flavorDimensions += "pagamento"
    productFlavors {
        create("simulado") {
            dimension = "pagamento"
            applicationIdSuffix = ".simulado"
            buildConfigField("String", "MODO", "\"simulado\"")
        }
        create("plugpag") {
            dimension = "pagamento"
            buildConfigField("String", "MODO", "\"plugpag\"")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Biblioteca PlugPag (só no sabor "plugpag"). Baixada do repositório Maven do PagBank,
    // já com as dependências que ela precisa.
    "plugpagImplementation"("br.com.uol.pagseguro:plugpag:4.15.1")
}
