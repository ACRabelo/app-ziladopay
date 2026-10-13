package br.com.totem.prototipo

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Modo quiosque: o cliente não sai do app do totem.
 *
 * - Com o app como **Device Owner** (configurado uma vez por adb): trava de verdade.
 *   Sem barra de status, sem botão de início nem de apps recentes, sem confirmação, e o
 *   totem vira a tela inicial do aparelho (abre sozinho ao ligar).
 * - Sem Device Owner e com `totem.quiosque=true`: "fixar app" do Android. Ele pede
 *   confirmação, e quem conhece o gesto (segurar voltar + recentes) consegue sair.
 * - Sem nenhum dos dois: só tela cheia.
 *
 * O operador sai pelo Diagnóstico (que pede o PIN da loja).
 */
object Quiosque {
    private const val TAG = "Quiosque"

    /** Alias da MainActivity com categoria HOME, desligado no manifest; só liga em modo quiosque. */
    private const val ALIAS_HOME = "br.com.totem.prototipo.TelaInicial"

    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(c: Context) = ComponentName(c, KioskAdmin::class.java)
    private fun home(c: Context) = ComponentName(c.packageName, ALIAS_HOME)

    fun ehDono(c: Context): Boolean = dpm(c).isDeviceOwnerApp(c.packageName)

    /** NONE, LOCKED (Device Owner) ou PINNED (fixar app). */
    fun estadoTrava(c: Context): Int =
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState

    /** Chamado no onResume: trava a tela no app se der. */
    fun entrar(a: Activity) {
        if (estadoTrava(a) != ActivityManager.LOCK_TASK_MODE_NONE) return
        try {
            if (ehDono(a)) {
                configurarDono(a)
                a.startLockTask()
            } else if (BuildConfig.QUIOSQUE) {
                a.startLockTask() // o Android pergunta se pode fixar
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível travar a tela", e)
        }
    }

    private fun configurarDono(a: Activity) {
        val d = dpm(a)
        val adm = admin(a)
        d.setLockTaskPackages(adm, arrayOf(a.packageName))
        // Nada do sistema durante a trava: sem início, recentes, notificações, barra de status.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            d.setLockTaskFeatures(adm, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        // O totem vira a tela inicial: depois de reiniciar o aparelho, ele abre sozinho.
        a.packageManager.setComponentEnabledSetting(
            home(a), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        val filtro = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        d.addPersistentPreferredActivity(adm, filtro, home(a))
        runCatching { d.setStatusBarDisabled(adm, true) }
        // Sem tela de bloqueio (não funciona se o aparelho tiver senha de desbloqueio).
        runCatching { d.setKeyguardDisabled(adm, true) }
        // Tela sempre ligada enquanto estiver na tomada (AC, USB e sem fio).
        runCatching { d.setGlobalSetting(adm, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7") }
    }

    /** Desfaz o que configurarDono fez, mas o app continua Device Owner. */
    private fun desfazerDono(a: Activity) {
        val d = dpm(a)
        val adm = admin(a)
        runCatching { d.clearPackagePersistentPreferredActivities(adm, a.packageName) }
        runCatching { d.setStatusBarDisabled(adm, false) }
        runCatching { d.setKeyguardDisabled(adm, false) }
        runCatching { d.setGlobalSetting(adm, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0") }
        a.packageManager.setComponentEnabledSetting(
            home(a), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Operador sai do totem (manutenção). O aparelho volta a ser um Android comum até
     * o app ser aberto de novo, quando a trava volta sozinha.
     */
    fun sair(a: Activity) {
        if (ehDono(a)) desfazerDono(a)
        runCatching { a.stopLockTask() }
        a.finishAndRemoveTask()
    }

    /**
     * Tira o app de Device Owner: o aparelho deixa de ser um totem (para devolver ou
     * usar em outra coisa). Para voltar, é preciso o comando adb de novo.
     */
    @Suppress("DEPRECATION") // clearDeviceOwnerApp é o único jeito de o próprio app se remover
    fun liberarAparelho(a: Activity) {
        if (ehDono(a)) {
            desfazerDono(a)
            runCatching { dpm(a).setLockTaskPackages(admin(a), emptyArray()) }
            runCatching { a.stopLockTask() }
            dpm(a).clearDeviceOwnerApp(a.packageName)
        } else {
            runCatching { a.stopLockTask() }
        }
        a.finishAndRemoveTask()
    }
}

/**
 * Ponte da página com o modo quiosque. Só a tela de Diagnóstico usa (depois do PIN).
 *
 * JS -> nativo:  window.TotemQuiosque.estado()   -> texto JSON {dono, trava, fixarAtivo} (síncrono)
 *                window.TotemQuiosque.sair()
 *                window.TotemQuiosque.liberar()
 */
class QuiosqueBridge(private val activity: Activity) {
    @JavascriptInterface
    fun estado(): String = JSONObject()
        .put("dono", Quiosque.ehDono(activity))
        .put(
            "trava",
            when (Quiosque.estadoTrava(activity)) {
                ActivityManager.LOCK_TASK_MODE_LOCKED -> "travado"
                ActivityManager.LOCK_TASK_MODE_PINNED -> "fixado"
                else -> "desligado"
            },
        )
        .put("fixarAtivo", BuildConfig.QUIOSQUE)
        .toString()

    @JavascriptInterface
    fun sair() {
        Log.i("Quiosque", "Operador saiu do totem")
        activity.runOnUiThread { Quiosque.sair(activity) }
    }

    @JavascriptInterface
    fun liberar() {
        Log.i("Quiosque", "Aparelho liberado do modo quiosque")
        activity.runOnUiThread { Quiosque.liberarAparelho(activity) }
    }
}
