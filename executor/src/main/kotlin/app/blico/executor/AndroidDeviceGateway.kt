package app.blico.executor

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telecom.TelecomManager
import app.blico.core.DeviceAction
import app.blico.core.DeviceGateway
import app.blico.core.DeviceResult
import app.blico.core.tools.Apps
import app.blico.core.tools.Player
import app.blico.executor.BlicoAccessibilityService.SendStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Backend 1 da arquitetura: intents e deep links. Abre telas e, depois do Confirmar,
 * completa ligações e toca em enviar no WhatsApp (pela [BlicoAccessibilityService]).
 */
class AndroidDeviceGateway(
    private val context: Context,
    /** Quem inicia as activities. Na sessão do assistente é o startActivity dela. */
    private val launcher: (Intent) -> Unit = { context.startActivity(it) },
) : DeviceGateway {

    override suspend fun perform(action: DeviceAction): DeviceResult = try {
        when (action) {
            is DeviceAction.OpenUri -> openUri(action)
            is DeviceAction.PlayMedia -> playMedia(action)
            is DeviceAction.LaunchPackage -> launchPackage(action.packageName)
            is DeviceAction.LaunchByName -> launchByName(action.name)
            is DeviceAction.Dial -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + dialable(action.number))))
            is DeviceAction.Call -> call(action.number)
            is DeviceAction.SendWhatsApp -> sendWhatsApp(action)
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
            // DuckDuckGo por privacidade: o app dele se estiver instalado, senão o navegador padrão.
            is DeviceAction.WebSearch -> start(
                Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).apply { if (isInstalled(Apps.DUCKDUCKGO)) setPackage(Apps.DUCKDUCKGO) },
            )
            is DeviceAction.ShareText -> shareText(action)
            is DeviceAction.OpenFile -> start(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(MediaStoreFileIndex.uriFor(action.id, action.mime), action.mime.ifEmpty { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            is DeviceAction.ShareFile -> {
                val uri = MediaStoreFileIndex.uriFor(action.id, action.mime)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(action.mime.ifEmpty { "*/*" })
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                start(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            is DeviceAction.RenameFile -> rename(action)
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

    /**
     * Pede ao player que toque, pelo "tocar a partir da busca" do Android (o mesmo que os assistentes usam).
     * Com app dito, só nele; sem app, no player padrão do sistema. Se o app não aceitar, abre a busca dele.
     */
    private fun playMedia(action: DeviceAction.PlayMedia): DeviceResult {
        val pkg = action.packageName
        if (pkg != null && !isInstalled(pkg)) return DeviceResult.NotInstalled(appLabel(action))
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, action.query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .apply { pkg?.let(::setPackage) }
        if (play.resolveActivity(context.packageManager) != null) return start(play)
        val fallback = action.fallbackUri ?: return DeviceResult.NotInstalled(appLabel(action))
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).apply { pkg?.let(::setPackage) })
    }

    /** Liga pelo Telecom, sem passar pelo discador. Só com a permissão de fazer chamadas. */
    private fun call(number: String): DeviceResult {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return DeviceResult.Failed("sem permissão de fazer chamadas")
        }
        context.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", dialable(number), null), Bundle())
        return DeviceResult.Done
    }

    /**
     * Abre a conversa com o texto na caixa e espera a acessibilidade tocar em enviar.
     * Sem o serviço, ou se o texto na tela não for o confirmado, a conversa fica aberta e pronta.
     */
    private suspend fun sendWhatsApp(action: DeviceAction.SendWhatsApp): DeviceResult = sending.withLock {
        if (!isInstalled(WHATSAPP)) return DeviceResult.NotInstalled("WhatsApp")
        val text = URLEncoder.encode(action.text, Charsets.UTF_8).replace("+", "%20")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${action.phone}&text=$text")).setPackage(WHATSAPP))
        val service = BlicoAccessibilityService.current ?: return DeviceResult.Failed("acessibilidade do Blico desligada")
        withContext(Dispatchers.Default) {
            var why = "o WhatsApp não abriu"
            val tapped = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                var step = service.tapSend(action.text)
                while (step is SendStep.NotYet) {
                    why = step.why
                    delay(POLL_MS)
                    step = service.tapSend(action.text)
                }
                true
            } ?: false
            if (!tapped) return@withContext DeviceResult.Failed("não enviei: $why")
            // A caixa esvazia quando a mensagem sai.
            val left = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                while (!service.sent(action.text)) delay(POLL_MS)
                true
            } ?: false
            if (left) DeviceResult.Done else DeviceResult.Failed("toquei em enviar, mas a mensagem continuou na caixa")
        }
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

    /**
     * Renomeia pelo MediaStore, que mantém o índice em dia. Com o acesso a todos os arquivos
     * o update é permitido; se o sistema recusar, cai para renomear o arquivo e reindexar.
     */
    private fun rename(action: DeviceAction.RenameFile): DeviceResult {
        val uri = android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL),
            action.id.toLong(),
        )
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, action.newName)
        }
        val updated = try {
            context.contentResolver.update(uri, values, null, null)
        } catch (_: SecurityException) {
            0
        } catch (_: IllegalStateException) {
            0 // já existe um arquivo com esse nome
        }
        if (updated > 0) return DeviceResult.Done
        return renameOnDisk(uri, action.newName)
    }

    @Suppress("DEPRECATION") // DATA ainda é o caminho real, e só é usado com o acesso a todos os arquivos.
    private fun renameOnDisk(uri: Uri, newName: String): DeviceResult {
        val path = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return DeviceResult.Failed("arquivo sem caminho")
        val from = java.io.File(path)
        val to = java.io.File(from.parentFile, newName)
        if (to.exists()) return DeviceResult.Failed("já existe $newName")
        if (!from.renameTo(to)) return DeviceResult.Failed("o sistema não deixou renomear")
        android.media.MediaScannerConnection.scanFile(context, arrayOf(from.path, to.path), null, null)
        return DeviceResult.Done
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
        is DeviceAction.PlayMedia -> action.packageName?.let { KNOWN_LABELS[it] ?: it } ?: "app de música"
        is DeviceAction.ShareText -> action.packageName?.let { KNOWN_LABELS[it] ?: it } ?: "app"
        is DeviceAction.LaunchPackage -> KNOWN_LABELS[action.packageName] ?: action.packageName
        is DeviceAction.LaunchByName -> action.name
        is DeviceAction.Dial, is DeviceAction.Call -> "discador"
        is DeviceAction.SendWhatsApp -> "WhatsApp"
        is DeviceAction.SetAlarm, is DeviceAction.SetTimer -> "app de relógio"
        is DeviceAction.WebSearch -> "navegador"
        is DeviceAction.OpenFile -> "app para abrir esse arquivo"
        is DeviceAction.ShareFile, is DeviceAction.RenameFile -> "arquivo"
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val SEND_TIMEOUT_MS = 8_000L
        const val CONFIRM_TIMEOUT_MS = 3_000L
        const val POLL_MS = 250L

        /** Um envio por vez, para dois pedidos seguidos não tocarem na mesma tela. */
        val sending = Mutex()

        fun dialable(number: String) = number.filter { it.isDigit() || it == '+' }

        val KNOWN_LABELS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.ubercab" to "Uber",
            "com.taxis99" to "99",
            "br.com.brainweb.ifood" to "iFood",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.maps" to "Google Maps",
            "com.waze" to "Waze",
        ) + Player.entries.associate { it.packageName to it.label }

        /** Minúsculas e sem acento, para "Câmera" bater com "camera". */
        fun simplify(s: String): String =
            Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
