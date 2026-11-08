package online.myesstu.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Every half hour compares the group's timetable with the last one seen and says what changed. */
class ScheduleWatcher(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!AppSettings.wants(context, "schedule")) return Result.success()
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val guest = prefs.getBoolean("guest", false)
        val config = prefs.getString("config", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val url = if (guest) prefs.getString("guest_url", null) else config?.optString("group_url")
        val subgroup = if (guest) prefs.getInt("guest_subgroup", 0) else config?.optInt("subgroup", 0) ?: 0
        if (url.isNullOrEmpty()) return Result.success()

        AppCache.init(context)
        val page = try {
            University.fresh(url)
        } catch (e: ApiException) {
            return Result.retry()
        }
        val monday = LocalDate.now(UniversityZone).with(DayOfWeek.MONDAY)
        val snapshot = JSONObject()
        for (offset in 0..1L) {
            val week = University.week(page, subgroup, University.localBaseWeek(), monday.plusWeeks(offset), "", emptyMap())
            for (day in week.days) {
                snapshot.put(day.date, JSONArray(day.lessons.map { "${it.period}|${it.subject}|${it.kind}|${it.place}|${it.teacher}" }))
            }
        }
        val store = context.getSharedPreferences("schedule_watch", Context.MODE_PRIVATE)
        val key = "snap:$url#$subgroup"
        val old = store.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        store.edit().putString(key, snapshot.toString()).apply()
        // The first run only remembers the timetable, so nobody gets an alert for what was already there.
        if (old == null) return Result.success()

        val today = LocalDate.now(UniversityZone).toString()
        val lines = ArrayList<String>()
        for (date in snapshot.keys().asSequence().sorted()) {
            if (date < today || !old.has(date)) continue
            val before = old.getJSONArray(date).strings().toSet()
            val after = snapshot.getJSONArray(date).strings().toSet()
            if (before == after) continue
            val label = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EE, d MMM", Locale("ru"))).replaceFirstChar { it.uppercase() }
            lines += label + ": " + describe(before, after)
        }
        if (lines.isNotEmpty()) announce(context, lines)
        return Result.success()
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun describe(before: Set<String>, after: Set<String>): String {
        fun name(entry: String) = entry.split("|").let { "${it[0]} пара ${it[1]}" }
        val added = (after - before).map(::name)
        val removed = (before - after).map(::name)
        val parts = ArrayList<String>()
        if (added.isNotEmpty()) parts += "появилась " + added.joinToString(", ")
        if (removed.isNotEmpty()) parts += "убрана " + removed.joinToString(", ")
        return parts.joinToString("; ")
    }

    @SuppressLint("MissingPermission")
    private fun announce(context: Context, lines: List<String>) {
        if (!AppSettings.canShow(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("schedule", "Изменения в расписании", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Сообщает, когда в расписании группы что-то поменялось"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = lines.take(4).joinToString("\n") + if (lines.size > 4) "\nи ещё дней: ${lines.size - 4}" else ""
        val notification = NotificationCompat.Builder(context, "schedule")
            .setSmallIcon(R.drawable.ic_stat_grade)
            .setContentTitle("Изменилось расписание")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(3, notification)
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleWatcher>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("schedule-watch", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
