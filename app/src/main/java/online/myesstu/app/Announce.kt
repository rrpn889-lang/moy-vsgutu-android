package online.myesstu.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.concurrent.TimeUnit

class Announcement(val id: Long, val text: String)

/** Messages from the author to every user, sent from the admin page: shown as a window on opening and as a notification. */
object Announcements {
    private fun prefs(context: Context) = context.getSharedPreferences("announcements", Context.MODE_PRIVATE)

    fun lastSeen(context: Context): Long = prefs(context).getLong("seen", 0L)

    fun markSeen(context: Context, id: Long) = prefs(context).edit().putLong("seen", maxOf(id, lastSeen(context))).apply()

    private fun lastNotified(context: Context): Long = prefs(context).getLong("notified", 0L)

    /** Unseen messages, newest first; empty when the server does not answer. */
    suspend fun fetch(context: Context, after: Long): List<Announcement> = try {
        (Api.get("app/announcements?after=$after") as? JSONArray)?.objects().orEmpty().map { Announcement(it.l("id"), it.s("text")) }
    } catch (_: ApiException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun notify(context: Context, items: List<Announcement>) {
        val fresh = items.filter { it.id > lastNotified(context) }
        if (fresh.isEmpty() || !AppSettings.notificationsWanted(context) || !AppSettings.canShow(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("news", "Сообщения от автора", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val newest = fresh.first()
        val notification = NotificationCompat.Builder(context, "news")
            .setSmallIcon(R.drawable.ic_stat_grade)
            .setContentTitle("Мой ВСГУТУ")
            .setContentText(newest.text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(newest.text))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(5, notification)
        prefs(context).edit().putLong("notified", newest.id).apply()
    }
}

/** Every half hour looks for a message from the author, so it arrives even when the app is closed. */
class AnnounceWatcher(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val token = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("token", "").orEmpty()
        if (token.isEmpty()) return Result.success()
        Api.token = token
        Announcements.notify(context, Announcements.fetch(context, Announcements.lastSeen(context)))
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnnounceWatcher>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("announce-watch", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The window with the author's message; it closes with one button and does not come back for the same message. */
@Composable
fun AnnouncePrompt() {
    val context = LocalContext.current
    val p = LocalPalette.current
    var items by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    LaunchedEffect(Unit) { items = Announcements.fetch(context, Announcements.lastSeen(context)) }
    if (items.isEmpty()) return
    fun close() {
        Announcements.markSeen(context, items.maxOf { it.id })
        items = emptyList()
    }
    CenterDialog(::close) {
        Text("Сообщение от автора", color = LocalAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            Text(item.text, color = p.text, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
        }
        PrimaryButton("Понятно", onClick = ::close, modifier = Modifier.padding(top = 16.dp))
    }
}
