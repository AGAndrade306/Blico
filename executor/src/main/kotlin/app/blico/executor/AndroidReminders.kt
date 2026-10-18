package app.blico.executor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.blico.core.DeviceResult
import app.blico.core.capability.Permission
import app.blico.core.capability.Reminder
import app.blico.core.capability.ReminderPort
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Lembretes pelo AlarmManager, avisados por notificação. A lista fica nas preferências do app, só para
 * mostrar, cancelar e remarcar depois de reiniciar o celular; o texto não sai do aparelho.
 */
class AndroidReminders(private val context: Context) : ReminderPort {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun canNotify(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override suspend fun schedule(text: String, at: LocalDateTime): DeviceResult {
        if (!canNotify()) return DeviceResult.NeedsPermission(Permission.NOTIFICATIONS)
        val r = Reminder(UUID.randomUUID().toString(), text, at)
        synchronized(LOCK) { save(context, load(context) + r) }
        arm(context, r)
        return DeviceResult.Done
    }

    override suspend fun pending(): List<Reminder> = load(context)

    override suspend fun cancel(id: String): Boolean {
        val removed = synchronized(LOCK) {
            val all = load(context)
            val next = all.filterNot { it.id == id }
            save(context, next)
            next.size != all.size
        }
        if (removed) context.getSystemService(AlarmManager::class.java).cancel(pending(context, id, ""))
        return removed
    }

    companion object {
        private const val PREFS = "lembretes"
        private const val CHANNEL = "lembretes"
        internal const val EXTRA_ID = "id"
        internal const val EXTRA_TEXT = "texto"
        private val LOCK = Any()

        internal fun load(context: Context): List<Reminder> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", null) ?: return emptyList()
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Reminder(o.getString("id"), o.getString("texto"), LocalDateTime.parse(o.getString("em")))
                }
            }.getOrElse { emptyList() }
        }

        internal fun save(context: Context, list: List<Reminder>) {
            val a = JSONArray(list.map { JSONObject().put("id", it.id).put("texto", it.text).put("em", it.at.toString()) })
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", a.toString()).apply()
        }

        private fun pending(context: Context, id: String, text: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Na hora exata quando o sistema deixa (USE_EXACT_ALARM); senão, perto dela. */
        @SuppressLint("MissingPermission") // canScheduleExactAlarms() é conferido antes.
        internal fun arm(context: Context, r: Reminder) {
            val am = context.getSystemService(AlarmManager::class.java)
            val millis = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = pending(context, r.id, r.text)
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }

        /** Remarca os que ainda não tocaram e tira os que já passaram. Depois de reiniciar o celular. */
        internal fun rearm(context: Context) {
            val now = Instant.now()
            val (future, past) = load(context).partition { it.at.atZone(ZoneId.systemDefault()).toInstant().isAfter(now) }
            if (past.isNotEmpty()) synchronized(LOCK) { save(context, future) }
            future.forEach { arm(context, it) }
        }

        @SuppressLint("MissingPermission") // POST_NOTIFICATIONS é conferido antes.
        internal fun notify(context: Context, id: String, text: String) {
            synchronized(LOCK) { save(context, load(context).filterNot { it.id == id }) }
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lembretes", NotificationManager.IMPORTANCE_HIGH))
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val n = android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Lembrete")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setCategory(android.app.Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .apply { open?.let(::setContentIntent) }
                .build()
            nm.notify(id.hashCode(), n)
        }
    }
}

/** Toca na hora do lembrete. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AndroidReminders.EXTRA_ID) ?: return
        val text = intent.getStringExtra(AndroidReminders.EXTRA_TEXT).orEmpty().ifEmpty { "Lembrete do Blico" }
        AndroidReminders.notify(context, id, text)
    }
}

/** O AlarmManager esquece tudo quando o celular reinicia: remarca. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AndroidReminders.rearm(context)
    }
}
