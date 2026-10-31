package br.com.totem.prototipo

import android.app.AlertDialog
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Saída de emergência 100% nativa, para quando a página (WebView) não abre o Diagnóstico:
 * erro de JavaScript, WebView travado, atualização do WebView com problema.
 *
 * Sequência nos botões de volume: 3x volume para cima e 3x volume para baixo, sem pausa
 * maior que 2 s entre os toques. Abre um teclado de PIN nativo com um único botão,
 * "Sair do totem". Aceita o PIN de suporte (PIN geral) ou o último PIN da loja guardado
 * no aparelho. Não fala com o servidor. Limite de 5 erros por minuto.
 */
class Emergencia(private val activity: AppCompatActivity) {

    private val sequencia = listOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN,
    )
    private var posicao = 0
    private var ultimoToque = 0L
    private val erros = ArrayDeque<Long>()
    private var dialogo: AlertDialog? = null

    /** Chamado pelo dispatchKeyEvent da Activity. Não consome a tecla (o volume continua mudando). */
    fun observar(evento: KeyEvent) {
        if (evento.action != KeyEvent.ACTION_DOWN || evento.repeatCount != 0) return
        val codigo = evento.keyCode
        if (codigo != KeyEvent.KEYCODE_VOLUME_UP && codigo != KeyEvent.KEYCODE_VOLUME_DOWN) return
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoToque > 2_000) posicao = 0
        ultimoToque = agora
        posicao = when {
            codigo == sequencia[posicao] -> posicao + 1
            codigo == sequencia[0] -> 1 // errou a ordem: recomeça, aproveitando este toque
            else -> 0
        }
        if (posicao == sequencia.size) {
            posicao = 0
            abrir()
        }
    }

    private fun abrir() {
        if (dialogo?.isShowing == true || activity.isFinishing) return
        Log.i(TAG, "Saída de emergência aberta")
        val dp = activity.resources.displayMetrics.density
        val aviso = TextView(activity).apply {
            text = "Digite o PIN de suporte ou o PIN do ponto de venda."
        }
        val campo = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            gravity = Gravity.CENTER
            textSize = 28f
        }
        val erro = TextView(activity).apply { setTextColor(0xFFD32F2F.toInt()) }
        val caixa = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(aviso); addView(campo); addView(erro)
        }

        val d = AlertDialog.Builder(activity)
            .setTitle("Saída de emergência")
            .setView(caixa)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sair do totem", null) // tratado abaixo, para não fechar com PIN errado
            .setCancelable(true)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (conferir(campo.text.toString())) {
                    Resultado.OK -> {
                        Log.i(TAG, "Saída de emergência: operador saiu do totem")
                        d.dismiss()
                        Quiosque.sair(activity)
                    }
                    Resultado.ERRADO -> { erro.text = "PIN incorreto."; campo.text.clear() }
                    Resultado.BLOQUEADO -> { erro.text = "Muitas tentativas. Aguarde um minuto."; campo.text.clear() }
                }
            }
            campo.requestFocus()
        }
        // Esquecido aberto: fecha sozinho em 30 s.
        val fechar = Runnable { if (d.isShowing) d.dismiss() }
        campo.postDelayed(fechar, 30_000)
        d.setOnDismissListener { campo.removeCallbacks(fechar); dialogo = null }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialogo = d
        d.show()
    }

    private enum class Resultado { OK, ERRADO, BLOQUEADO }

    private fun conferir(pin: String): Resultado {
        val agora = SystemClock.elapsedRealtime()
        while (erros.isNotEmpty() && agora - erros.first() > 60_000) erros.removeFirst()
        if (erros.size >= 5) return Resultado.BLOQUEADO
        if (pin.length >= 4 && (TotemServidor.ehPinSuporte(pin) || TotemServidor.conferirPin(activity, pin) == true)) {
            erros.clear()
            return Resultado.OK
        }
        erros.addLast(agora)
        return Resultado.ERRADO
    }

    fun encerrar() {
        dialogo?.dismiss()
    }

    companion object {
        private const val TAG = "Emergencia"
    }
}
