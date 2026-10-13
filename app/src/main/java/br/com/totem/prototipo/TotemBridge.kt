package br.com.totem.prototipo

import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ponte entre a página do totem (HTML/JS, local ou servida pelo Laravel) e o pagamento nativo.
 *
 * JS -> nativo:  window.TotemNativo.pagar('{"valor":100,"tipo":"CREDITO","parcelas":1,"referencia":"PED123"}')
 *                window.TotemNativo.cancelar()
 *                window.TotemNativo.conectar()
 *                window.TotemNativo.autenticar()
 *                window.TotemNativo.info()            -> string JSON (síncrono)
 *                window.TotemNativo.listarLeitores()  -> string JSON (síncrono)
 *                window.TotemNativo.definirLeitor('PAX-12345678')
 *
 * nativo -> JS:  window.Totem.receber({ tipo: "evento" | "resultado" | "conexao" | "autenticacao" | "erro", ... })
 */
class TotemBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val gateway: PaymentGateway,
) {
    // Operações bloqueantes em fila única: o PlugPag não aceita chamadas em paralelo
    private val fila = Executors.newSingleThreadExecutor()
    private val ocupado = AtomicBoolean(false)

    @JavascriptInterface
    fun info(): String = JSONObject()
        .put("modo", gateway.modo)
        .put("biblioteca", gateway.versaoBiblioteca())
        .put("leitor", LeitorConfig.atual(activity))
        .put("autenticado", runCatching { gateway.estaAutenticado() }.getOrDefault(false))
        .put("ocupado", ocupado.get())
        .toString()

    /** Leitores pareados no Bluetooth do aparelho, para escolher na tela de Diagnóstico. */
    @JavascriptInterface
    fun listarLeitores(): String {
        val atual = LeitorConfig.atual(activity)
        val lista = JSONArray()
        LeitorConfig.pareados(activity).forEach { p ->
            lista.put(
                JSONObject()
                    .put("nome", p.nome)
                    .put("mac", p.mac)
                    .put("pagbank", LeitorConfig.pareceLeitorPagBank(p.nome))
                    .put("selecionado", p.nome == atual)
            )
        }
        return JSONObject()
            .put("permissao", LeitorConfig.temPermissaoBluetooth(activity))
            .put("bluetoothLigado", LeitorConfig.bluetoothLigado(activity))
            .put("atual", atual)
            .put("leitores", lista)
            .toString()
    }

    /** Salva no aparelho o nome Bluetooth do leitor a usar. Não precisa recompilar. */
    @JavascriptInterface
    fun definirLeitor(nome: String) {
        if (nome.isBlank()) return
        LeitorConfig.salvar(activity, nome)
        Log.i(TAG, "Leitor definido: $nome")
    }

    @JavascriptInterface
    fun conectar() {
        fila.execute {
            val r = runCatching { gateway.conectar() }
                .getOrElse { ResultadoConexao(false, -1, it.message ?: "Falha ao conectar") }
            enviar(JSONObject().put("tipo", "conexao").put("ok", r.ok).put("codigo", r.codigo).put("mensagem", r.mensagem))
        }
    }

    /**
     * Login PagBank. A biblioteca fala com o leitor por Bluetooth durante o login (lê o número
     * de série), então ela precisa saber ANTES qual é o leitor. Sem isso ela tenta adivinhar
     * um dispositivo pareado e o login falha com "verifique sua conexão com a internet".
     */
    @JavascriptInterface
    fun autenticar() {
        fila.execute {
            val c = runCatching { gateway.conectar(semLeitor = false) }
                .getOrElse { ResultadoConexao(false, -1, it.message ?: "Falha ao configurar o leitor") }
            enviar(JSONObject().put("tipo", "conexao").put("ok", c.ok).put("codigo", c.codigo).put("mensagem", c.mensagem))
            if (!c.ok) {
                enviar(JSONObject().put("tipo", "autenticacao").put("ok", false).put("mensagem", c.mensagem))
                return@execute
            }
            activity.runOnUiThread {
                gateway.autenticar(activity) { ok ->
                    enviar(JSONObject().put("tipo", "autenticacao").put("ok", ok))
                }
            }
        }
    }

    /**
     * Teste direto do Bluetooth com o leitor escolhido, sem passar pela biblioteca PlugPag.
     * Mostra o erro real do Android quando a conexão falha.
     */
    @JavascriptInterface
    fun testarBluetooth() {
        fila.execute {
            val r = LeitorConfig.testarConexao(activity)
            enviar(JSONObject().put("tipo", "bluetooth").put("ok", r.first).put("mensagem", r.second))
        }
    }

    @JavascriptInterface
    fun pagar(json: String) {
        if (!ocupado.compareAndSet(false, true)) {
            enviar(erro("Já existe um pagamento em andamento"))
            return
        }

        val pedido = try {
            val o = JSONObject(json)
            PedidoPagamento(
                valorCentavos = o.getInt("valor"),
                tipo = TipoPagamento.valueOf(o.getString("tipo").uppercase()),
                parcelas = o.optInt("parcelas", 1),
                referencia = o.optString("referencia", "TOTEM"),
            )
        } catch (e: Exception) {
            ocupado.set(false)
            enviar(erro("Pedido inválido: ${e.message}"))
            return
        }

        fila.execute {
            try {
                // Conecta antes de cada cobrança: o Bluetooth do leitor pode ter caído
                val conexao = gateway.conectar(semLeitor = pedido.tipo == TipoPagamento.PIX)
                if (!conexao.ok) {
                    enviar(resultado(ResultadoPagamento(false, conexao.codigo, conexao.mensagem, "CONEXAO")))
                    return@execute
                }
                val r = gateway.pagar(pedido) { ev ->
                    enviar(JSONObject().put("tipo", "evento").put("codigo", ev.codigo).put("mensagem", ev.mensagem))
                }
                enviar(resultado(r))
            } catch (e: Exception) {
                Log.e(TAG, "Falha no pagamento", e)
                enviar(resultado(ResultadoPagamento(false, -1, e.message ?: "Erro inesperado", "EXCECAO")))
            } finally {
                ocupado.set(false)
            }
        }
    }

    /** Roda fora da fila: precisa funcionar enquanto doPayment está bloqueado. */
    @JavascriptInterface
    fun cancelar() {
        Thread {
            val ret = runCatching { gateway.cancelar() }.getOrDefault(-1)
            Log.i(TAG, "Cancelamento solicitado, retorno $ret")
        }.start()
    }

    private fun resultado(r: ResultadoPagamento) = JSONObject()
        .put("tipo", "resultado")
        .put("aprovado", r.aprovado)
        .put("codigo", r.codigo)
        .put("mensagem", r.mensagem)
        .put("codigoErro", r.codigoErro)
        .put("transactionCode", r.transactionCode)
        .put("transactionId", r.transactionId)
        .put("hostNsu", r.hostNsu)
        .put("bandeira", r.bandeira)
        .put("finalCartao", r.finalCartao)
        .put("data", r.data)
        .put("hora", r.hora)

    private fun erro(msg: String) = JSONObject().put("tipo", "erro").put("mensagem", msg)

    private fun enviar(payload: JSONObject) {
        Log.d(TAG, "-> JS $payload")
        val js = "window.Totem && window.Totem.receber($payload);"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun encerrar() = fila.shutdownNow()

    companion object { private const val TAG = "TotemBridge" }
}
