package app.blico.executor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import app.blico.core.DeviceResult
import app.blico.core.capability.Permission
import app.blico.core.capability.SmsPort

/** SMS pelo chip padrão. Só roda depois do Confirmar (classe 2) e com a permissão SEND_SMS. */
class AndroidSms(private val context: Context) : SmsPort {
    override fun canSend(): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // canSend() é conferido logo abaixo.
    override suspend fun send(phone: String, text: String): DeviceResult {
        if (!canSend()) return DeviceResult.NeedsPermission(Permission.SMS)
        val number = phone.filter { it.isDigit() || it == '+' }
        return try {
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            DeviceResult.Done
        } catch (e: SecurityException) {
            DeviceResult.NeedsPermission(Permission.SMS)
        } catch (e: IllegalArgumentException) {
            DeviceResult.Failed("número inválido: ${e.message}")
        }
    }
}
