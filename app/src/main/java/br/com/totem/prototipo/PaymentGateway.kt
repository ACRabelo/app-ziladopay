package br.com.totem.prototipo

import android.app.Activity

/**
 * Contrato de pagamento do totem.
 *
 * O resto do app (WebView, ponte JS) só conhece esta interface.
 * Existem duas implementações, uma por sabor de build:
 *  - src/simulado: SimulatedGateway (sem maquininha)
 *  - src/plugpag:  PlugPagGateway (Minizinha real via PlugPag)
 *
 * Todos os métodos bloqueantes devem ser chamados FORA da thread principal.
 */
interface PaymentGateway {

    /** Nome do modo, exibido na tela de diagnóstico. */
    val modo: String

    /** Versão da biblioteca de pagamento (ou "simulado"). */
    fun versaoBiblioteca(): String

    /** Há uma conta PagBank autenticada neste aparelho? */
    fun estaAutenticado(): Boolean

    /**
     * Abre o login do PagBank (feito uma vez, pelo operador, na instalação).
     * O resultado volta no callback, na thread em que a biblioteca chamar.
     */
    fun autenticar(activity: Activity, aoTerminar: (sucesso: Boolean) -> Unit)

    /**
     * Configura a conexão com o leitor. Bloqueante.
     * [semLeitor] = true para PIX/QR Code: no PlugPag 4.x essas transações são "pinpadless".
     */
    fun conectar(semLeitor: Boolean = false): ResultadoConexao

    /**
     * Cobra o valor. Bloqueante: só retorna quando a transação termina.
     * Os passos intermediários (insira o cartão, digite a senha...) chegam em [aoEvento].
     */
    fun pagar(pedido: PedidoPagamento, aoEvento: (EventoPagamento) -> Unit): ResultadoPagamento

    /** Pede o cancelamento da cobrança em andamento. Pode ser chamado de outra thread. */
    fun cancelar(): Int
}

enum class TipoPagamento { CREDITO, DEBITO, PIX }

data class PedidoPagamento(
    /** Valor em centavos. R$ 1,00 = 100 (mínimo aceito pelo PagBank). */
    val valorCentavos: Int,
    val tipo: TipoPagamento,
    val parcelas: Int = 1,
    /** Referência do pedido. O PlugPag exige menos de 10 caracteres. */
    val referencia: String,
)

data class ResultadoConexao(val ok: Boolean, val codigo: Int, val mensagem: String)

data class EventoPagamento(val codigo: Int, val mensagem: String)

data class ResultadoPagamento(
    val aprovado: Boolean,
    val codigo: Int,
    val mensagem: String,
    val codigoErro: String? = null,
    // Guardar estes dados no backend: são necessários para estorno e conciliação
    val transactionCode: String? = null,
    val transactionId: String? = null,
    val hostNsu: String? = null,
    val bandeira: String? = null,
    val finalCartao: String? = null,
    val data: String? = null,
    val hora: String? = null,
)
