package app.blico.executor

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import app.blico.core.DeviceAction
import app.blico.core.DeviceGateway
import app.blico.core.DeviceResult
import java.text.Normalizer

/** Backend 1 da arquitetura: intents e deep links. Abre telas, não toca em nada dentro dos apps. */
class AndroidDeviceGateway(
    private val context: Context,
    /** Quem inicia as activities. Na sessão do assistente é o startActivity dela. */
    private val launcher: (Intent) -> Unit = { context.startActivity(it) },
) : DeviceGateway {

    override suspend fun perform(action: DeviceAction): DeviceResult = try {
        when (action) {
            is DeviceAction.OpenUri -> openUri(action)
            is DeviceAction.LaunchPackage -> launchPackage(action.packageName)
            is DeviceAction.LaunchByName -> launchByName(action.name)
            is DeviceAction.Dial -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + action.number.filter { it.isDigit() || it == '+' })))
            is DeviceAction.SetAlarm -> start(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { action.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
            )
            is DeviceAction.SetTimer -> start(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { action.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
            )
            is DeviceAction.WebSearch -> start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, action.query))
            is DeviceAction.ShareText -> shareText(action)
        }
    } catch (e: ActivityNotFoundException) {
        DeviceResult.NotInstalled(appLabel(action))
    } catch (e: SecurityException) {
        DeviceResult.Failed(e.message ?: "sem permissão")
    }

    private fun openUri(action: DeviceAction.OpenUri): DeviceResult {
        val pkg = action.packageName
        if (pkg != null && !isInstalled(pkg)) return DeviceResult.NotInstalled(appLabel(action))
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).apply { pkg?.let(::setPackage) })
    }

    private fun launchPackage(pkg: String): DeviceResult {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return DeviceResult.NotInstalled(KNOWN_LABELS[pkg] ?: pkg)
        return start(intent)
    }

    /** Procura entre os apps da tela inicial o rótulo que mais se parece com [name]. */
    private fun launchByName(name: String): DeviceResult {
        val pm = context.packageManager
        val wanted = simplify(name)
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).map { it.activityInfo.packageName to simplify(it.loadLabel(pm).toString()) }

        val best = apps.firstOrNull { it.second == wanted }
            ?: apps.firstOrNull { it.second.startsWith(wanted) }
            ?: apps.firstOrNull { wanted.length >= 3 && it.second.contains(wanted) }
            ?: return DeviceResult.NotInstalled(name)
        return launchPackage(best.first)
    }

    private fun shareText(action: DeviceAction.ShareText): DeviceResult {
        val pkg = action.packageName
        if (pkg != null && !isInstalled(pkg)) return DeviceResult.NotInstalled(appLabel(action))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, action.text)
        return start(if (pkg != null) send.setPackage(pkg) else Intent.createChooser(send, null))
    }

    private fun start(intent: Intent): DeviceResult {
        launcher(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return DeviceResult.Done
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun appLabel(action: DeviceAction): String = when (action) {
        is DeviceAction.OpenUri -> action.packageName?.let { KNOWN_LABELS[it] ?: it } ?: "app"
        is DeviceAction.ShareText -> action.packageName?.let { KNOWN_LABELS[it] ?: it } ?: "app"
        is DeviceAction.LaunchPackage -> KNOWN_LABELS[action.packageName] ?: action.packageName
        is DeviceAction.LaunchByName -> action.name
        is DeviceAction.Dial -> "discador"
        is DeviceAction.SetAlarm, is DeviceAction.SetTimer -> "app de relógio"
        is DeviceAction.WebSearch -> "navegador"
    }

    private companion object {
        val KNOWN_LABELS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.ubercab" to "Uber",
            "com.taxis99" to "99",
            "br.com.brainweb.ifood" to "iFood",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.maps" to "Google Maps",
        )

        /** Minúsculas e sem acento, para "Câmera" bater com "camera". */
        fun simplify(s: String): String =
            Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
