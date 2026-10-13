package br.com.totem.prototipo

import android.app.Activity
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object GatewayFactory {
    fun criar(context: Context): PaymentGateway = SimulatedGateway()
}

/**
 * Simula a Minizinha: mesma sequência de eventos e mesmo formato de resultado.
 * Valor terminado em 13 centavos (ex.: R$ 1,13) simula uma transação negada,
 * para você testar o caminho de erro na tela.
 */
class SimulatedGateway : PaymentGateway {

    private val cancelado = AtomicBoolean(false)
    private var autenticado = true

    override val modo = "simulado"

    override fun versaoBiblioteca() = "simulado"

    override fun estaAutenticado() = autenticado

    override fun autenticar(activity: Activity, aoTerminar: (Boolean) -> Unit) {
        autenticado = true
        aoTerminar(true)
    }

    override fun conectar(semLeitor: Boolean): ResultadoConexao {
        Thread.sleep(500)
        return ResultadoConexao(true, 0, "Leitor simulado conectado")
    }

    override fun pagar(pedido: PedidoPagamento, aoEvento: (EventoPagamento) -> Unit): ResultadoPagamento {
        cancelado.set(false)

        val passos = if (pedido.tipo == TipoPagamento.PIX) {
            listOf(0 to "Aproxime o celular ou leia o QR Code", 5 to "Autorizando...", 4 to "Pagamento finalizado")
        } else {
            listOf(
                0 to "Insira ou aproxime o cartão",
                1 to "Cartão inserido",
                2 to "Digite a senha",
                6 to "Senha digitada",
                5 to "Autorizando...",
                7 to "Retire o cartão",
                8 to "Cartão removido",
                4 to "Pagamento finalizado",
            )
        }

        for ((codigo, msg) in passos) {
            if (cancelado.get()) {
                return ResultadoPagamento(false, -1, "Operação cancelada", codigoErro = "CANCELADO")
            }
            aoEvento(EventoPagamento(codigo, msg))
            Thread.sleep(1200)
        }

        if (pedido.valorCentavos % 100 == 13) {
            return ResultadoPagamento(false, -1004, "Transação negada (simulação)", codigoErro = "NEGADA")
        }

        val agora = java.util.Date()
        return ResultadoPagamento(
            aprovado = true,
            codigo = 0,
            mensagem = "Transação aprovada",
            transactionCode = "SIM" + System.currentTimeMillis(),
            transactionId = "SIM-" + (100000..999999).random(),
            hostNsu = (100000..999999).random().toString(),
            bandeira = if (pedido.tipo == TipoPagamento.PIX) "PIX" else "VISA",
            finalCartao = if (pedido.tipo == TipoPagamento.PIX) null else "4242",
            data = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(agora),
            hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("pt", "BR")).format(agora),
        )
    }

    override fun cancelar(): Int {
        cancelado.set(true)
        return 0
    }
}
