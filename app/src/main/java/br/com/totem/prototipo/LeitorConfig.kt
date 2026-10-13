package br.com.totem.prototipo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.UUID
import androidx.core.content.ContextCompat

/**
 * Qual leitor (Minizinha) o totem usa.
 *
 * O nome escolhido na tela de Diagnóstico fica salvo no aparelho. Enquanto nada for
 * escolhido, vale o padrão de gradle.properties (totem.leitor).
 * O PlugPag identifica o leitor pelo NOME Bluetooth (ex.: PAX-12345678, Pag-12345678).
 */
object LeitorConfig {
    private const val PREFS = "totem"
    private const val CHAVE = "leitor"

    data class Pareado(val nome: String, val mac: String)

    fun atual(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHAVE, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.LEITOR

    fun salvar(context: Context, nome: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CHAVE, nome.trim()).apply()
    }

    /** No Android 12+ o app precisa da permissão "Dispositivos por perto" para usar o Bluetooth. */
    fun temPermissaoBluetooth(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun bluetoothLigado(context: Context): Boolean = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    /** Dispositivos já pareados nas configurações de Bluetooth do aparelho. */
    @SuppressLint("MissingPermission")
    fun pareados(context: Context): List<Pareado> {
        if (!temPermissaoBluetooth(context)) return emptyList()
        return try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.bondedDevices.orEmpty()
                .mapNotNull { d -> d.name?.takeIf { it.isNotBlank() }?.let { Pareado(it, d.address) } }
                .sortedBy { it.nome }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Abre e fecha uma conexão Bluetooth clássica (SPP) com o leitor escolhido.
     * É o mesmo tipo de conexão que a biblioteca PlugPag usa. Bloqueante.
     * Retorna (conectou?, descrição).
     */
    @SuppressLint("MissingPermission")
    fun testarConexao(context: Context): Pair<Boolean, String> {
        val nome = atual(context)
        if (!temPermissaoBluetooth(context)) return false to "App sem a permissão \"Dispositivos por perto\""
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false to "Aparelho sem Bluetooth"
        if (!adapter.isEnabled) return false to "Bluetooth desligado"

        val device = try {
            adapter.bondedDevices.orEmpty().firstOrNull { it.name == nome }
        } catch (e: SecurityException) {
            null
        }
        if (device == null) return false to "Leitor \"$nome\" não está pareado neste aparelho"

        val inicio = System.currentTimeMillis()
        return try {
            runCatching { adapter.cancelDiscovery() }
            val socket = device.createRfcommSocketToServiceRecord(SPP)
            try {
                socket.connect()
                true to "Conectou em $nome (${device.address}) em ${System.currentTimeMillis() - inicio} ms"
            } finally {
                runCatching { socket.close() }
            }
        } catch (e: IOException) {
            false to "Não conectou em $nome (${device.address}) após ${System.currentTimeMillis() - inicio} ms: ${e.message}"
        } catch (e: SecurityException) {
            false to "Permissão negada pelo Android: ${e.message}"
        }
    }

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Prefixos de nome usados pelos leitores e terminais PagBank. */
    fun pareceLeitorPagBank(nome: String): Boolean =
        listOf("PAX-", "PAG-", "MOBI", "PRO-", "W-", "PLUS-").any { nome.uppercase().startsWith(it) }
}
