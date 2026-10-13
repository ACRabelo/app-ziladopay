package br.com.totem.prototipo

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Ponte entre a página do totem e o servidor (Laravel).
 *
 * A página não fala com o servidor direto: pede para o app, e o app coloca o token.
 * Assim o token nunca chega ao JavaScript.
 *
 * JS -> nativo:  window.TotemApi.estado()                           -> texto JSON (síncrono)
 *                window.TotemApi.parear(id, codigo, servidor)
 *                window.TotemApi.chamar(id, metodo, caminho, corpo) caminho relativo a /api/v1/totem
 *                window.TotemApi.desparear()
 *                window.TotemApi.desbloquear(id, pin)                 PIN da área de diagnóstico
 *
 * nativo -> JS:  window.Totem.receber({ tipo: "api", id, status, corpo, erro })
 *                status = código HTTP, ou 0 quando não houve resposta (sem rede, servidor fora).
 *                corpo  = texto da resposta (JSON), para a página fazer JSON.parse.
 */
class ApiBridge(
    private val context: Context,
    private val webView: WebView,
) {
    // Fora da fila de pagamento do TotemBridge: consultar o servidor não pode
    // esperar o leitor, nem o contrário.
    private val rede = Executors.newFixedThreadPool(3)

    private data class Resposta(val status: Int, val corpo: String, val erro: String? = null)

    @JavascriptInterface
    fun estado(): String = JSONObject()
        .put("pareado", TotemServidor.token(context) != null)
        .put("servidor", TotemServidor.endereco(context))
        .put("versao", BuildConfig.VERSION_NAME)
        .toString()

    /** Troca o código mostrado no painel pelo token do totem e guarda no aparelho. */
    @JavascriptInterface
    fun parear(id: String, codigo: String, servidor: String) {
        rede.execute {
            val base = servidor.trim().trimEnd('/')
            val r = requisitar("POST", "$base$PREFIXO/pair", JSONObject().put("code", codigo).toString(), null)
            if (r.status != 201) {
                responder(id, r)
                return@execute
            }
            try {
                val json = JSONObject(r.corpo)
                val dados = json.getJSONObject("data")
                TotemServidor.salvar(context, base, dados.getString("token"))
                dados.remove("token") // a página recebe quem é o totem, mas não o token
                responder(id, Resposta(r.status, json.toString()))
            } catch (e: Exception) {
                Log.e(TAG, "Resposta de pareamento inesperada", e)
                responder(id, Resposta(0, "", "Resposta inesperada do servidor"))
            }
        }
    }

    @JavascriptInterface
    fun chamar(id: String, metodo: String, caminho: String, corpo: String) {
        rede.execute {
            val token = TotemServidor.token(context)
            val r = when {
                token == null -> Resposta(401, "", "Aparelho não pareado")
                metodo !in METODOS || !caminho.startsWith("/") -> Resposta(0, "", "Chamada inválida")
                else -> requisitar(metodo, TotemServidor.endereco(context) + PREFIXO + caminho, corpo, token)
            }
            responder(id, r)
        }
    }

    /**
     * Confere o PIN da área de diagnóstico. Responde como uma chamada da API:
     * 200 libera; 422 PIN errado; 409 loja sem PIN; 429 tentativas demais; 0 sem servidor
     * e sem PIN guardado. Sem pareamento libera (ainda não há loja nem PIN).
     *
     * Com o servidor fora (ou o totem desativado, 403), vale o hash do último PIN aceito,
     * com o mesmo limite de 5 erros por minuto.
     */
    @JavascriptInterface
    fun desbloquear(id: String, pin: String) {
        rede.execute {
            if (TotemServidor.token(context) == null) {
                responder(id, ok("sem_pareamento"))
                return@execute
            }
            val r = requisitar(
                "POST",
                TotemServidor.endereco(context) + PREFIXO + "/admin/unlock",
                JSONObject().put("pin", pin).toString(),
                TotemServidor.token(context),
            )
            when {
                r.status == 200 -> { TotemServidor.salvarPin(context, pin); limparErros(); responder(id, r) }
                // PIN errado ou loja sem PIN: o guardado pode ser um PIN antigo, que não vale mais.
                r.status == 422 || r.status == 409 -> { TotemServidor.apagarPin(context); responder(id, r) }
                r.status == 0 || r.status == 403 || r.status >= 500 -> responder(id, conferirOffline(pin, r))
                else -> responder(id, r)
            }
        }
    }

    // Erros de PIN sem internet, para o mesmo limite do servidor (5 por minuto).
    private val errosOffline = ArrayDeque<Long>()

    @Synchronized
    private fun conferirOffline(pin: String, falhaServidor: Resposta): Resposta {
        val agora = System.currentTimeMillis()
        while (errosOffline.isNotEmpty() && agora - errosOffline.first() > 60_000) errosOffline.removeFirst()
        if (errosOffline.size >= 5) {
            return Resposta(429, JSONObject().put("message", "Muitas tentativas. Aguarde um minuto.").toString())
        }
        return when (TotemServidor.conferirPin(context, pin)) {
            null -> falhaServidor // nunca entrou com PIN neste aparelho: depende do servidor
            true -> { errosOffline.clear(); ok("offline") }
            false -> {
                errosOffline.addLast(agora)
                Resposta(422, JSONObject().put("message", "PIN incorreto.").put("code", "pin_invalid").toString())
            }
        }
    }

    @Synchronized
    private fun limparErros() = errosOffline.clear()

    private fun ok(modo: String) = Resposta(200, JSONObject().put("data", JSONObject().put("ok", true).put("modo", modo)).toString())

    @JavascriptInterface
    fun desparear() {
        TotemServidor.apagarToken(context)
        Log.i(TAG, "Aparelho despareado")
    }

    private fun requisitar(metodo: String, url: String, corpo: String?, token: String?): Resposta {
        var conexao: HttpURLConnection? = null
        return try {
            conexao = URL(url).openConnection() as HttpURLConnection
            conexao.requestMethod = metodo
            conexao.connectTimeout = 8_000
            conexao.readTimeout = 25_000 // gerar PIX pode levar alguns segundos no provedor
            conexao.setRequestProperty("Accept", "application/json")
            conexao.setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
            if (token != null) conexao.setRequestProperty("Authorization", "Bearer $token")
            if (metodo != "GET") {
                conexao.doOutput = true
                conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val dados = (if (corpo.isNullOrBlank()) "{}" else corpo).toByteArray(Charsets.UTF_8)
                conexao.outputStream.use { it.write(dados) }
            }
            val status = conexao.responseCode
            val fluxo = if (status in 200..299) conexao.inputStream else conexao.errorStream
            val texto = fluxo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Resposta(status, texto)
        } catch (e: Exception) {
            Log.w(TAG, "$metodo $url falhou: ${e.message}")
            Resposta(0, "", e.message ?: e.javaClass.simpleName)
        } finally {
            conexao?.disconnect()
        }
    }

    private fun responder(id: String, r: Resposta) {
        val payload = JSONObject()
            .put("tipo", "api")
            .put("id", id)
            .put("status", r.status)
            .put("corpo", r.corpo)
        if (r.erro != null) payload.put("erro", r.erro)
        Log.d(TAG, "-> JS api $id ${r.status}")
        val js = "window.Totem && window.Totem.receber($payload);"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun encerrar() {
        rede.shutdownNow()
    }

    companion object {
        private const val TAG = "ApiBridge"
        private const val PREFIXO = "/api/v1/totem"
        private val METODOS = setOf("GET", "POST")
    }
}
