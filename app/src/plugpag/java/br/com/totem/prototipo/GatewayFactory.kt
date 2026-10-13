package br.com.totem.prototipo

import android.app.Activity
import android.content.Context
import br.com.uol.pagseguro.plugpag.LogLevel
import br.com.uol.pagseguro.plugpag.PlugPag
import br.com.uol.pagseguro.plugpag.PlugPagAuthenticationListener
import br.com.uol.pagseguro.plugpag.PlugPagDevice
import br.com.uol.pagseguro.plugpag.PlugPagEventData
import br.com.uol.pagseguro.plugpag.PlugPagEventListener
import br.com.uol.pagseguro.plugpag.PlugPagPaymentData

object GatewayFactory {
    // Uma única instância do PlugPag por app (exigência do manual)
    @Volatile private var instancia: PlugPagGateway? = null

    fun criar(context: Context): PaymentGateway =
        instancia ?: synchronized(this) {
            instancia ?: PlugPagGateway(context.applicationContext).also { instancia = it }
        }
}

/**
 * Implementação real usando o PlugPag 4.15.1 (Bluetooth) com a Minizinha.
 *
 * Toda a dependência da biblioteca fica NESTE arquivo.
 * Assinaturas conferidas no plugpag-4.15.1.aar e no demo oficial (pagseguro/plugpag/4.x/demo).
 * Diferenças em relação ao manual 3.x:
 *  - PlugPag(context): o construtor não recebe mais PlugPagAppIdentification
 *  - PlugPagDevice(nome, null, null, less): less = true para PIX/QR Code (sem leitor)
 *  - PlugPagEventData.customMessage já traz o texto pronto para exibir
 */
class PlugPagGateway(private val context: Context) : PaymentGateway {

    private val plugPag = PlugPag(context).apply {
        // Sem isso a lib devolve -1017 (nome/versão da aplicação não definidos)
        setVersionName(NOME_APP, VERSAO_APP)
        // Em build de debug, liga o log detalhado da biblioteca (aparece no Logcat).
        // Ajuda a ver o motivo real de falhas de login e de conexão.
        if (BuildConfig.DEBUG) runCatching { setLogLevel(LogLevel.LOG_ALL.value) }
    }

    override val modo = "plugpag"

    override fun versaoBiblioteca(): String = runCatching { PlugPag.getLibVersion() }.getOrNull() ?: "?"

    override fun estaAutenticado(): Boolean = plugPag.isAuthenticated

    override fun autenticar(activity: Activity, aoTerminar: (Boolean) -> Unit) {
        // Abre a tela de login do PagBank (Activity da própria biblioteca). Feito uma vez pelo operador.
        plugPag.requestAuthentication(object : PlugPagAuthenticationListener {
            override fun onSuccess() = aoTerminar(true)
            override fun onError() = aoTerminar(false)
        })
    }

    override fun conectar(semLeitor: Boolean): ResultadoConexao {
        // Nome escolhido na tela de Diagnóstico (ou o padrão de gradle.properties)
        val leitor = LeitorConfig.atual(context)
        val device = PlugPagDevice(leitor, null, null, semLeitor)
        val ret = plugPag.initBTConnection(device)
        return if (ret == PlugPag.RET_OK) {
            ResultadoConexao(true, ret, if (semLeitor) "Pronto para PIX" else "Leitor $leitor conectado")
        } else {
            ResultadoConexao(false, ret, "${mensagemErro(ret)} [leitor: $leitor]")
        }
    }

    override fun pagar(pedido: PedidoPagamento, aoEvento: (EventoPagamento) -> Unit): ResultadoPagamento {
        // Eventos só são gerados com leitores (Minizinha), não com Moderninhas
        plugPag.setEventListener(PlugPagEventListener { data ->
            val codigo = data.eventCode
            val texto = data.customMessage?.takeIf { it.isNotBlank() } ?: mensagemEvento(codigo)
            aoEvento(EventoPagamento(codigo, texto))
            PlugPag.RET_OK
        })

        val tipo = when (pedido.tipo) {
            TipoPagamento.CREDITO -> PlugPag.TYPE_CREDITO
            TipoPagamento.DEBITO -> PlugPag.TYPE_DEBITO
            TipoPagamento.PIX -> PlugPag.TYPE_PIX
        }
        val parcelamento = if (pedido.parcelas > 1) PlugPag.INSTALLMENT_TYPE_PARC_VENDEDOR
                           else PlugPag.INSTALLMENT_TYPE_A_VISTA

        val dados = PlugPagPaymentData(
            tipo,
            pedido.valorCentavos,
            parcelamento,
            pedido.parcelas,
            pedido.referencia.take(9), // a lib exige menos de 10 caracteres
        )

        val r = plugPag.doPayment(dados)
        val ok = r.result == PlugPag.RET_OK
        return ResultadoPagamento(
            aprovado = ok,
            codigo = r.result,
            mensagem = r.message ?: if (ok) "Transação aprovada" else mensagemErro(r.result),
            codigoErro = r.errorCode,
            transactionCode = r.transactionCode,
            transactionId = r.transactionId,
            hostNsu = r.hostNsu,
            bandeira = r.cardBrand,
            finalCartao = r.holder,
            data = r.date,
            hora = r.time,
        )
    }

    override fun cancelar(): Int = plugPag.abort().result

    /** Usado só quando a biblioteca não manda customMessage. */
    private fun mensagemEvento(codigo: Int): String = when (codigo) {
        PlugPagEventData.EVENT_CODE_WAITING_CARD -> "Insira ou aproxime o cartão"
        PlugPagEventData.EVENT_CODE_INSERTED_CARD -> "Cartão inserido"
        PlugPagEventData.EVENT_CODE_PIN_REQUESTED -> "Digite a senha na maquininha"
        PlugPagEventData.EVENT_CODE_PIN_OK -> "Senha validada"
        PlugPagEventData.EVENT_CODE_INSERTED_KEY -> "Senha digitada"
        PlugPagEventData.EVENT_CODE_AUTHORIZING -> "Autorizando..."
        PlugPagEventData.EVENT_CODE_WAITING_REMOVE_CARD -> "Retire o cartão"
        PlugPagEventData.EVENT_CODE_REMOVED_CARD -> "Cartão removido"
        PlugPagEventData.EVENT_CODE_SALE_END -> "Pagamento finalizado"
        else -> "Processando..."
    }

    private fun mensagemErro(codigo: Int): String = when (codigo) {
        -1003 -> "Leitor ocupado ou não está pronto. Tente novamente"
        -1004 -> "Transação não realizada"
        -1010 -> "Modelo de leitor não reconhecido. Confira o nome Bluetooth"
        -1019 -> "Erro de comunicação com o leitor"
        -1025 -> "Não foi possível conectar ao leitor. Confira se ele está ligado, pareado e se o nome está correto"
        -1028 -> "Operação cancelada"
        -1047 -> "Já existe uma transação em andamento"
        -1017 -> "Nome/versão da aplicação não definidos"
        -1030, -4046 -> "Conta PagBank não autenticada neste aparelho"
        -1031 -> "Valor inválido (mínimo R$ 1,00)"
        -2004 -> "Tempo esgotado na comunicação Bluetooth"
        -2027, -2033 -> "Falha no Bluetooth. Reinicie o app ou o leitor"
        -3000 -> "Permissões não concedidas"
        -3001 -> "Aparelho com root não é permitido"
        else -> "Erro $codigo"
    }

    companion object {
        const val NOME_APP = "TotemPrototipo" // máx. 25 caracteres
        const val VERSAO_APP = "0.1.0"        // máx. 10 caracteres
    }
}
