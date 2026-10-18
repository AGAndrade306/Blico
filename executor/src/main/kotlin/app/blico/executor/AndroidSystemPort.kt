package app.blico.executor

import android.app.NotificationManager
import android.app.usage.StorageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import app.blico.core.DeviceResult
import app.blico.core.capability.Permission
import app.blico.core.capability.SettingsScreen
import app.blico.core.capability.SystemPort
import app.blico.core.capability.SystemPort.Level
import kotlin.math.roundToInt

/**
 * O sistema do celular pelo Android. Nada aqui precisa de permissão perigosa: Não perturbe e modo
 * silencioso pedem o acesso especial ao Não perturbe, e o brilho pede "modificar configurações do sistema".
 * Sem eles, a resposta diz qual liberar. [launcher] abre as telas, como no [AndroidDeviceGateway].
 */
class AndroidSystemPort(
    private val context: Context,
    private val launcher: (Intent) -> Unit = { context.startActivity(it) },
) : SystemPort {
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    override fun battery(): SystemPort.Battery? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return null
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        return SystemPort.Battery(percent, bm.isCharging, saver)
    }

    override fun storage(): SystemPort.Storage? = try {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        SystemPort.Storage(stats.getFreeBytes(StorageManager.UUID_DEFAULT), stats.getTotalBytes(StorageManager.UUID_DEFAULT))
    } catch (_: Exception) {
        null
    }

    override fun network(): SystemPort.Network {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
        val type = when {
            caps == null -> SystemPort.Network.Type.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> SystemPort.Network.Type.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> SystemPort.Network.Type.MOBILE
            else -> SystemPort.Network.Type.OTHER
        }
        return SystemPort.Network(
            type = type,
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            wifiOn = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true,
        )
    }

    override suspend fun torch(on: Boolean): DeviceResult {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return DeviceResult.Failed("sem câmera")
        return try {
            val id = cameras.cameraIdList.firstOrNull { id ->
                val c = cameras.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return DeviceResult.Failed("sem flash")
            cameras.setTorchMode(id, on)
            DeviceResult.Done
        } catch (e: CameraAccessException) {
            // Câmera em uso por outro app.
            DeviceResult.Failed("câmera ocupada: ${e.reason}")
        }
    }

    override suspend fun volume(stream: SystemPort.Stream, change: Level): DeviceResult {
        val s = stream.android
        val max = audio.getStreamMaxVolume(s)
        val min = audio.getStreamMinVolume(s)
        val step = (max / 10).coerceAtLeast(1)
        return try {
            when (change) {
                Level.Mute -> audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                Level.Auto -> return DeviceResult.Failed("volume não tem automático")
                else -> {
                    if (audio.isStreamMute(s)) audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                    val now = audio.getStreamVolume(s)
                    val target = when (change) {
                        Level.Up -> now + step
                        Level.Down -> now - step
                        is Level.To -> (change.percent * max / 100.0).roundToInt()
                        else -> now
                    }.coerceIn(min, max)
                    audio.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
                }
            }
            DeviceResult.Done
        } catch (_: SecurityException) {
            // Zerar o toque muda o Não perturbe, que pede o acesso especial.
            DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB)
        }
    }

    override fun volumePercent(stream: SystemPort.Stream): Int? {
        val max = audio.getStreamMaxVolume(stream.android).takeIf { it > 0 } ?: return null
        return audio.getStreamVolume(stream.android) * 100 / max
    }

    override suspend fun soundMode(mode: SystemPort.SoundMode): DeviceResult {
        val nm = notifications
        val access = nm.isNotificationPolicyAccessGranted
        return try {
            when (mode) {
                SystemPort.SoundMode.VIBRATE -> audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                SystemPort.SoundMode.SILENT -> {
                    if (!access) return DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB)
                    audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                }
                SystemPort.SoundMode.DO_NOT_DISTURB -> {
                    if (!access) return DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB)
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                }
                SystemPort.SoundMode.NORMAL -> {
                    if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        if (!access) return DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB)
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
            }
            DeviceResult.Done
        } catch (_: SecurityException) {
            DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB)
        }
    }

    override suspend fun brightness(change: Level): DeviceResult {
        if (!Settings.System.canWrite(context)) return DeviceResult.NeedsPermission(Permission.WRITE_SETTINGS)
        val cr = context.contentResolver
        if (change == Level.Auto) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return DeviceResult.Done
        }
        val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, MAX_BRIGHTNESS / 2)
        val target = when (change) {
            Level.Up -> now + MAX_BRIGHTNESS / 10
            Level.Down -> now - MAX_BRIGHTNESS / 10
            is Level.To -> change.percent * MAX_BRIGHTNESS / 100
            else -> now
        }.coerceIn(1, MAX_BRIGHTNESS)
        // Com o automático ligado, o sistema desfaria o valor na hora.
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        return DeviceResult.Done
    }

    override fun brightnessPercent(): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / MAX_BRIGHTNESS
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    override suspend fun open(screen: SettingsScreen): DeviceResult {
        for (action in actionsFor(screen)) {
            try {
                launcher(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return DeviceResult.Done
            } catch (_: ActivityNotFoundException) {
                // tenta a próxima
            }
        }
        return DeviceResult.NotInstalled("tela de ${screen.label}")
    }

    private val SystemPort.Stream.android: Int
        get() = when (this) {
            SystemPort.Stream.MEDIA -> AudioManager.STREAM_MUSIC
            SystemPort.Stream.RING -> AudioManager.STREAM_RING
            SystemPort.Stream.ALARM -> AudioManager.STREAM_ALARM
        }

    private companion object {
        const val MAX_BRIGHTNESS = 255

        /** Da tela mais exata à mais geral: a primeira que o aparelho tiver abre. */
        fun actionsFor(screen: SettingsScreen): List<String> = when (screen) {
            SettingsScreen.WIFI -> listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            SettingsScreen.INTERNET -> listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
            SettingsScreen.MOBILE_DATA -> listOf(Settings.ACTION_DATA_USAGE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            SettingsScreen.BLUETOOTH -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsScreen.NFC -> listOf(Settings.Panel.ACTION_NFC, Settings.ACTION_NFC_SETTINGS)
            SettingsScreen.AIRPLANE -> listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            SettingsScreen.HOTSPOT -> listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
            SettingsScreen.VOLUME -> listOf(Settings.Panel.ACTION_VOLUME, Settings.ACTION_SOUND_SETTINGS)
            SettingsScreen.SOUND -> listOf(Settings.ACTION_SOUND_SETTINGS)
            SettingsScreen.DO_NOT_DISTURB -> listOf("android.settings.ZEN_MODE_SETTINGS", Settings.ACTION_SOUND_SETTINGS)
            SettingsScreen.NOTIFICATIONS -> listOf("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", Settings.ACTION_SETTINGS)
            SettingsScreen.DISPLAY -> listOf(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsScreen.BATTERY -> listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)
            SettingsScreen.BATTERY_SAVER -> listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            SettingsScreen.STORAGE -> listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            SettingsScreen.LOCATION -> listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SettingsScreen.ACCESSIBILITY -> listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SettingsScreen.APPS -> listOf(Settings.ACTION_APPLICATION_SETTINGS)
            SettingsScreen.DATE_TIME -> listOf(Settings.ACTION_DATE_SETTINGS)
            SettingsScreen.LANGUAGE -> listOf(Settings.ACTION_LOCALE_SETTINGS)
            SettingsScreen.SECURITY -> listOf(Settings.ACTION_SECURITY_SETTINGS)
            SettingsScreen.PRIVACY -> listOf(Settings.ACTION_PRIVACY_SETTINGS)
            SettingsScreen.ACCOUNTS -> listOf(Settings.ACTION_SYNC_SETTINGS)
            SettingsScreen.SETTINGS -> emptyList()
        } + Settings.ACTION_SETTINGS
    }
}
