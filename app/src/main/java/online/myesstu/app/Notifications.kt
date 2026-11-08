package online.myesstu.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Notifications {
    private const val CHANNEL = "grades"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Новые оценки", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Сообщает, когда преподаватель ставит оценку"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun announce(context: Context, fresh: List<Pair<GradeModule, GradeMark>>) {
        if (fresh.isEmpty() || !AppSettings.wants(context, "grades") || !permitted(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val manager = NotificationManagerCompat.from(context)
        fun build(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_grade).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        if (fresh.size > 4) {
            manager.notify(1, build("Новые оценки", "Появилось оценок: ${fresh.size}"))
            return
        }
        fresh.forEachIndexed { index, (module, mark) ->
            val subject = module.name.substringBefore('·').trim()
            val text = if (mark.value in setOf("Н", "О", "Б")) "$subject: отметка ${mark.value}" else "$subject: ${mark.value}"
            manager.notify(10 + index, build("Новая оценка", text))
        }
    }
}

/** Looks at the cabinet every half hour and announces grades that were not there last time. */
class GradeWatcher(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val credentials = runCatching { Vault.load(applicationContext) }.getOrNull() ?: return Result.success()
        val modules = try {
            Portal.grades(credentials)
        } catch (e: PortalException) {
            return if (e.auth) Result.success() else Result.retry()
        }
        val store = applicationContext.getSharedPreferences("grade_watch", Context.MODE_PRIVATE)
        val known = store.getStringSet("marks", null)
        fun key(module: GradeModule, mark: GradeMark) = "${module.name}|${mark.date}|${mark.value}"
        if (known != null) {
            val fresh = modules.flatMap { module -> module.marks.filter { key(module, it) !in known }.map { module to it } }
            Notifications.announce(applicationContext, fresh)
        }
        // The first run only remembers what is already there, so nobody gets a flood of old grades.
        store.edit().putStringSet("marks", modules.flatMap { m -> m.marks.map { key(m, it) } }.toSet()).apply()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GradeWatcher>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("grade-watch", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
