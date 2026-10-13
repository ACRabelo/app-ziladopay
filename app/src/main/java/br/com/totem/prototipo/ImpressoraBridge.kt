package br.com.totem.prototipo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Ponte entre a página do totem e uma impressora térmica Bluetooth (ESC/POS).
 *
 * A página monta o comprovante já em bytes ESC/POS (ver "Escpos" em index.html) e
 * manda em Base64. Aqui só se abre a conexão Bluetooth clássica (SPP) com a
 * impressora escolhida e se enviam os bytes. Assim o layout do comprovante muda
 * sem recompilar o app.
 *
 * JS -> nativo:  window.TotemImpressora.estado()            -> texto JSON { nome, mac }
 *                window.TotemImpressora.listar()            -> texto JSON com os dispositivos pareados
 *                window.TotemImpressora.definir(mac, nome)  mac vazio = não usar impressora
 *                window.TotemImpressora.imprimir(id, base64)
 *
 * nativo -> JS:  window.Totem.receber({ tipo: "impressao", id, ok, mensagem, enviados })
 *                enviados = quantos bytes chegaram à impressora (-1 = não dá para saber).
 *                No modo evento a página usa isso para reimprimir só as fichas que faltaram.
 */
class ImpressoraBridge(
    private val context: Context,
    private val webView: WebView,
) {
    // Uma impressão por vez, fora da fila de pagamento e da fila do servidor.
    private val fila = Executors.newSingleThreadExecutor()

    /** Resultado de um envio. [enviados] = bytes entregues à impressora; -1 = desconhecido. */
    private data class Envio(val ok: Boolean, val mensagem: String, val enviados: Int = 0)

    private fun prefs() = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun macAtual(): String? = prefs().getString(K_MAC, null)?.takeIf { it.isNotBlank() }

    @JavascriptInterface
    fun estado(): String {
        val mac = macAtual()
        val json = JSONObject()
        if (mac != null) {
            json.put("mac", mac)
            json.put("nome", prefs().getString(K_NOME, mac))
        }
        return json.toString()
    }

    /** Dispositivos pareados no Bluetooth do aparelho, para escolher a impressora no Diagnóstico. */
    @JavascriptInterface
    fun listar(): String {
        val atual = macAtual()
        val lista = JSONArray()
        LeitorConfig.pareados(context).forEach { p ->
            lista.put(
                JSONObject()
                    .put("nome", p.nome)
                    .put("mac", p.mac)
                    .put("leitor", LeitorConfig.pareceLeitorPagBank(p.nome))
                    .put("selecionado", p.mac == atual)
            )
        }
        return JSONObject()
            .put("permissao", LeitorConfig.temPermissaoBluetooth(context))
            .put("bluetoothLigado", LeitorConfig.bluetoothLigado(context))
            .put("dispositivos", lista)
            .toString()
    }

    /** Guarda no aparelho qual impressora usar. [mac] vazio = não imprimir. */
    @JavascriptInterface
    fun definir(mac: String, nome: String) {
        if (mac.isBlank()) {
            prefs().edit().remove(K_MAC).remove(K_NOME).apply()
            Log.i(TAG, "Impressora removida")
        } else {
            prefs().edit().putString(K_MAC, mac.trim()).putString(K_NOME, nome.trim()).apply()
            Log.i(TAG, "Impressora definida: $nome ($mac)")
        }
    }

    @JavascriptInterface
    fun imprimir(id: String, dadosBase64: String) {
        fila.execute {
            val resultado = try {
                val mac = macAtual()
                if (mac == null) {
                    Envio(false, "Nenhuma impressora escolhida")
                } else {
                    enviar(mac, Base64.decode(dadosBase64, Base64.DEFAULT))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao imprimir", e)
                Envio(false, e.message ?: "Erro inesperado ao imprimir", -1)
            }
            responder(id, resultado)
        }
    }

    /**
     * Conecta, envia e fecha. Bloqueante.
     * Tenta primeiro a conexão normal e depois a "insegura" (sem troca de chave), que é
     * a que muitas impressoras baratas aceitam. A segunda tentativa só acontece se a
     * primeira não chegou a conectar: depois de conectado nada é reenviado, para não
     * imprimir em dobro (no modo evento cada ficha vale um produto).
     */
    @SuppressLint("MissingPermission")
    private fun enviar(mac: String, dados: ByteArray): Envio {
        if (!LeitorConfig.temPermissaoBluetooth(context)) return Envio(false, "App sem a permissão \"Dispositivos por perto\"")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return Envio(false, "Aparelho sem Bluetooth")
        if (!adapter.isEnabled) return Envio(false, "Bluetooth desligado")

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            return Envio(false, "Endereço de impressora inválido")
        }

        var ultimoErro = "sem resposta"
        for (segura in listOf(true, false)) {
            runCatching { adapter.cancelDiscovery() }

            val socket = try {
                if (segura) {
                    device.createRfcommSocketToServiceRecord(SPP)
                } else {
                    device.createInsecureRfcommSocketToServiceRecord(SPP)
                }
            } catch (e: IOException) {
                ultimoErro = e.message ?: "falha de conexão"
                continue
            } catch (e: SecurityException) {
                return Envio(false, "Permissão de Bluetooth negada pelo Android")
            }

            try {
                socket.connect()
            } catch (e: IOException) {
                runCatching { socket.close() }
                ultimoErro = e.message ?: "falha de conexão"
                Log.w(TAG, "Conexão ${if (segura) "normal" else "insegura"} com $mac falhou: $ultimoErro")
                continue
            } catch (e: SecurityException) {
                runCatching { socket.close() }
                return Envio(false, "Permissão de Bluetooth negada pelo Android")
            }

            // Conectou. Daqui em diante qualquer erro encerra: nada é mandado duas vezes.
            var enviado = 0
            try {
                val saida = socket.outputStream
                // Em blocos pequenos: impressora barata tem pouca memória e perde dados se receber tudo de uma vez.
                while (enviado < dados.size) {
                    val tamanho = minOf(BLOCO, dados.size - enviado)
                    saida.write(dados, enviado, tamanho)
                    saida.flush()
                    enviado += tamanho
                    Thread.sleep(30)
                }
                // Fechar na hora corta o fim do papel: espera a impressora esvaziar o que recebeu.
                Thread.sleep(800)
                return Envio(true, "Impresso", enviado)
            } catch (e: IOException) {
                Log.w(TAG, "Conexão com $mac caiu depois de $enviado de ${dados.size} bytes: ${e.message}")
                return Envio(false, "A conexão com a impressora caiu no meio da impressão", enviado)
            } finally {
                runCatching { socket.close() }
            }
        }
        return Envio(false, "Não conectou na impressora (ligada e por perto?): $ultimoErro")
    }

    private fun responder(id: String, envio: Envio) {
        val payload = JSONObject()
            .put("tipo", "impressao")
            .put("id", id)
            .put("ok", envio.ok)
            .put("mensagem", envio.mensagem)
            .put("enviados", envio.enviados)
        Log.d(TAG, "-> JS $payload")
        val js = "window.Totem && window.Totem.receber($payload);"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun encerrar() {
        fila.shutdownNow()
    }

    companion object {
        private const val TAG = "ImpressoraBridge"
        private const val PREFS = "totem_impressora"
        private const val K_MAC = "mac"
        private const val K_NOME = "nome"
        private const val BLOCO = 256

        /** Perfil de porta serial (SPP), usado pelas impressoras térmicas Bluetooth. */
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
