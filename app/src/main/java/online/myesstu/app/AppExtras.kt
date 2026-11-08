package online.myesstu.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the person chose for notifications in the app; the system permission is a separate thing. */
object AppSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun notificationsWanted(context: Context): Boolean = prefs(context).getBoolean("notify", true)

    /** The three kinds of alert can be switched one by one; the main switch turns all of them off. */
    fun wants(context: Context, kind: String): Boolean = notificationsWanted(context) && prefs(context).getBoolean("notify_$kind", true)

    fun setWants(context: Context, kind: String, value: Boolean) = prefs(context).edit().putBoolean("notify_$kind", value).apply()

    fun canShow(context: Context): Boolean = Notifications.permitted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun setNotificationsWanted(context: Context, value: Boolean) = prefs(context).edit().putBoolean("notify", value).apply()

    /** Notifications are really on only when the person wants them and Android lets the app show them. */
    fun notificationsOn(context: Context): Boolean =
        notificationsWanted(context) && Notifications.permitted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun installId(context: Context): String {
        val p = prefs(context)
        return p.getString("install_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("install_id", it).apply() }
    }

    fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun versionName(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

/** Tells the server that this account uses the app and whether it shows alerts itself, so Telegram does not repeat them. */
object Device {
    suspend fun report(context: Context) {
        val body = JSONObject()
            .put("install_id", AppSettings.installId(context))
            .put("version", AppSettings.versionName(context))
            .put("model", Build.MODEL.orEmpty())
            .put("notifications", AppSettings.notificationsOn(context))
        runCatching { Api.post("app/device", body) }
    }
}

/** A small card in the middle of the screen over a dimmed background. */
@Composable
internal fun CenterDialog(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(horizontal = 28.dp).fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                .background(p.surface).padding(22.dp),
            content = content,
        )
    }
}

/** A quiet, always-visible notification while a lesson is on: the system counts down the time to its end. */
object PairAlert {
    private const val CHANNEL = "pair"
    private const val ID = 77

    private fun prefs(context: Context) = context.getSharedPreferences("pair_alert", Context.MODE_PRIVATE)

    /** Keeps the lessons of the viewed week, so the alert works when the app is closed. */
    fun remember(context: Context, schedule: Schedule) {
        val saved = runCatching { JSONArray(prefs(context).getString("lessons", "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis() / 1000
        val kept = LinkedHashMap<Long, JSONObject>()
        for (i in 0 until saved.length()) saved.getJSONObject(i).let { if (it.getLong("end") > now - 86400) kept[it.getLong("start")] = it }
        for (day in schedule.days) for (lesson in day.lessons) {
            if (lesson.startTs <= 0) continue
            kept[lesson.startTs] = JSONObject().put("start", lesson.startTs).put("end", lesson.endTs).put("subject", lesson.subject).put("place", lesson.place)
        }
        prefs(context).edit().putString("lessons", JSONArray(kept.values.toList()).toString()).apply()
        refresh(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove("lessons").apply()
        pause(context)
    }

    /** Hides the countdown and stops the wake-ups but keeps the saved lessons, so it can be switched back on. */
    fun pause(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
        WorkManager.getInstance(context).cancelUniqueWork("pair-alert")
        val pending = PendingIntent.getBroadcast(context, 5, Intent(context, PairReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(android.app.AlarmManager::class.java).cancel(pending)
    }

    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val lessons = runCatching { JSONArray(prefs(context).getString("lessons", "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis() / 1000
        val sorted = (0 until lessons.length()).map { lessons.getJSONObject(it) }.sortedBy { it.getLong("start") }
        val live = sorted.firstOrNull { now in it.getLong("start") until it.getLong("end") }
        val upcoming = sorted.firstOrNull { it.getLong("start") > now }
        var next = Long.MAX_VALUE
        for (l in sorted) {
            if (l.getLong("start") > now) next = minOf(next, l.getLong("start"))
            if (l.getLong("end") > now) next = minOf(next, l.getLong("end"))
        }
        // Between two lessons (or before the first one) the same notification counts down to the next start.
        val soon = if (live == null && upcoming != null && upcoming.getLong("start") - now <= SOON_SECONDS) upcoming else null
        val shown = live ?: soon
        if (shown != null && AppSettings.wants(context, "pair") && AppSettings.canShow(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL, "Идёт пара", NotificationManager.IMPORTANCE_LOW)
                channel.description = "Время до конца пары и до начала следующей"
                channel.setShowBadge(false)
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val start = shown.getLong("start")
            val end = shown.getLong("end")
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val place = shown.optString("place")
            val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            fun at(ts: Long) = java.time.Instant.ofEpochSecond(ts).atZone(UniversityZone).format(clock)
            val range = "${at(start)}–${at(end)}" + if (place.isNotEmpty()) " · $place" else ""
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_grade)
                .setContentTitle(shown.optString("subject"))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setUsesChronometer(true).setChronometerCountDown(true)
                .setContentIntent(open)
            if (live != null) {
                val after = sorted.firstOrNull { it.getLong("start") >= end }
                val text = "Идёт пара · до ${at(end)}"
                builder.setSubText("Идёт пара").setContentText(range)
                    .setWhen(end * 1000).setTimeoutAfter((end - now) * 1000)
                    .setProgress((end - start).toInt(), (now - start).toInt(), false)
                    .setStyle(
                        NotificationCompat.BigTextStyle().setBigContentTitle(shown.optString("subject")).bigText(
                            "$text\n$range" + if (after != null) "\nДальше: ${after.optString("subject")}, ${at(after.getLong("start"))}" else "",
                        ),
                    )
            } else {
                builder.setSubText("Скоро пара").setContentText("Начало в ${at(start)}" + if (place.isNotEmpty()) " · $place" else "")
                    .setWhen(start * 1000).setTimeoutAfter((start - now) * 1000)
            }
            manager.notify(ID, builder.build())
            // The bar moves once a minute.
            if (live != null) scheduleTick(context, minOf(60L, end - now))
        } else {
            manager.cancel(ID)
        }
        if (next != Long.MAX_VALUE) {
            // Wake once more shortly before a lesson starts, so the countdown for it can appear.
            val wake = if (upcoming != null && live == null && upcoming.getLong("start") - now > SOON_SECONDS) upcoming.getLong("start") - SOON_SECONDS else next
            val request = OneTimeWorkRequestBuilder<PairWorker>().setInitialDelay(maxOf(wake - now, 1), TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pair-alert", ExistingWorkPolicy.REPLACE, request)
        }
    }

    private const val SOON_SECONDS = 25 * 60L

    private fun scheduleTick(context: Context, seconds: Long) {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 5, Intent(context, PairReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + maxOf(seconds, 1L) * 1000, pending)
    }
}

/** The once-a-minute tick that moves the progress bar of the running lesson. */
class PairReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PairAlert.refresh(context.applicationContext)
    }
}

/** Wakes at the start and the end of every lesson to show or remove the countdown. */
class PairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PairAlert.refresh(applicationContext)
        return Result.success()
    }
}

/** The launcher icon is one of six aliases of the main screen, each in an accent colour; exactly one is enabled. */
object AppIcon {
    private fun name(id: String) = "online.myesstu.app.Icon" + id.replaceFirstChar { it.uppercase() }

    fun current(context: Context): String {
        val manager = context.packageManager
        return Accents.keys.firstOrNull {
            manager.getComponentEnabledSetting(android.content.ComponentName(context.packageName, name(it))) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: "blue"
    }

    fun set(context: Context, id: String) {
        val manager = context.packageManager
        fun apply(target: String, enabled: Boolean) = manager.setComponentEnabledSetting(
            android.content.ComponentName(context.packageName, name(target)),
            if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        apply(id, true)
        Accents.keys.filter { it != id }.forEach { apply(it, false) }
    }
}
