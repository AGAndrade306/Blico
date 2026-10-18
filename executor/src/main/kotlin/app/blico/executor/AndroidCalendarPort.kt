package app.blico.executor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import app.blico.core.DeviceResult
import app.blico.core.capability.CalendarEvent
import app.blico.core.capability.CalendarPort
import app.blico.core.capability.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * A agenda do aparelho pelo CalendarContract. Lê todas as agendas visíveis (as contas sincronizadas,
 * como a do Google, aparecem aqui sem login no Blico) e cria na principal em que dá para escrever.
 * Cada leitura e escrita confere a permissão antes ([canRead], [canWrite]); o lint não segue essa checagem.
 */
@SuppressLint("MissingPermission")
class AndroidCalendarPort(private val context: Context) : CalendarPort {
    override fun canRead() = granted(Manifest.permission.READ_CALENDAR)

    override fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR) && canRead()

    override suspend fun events(from: LocalDateTime, to: LocalDateTime): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from.atZone(zone).toInstant().toEpochMilli())
            android.content.ContentUris.appendId(it, to.atZone(zone).toInstant().toEpochMilli())
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                // Dia inteiro vem em UTC: meia-noite de lá é o dia certo aqui.
                val tz = if (allDay) ZoneOffset.UTC else zone
                out += CalendarEvent(
                    title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "sem título",
                    start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), tz),
                    end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), tz),
                    allDay = allDay,
                    location = c.getString(4)?.takeIf { it.isNotBlank() },
                )
            }
        }
        out
    }

    override suspend fun insert(title: String, start: LocalDateTime, end: LocalDateTime, location: String?): DeviceResult =
        withContext(Dispatchers.IO) {
            if (!canWrite()) return@withContext DeviceResult.NeedsPermission(Permission.CALENDAR)
            val calendarId = writableCalendar() ?: return@withContext DeviceResult.Failed("nenhuma agenda em que dá para escrever")
            val zone = ZoneId.systemDefault()
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            if (context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null) {
                DeviceResult.Done
            } else {
                DeviceResult.Failed("a agenda não aceitou o evento")
            }
        }

    /** A principal visível onde dá para escrever; senão, a primeira assim. */
    private fun writableCalendar(): Long? {
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val where = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var first: Long? = null
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, where, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) return c.getLong(0)
                if (first == null) first = c.getLong(0)
            }
        }
        return first
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
