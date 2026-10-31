import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String, default: String) =
    (project.findProperty(name) as String?) ?: default

/**
 * PIN de suporte (PIN geral): entra no Diagnóstico de qualquer totem, mesmo sem internet
 * e sem o PIN da loja guardado. Fica FORA do repositório, em ~/.gradle/gradle.properties:
 *   totem.pinSuporte=12345678
 * O APK leva só um hash SHA-256 (com sal); sem a propriedade, não há PIN de suporte.
 */
val SAL_PIN_SUPORTE = "zilado-pay-suporte-v1"
fun hashPinSuporte(): String {
    val pin = (project.findProperty("totem.pinSuporte") as String?)?.trim().orEmpty()
    if (pin.isEmpty()) return ""
    require(pin.matches(Regex("\\d{6,8}"))) { "totem.pinSuporte deve ter de 6 a 8 dígitos" }
    return MessageDigest.getInstance("SHA-256")
        .digest("$SAL_PIN_SUPORTE:$pin".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

android {
    namespace = "br.com.totem.prototipo"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.totem.prototipo"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.0"

        buildConfigField("String", "LEITOR", "\"${prop("totem.leitor", "PAX-00000000")}\"")
        buildConfigField("String", "TOTEM_URL", "\"${prop("totem.url", "file:///android_asset/totem/index.html")}\"")
        buildConfigField("boolean", "QUIOSQUE", prop("totem.quiosque", "false"))
        buildConfigField("String", "API_URL", "\"${prop("totem.api", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "PIN_SUPORTE_HASH", "\"${hashPinSuporte()}\"")
        buildConfigField("String", "PIN_SUPORTE_SAL", "\"$SAL_PIN_SUPORTE\"")
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
