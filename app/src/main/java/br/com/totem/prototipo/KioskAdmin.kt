package br.com.totem.prototipo

import android.app.admin.DeviceAdminReceiver

/**
 * Administrador do aparelho. Quando o app vira Device Owner (uma vez, por adb), o Android
 * deixa ele travar a tela no totem sem perguntar nada e sem o cliente conseguir sair.
 *
 *   adb shell dpm set-device-owner br.com.totem.prototipo/.KioskAdmin
 *
 * Veja "Modo quiosque" no README.
 */
class KioskAdmin : DeviceAdminReceiver()
