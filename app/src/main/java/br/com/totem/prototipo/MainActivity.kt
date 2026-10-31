package br.com.totem.prototipo

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: TotemBridge
    private lateinit var api: ApiBridge
    private lateinit var impressora: ImpressoraBridge
    private val emergencia = Emergencia(this)

    private val pedirPermissoes =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* a página consulta via info() */ }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        telaCheia()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect no PC

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            // Se o processo do WebView cair, recria a tela em vez de o app fechar.
            webViewClient = ClienteRestrito(BuildConfig.TOTEM_URL) { recreate() }
        }
        setContentView(webView)

        bridge = TotemBridge(this, webView, GatewayFactory.criar(this))
        webView.addJavascriptInterface(bridge, "TotemNativo")

        // Ponte com o servidor (Laravel): a página pede, o app chama e coloca o token.
        api = ApiBridge(this, webView)
        webView.addJavascriptInterface(api, "TotemApi")

        // Impressora de comprovante (Bluetooth): a página monta o papel, o app só envia.
        impressora = ImpressoraBridge(this, webView)
        webView.addJavascriptInterface(impressora, "TotemImpressora")

        // Modo quiosque: estado e saída do operador, pela tela de Diagnóstico.
        webView.addJavascriptInterface(QuiosqueBridge(this), "TotemQuiosque")
        webView.loadUrl(BuildConfig.TOTEM_URL)

        // Cliente não sai do totem pelo botão voltar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* ignorado */ }
        })

        pedirPermissoes.launch(permissoesNecessarias())
    }

    override fun onResume() {
        super.onResume()
        telaCheia()
        // Trava a tela no app: de verdade se for Device Owner; senão "fixar app",
        // se totem.quiosque=true. Veja Quiosque.kt.
        Quiosque.entrar(this)
    }

    // Saída de emergência nativa (sequência nos botões de volume). Veja Emergencia.kt.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        emergencia.observar(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        emergencia.encerrar()
        bridge.encerrar()
        api.encerrar()
        impressora.encerrar()
        webView.destroy()
        super.onDestroy()
    }

    private fun telaCheia() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun permissoesNecessarias(): Array<String> {
        val lista = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lista += Manifest.permission.BLUETOOTH_CONNECT
            lista += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lista += Manifest.permission.READ_MEDIA_AUDIO
        }
        return lista.toTypedArray()
    }

    /**
     * A ponte JS dá acesso ao pagamento, então o WebView só navega dentro da origem configurada.
     * Qualquer link externo é bloqueado.
     */
    private class ClienteRestrito(urlInicial: String, private val aoCair: () -> Unit) : WebViewClient() {
        private val origem = Uri.parse(urlInicial).let { "${it.scheme}://${it.authority ?: ""}" }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val alvo = request.url.let { "${it.scheme}://${it.authority ?: ""}" }
            return alvo != origem // true = bloqueia
        }

        @androidx.annotation.RequiresApi(26) // antes do Android 8 o WebView não avisa
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e("MainActivity", "Processo do WebView caiu (crash=${detail.didCrash()}); recriando a tela")
            view.post(aoCair)
            return true // o app trata; sem isso o Android fecha o app
        }
    }
}
