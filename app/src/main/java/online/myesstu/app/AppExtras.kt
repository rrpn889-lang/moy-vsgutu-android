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

class UpdateInfo(val name: String, val size: Long, val url: String, val notes: String)

/** Looks for a newer release on GitHub, downloads its APK and hands it to the system installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/rrpn889-lang/moy-vsgutu-android/releases/latest"

    /** The newest published release (its text is the description of the last commit), or null when GitHub did not answer. */
    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").get().build()
            Api.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val release = JSONObject(response.body?.string().orEmpty())
                val asset = release.arr("assets").objects().firstOrNull { it.s("name").endsWith(".apk") } ?: return@use null
                UpdateInfo(release.s("tag_name").removePrefix("v"), asset.l("size"), asset.s("browser_download_url"), release.s("body").trim())
            }
        }.getOrNull()
    }

    /** Compares dotted version numbers: 0.2.10 is newer than 0.2.9. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = installed.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun download(context: Context, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "exports/update.apk").apply { parentFile?.mkdirs() }
        val client = Api.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url(info.url).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiException("Не удалось скачать обновление (${response.code})", response.code)
                val body = response.body ?: throw ApiException("Сервер прислал пустой файл")
                body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        } catch (e: IOException) {
            throw ApiException("Нет связи с сервером")
        }
        target
    }

    /** False when Android first needs the person to allow installing from this app (the settings screen is opened). */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

/** "The app was updated" sheet: shown once per new build, with one button that downloads and installs it. */
@Composable
fun UpdatePrompt() {
    // Looks again every time the app comes to the front, so a build published meanwhile is noticed.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_START) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.key(tick) { UpdateSheet(manual = false, onClose = {}) }
}

/**
 * A small window over the screen. Started by hand it first checks and, with nothing new, says so with a single "Закрыть";
 * when a build is waiting it shows a single "Обновить". Started on launch it stays silent unless there is a build.
 */
@Composable
fun UpdateSheet(manual: Boolean, onClose: () -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var latest: UpdateInfo? = null
        for (attempt in 1..3) {
            latest = Updater.latest()
            if (latest != null) break
            kotlinx.coroutines.delay(2_000)
        }
        if (latest == null) failed = true
        else info = latest.takeIf { Updater.isNewer(it.name, AppSettings.versionName(context)) }
        checked = true
    }
    val update = info
    if (dismissed || (!manual && update == null)) return
    fun close() {
        dismissed = true
        onClose()
    }
    CenterDialog(::close) {
        when {
            !checked -> {
                Text("Проверяю обновления", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Loading()
            }
            update != null -> {
                Text("Вышло обновление", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Версия ${update.name}", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(
                    (if (update.notes.isNotEmpty()) update.notes + "\n\n" else "") + "Приложение скачается и установится само, данные сохранятся.",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                )
                if (message != null) Text(message!!, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                PrimaryButton("Обновить", loading = busy, onClick = {
                    scope.launch {
                        busy = true
                        message = null
                        try {
                            val file = Updater.download(context, update)
                            if (!Updater.install(context, file)) message = "Разреши установку из этого приложения и нажми «Обновить» ещё раз."
                        } catch (e: ApiException) {
                            message = e.message
                        } finally {
                            busy = false
                        }
                    }
                })
            }
            else -> {
                Text(if (failed) "Не удалось проверить" else "Обновлений нет", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (failed) "Нет связи с сервером. Попробуй позже." else "Установлена последняя версия ${AppSettings.versionName(context)}.",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                SecondaryButton("Закрыть", ::close)
            }
        }
    }
}

/** A small card in the middle of the screen over a dimmed background. */
@Composable
private fun CenterDialog(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
    }

    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val lessons = runCatching { JSONArray(prefs(context).getString("lessons", "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis() / 1000
        var live: JSONObject? = null
        var next = Long.MAX_VALUE
        for (i in 0 until lessons.length()) {
            val l = lessons.getJSONObject(i)
            val start = l.getLong("start")
            val end = l.getLong("end")
            if (now in start until end) live = l
            if (start > now) next = minOf(next, start)
            if (end > now) next = minOf(next, end)
        }
        if (live != null && AppSettings.wants(context, "pair") && AppSettings.canShow(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL, "Идёт пара", NotificationManager.IMPORTANCE_LOW)
                channel.description = "Сколько осталось до конца текущей пары"
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val end = live.getLong("end")
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val place = live.optString("place")
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_grade)
                .setContentTitle(live.optString("subject"))
                .setContentText(if (place.isNotEmpty()) "До конца пары · $place" else "До конца пары")
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(true)
                .setWhen(end * 1000).setUsesChronometer(true).setChronometerCountDown(true)
                .setTimeoutAfter((end - now) * 1000)
                .setContentIntent(open)
            manager.notify(ID, builder.build())
        } else {
            manager.cancel(ID)
        }
        if (next != Long.MAX_VALUE) {
            val request = OneTimeWorkRequestBuilder<PairWorker>().setInitialDelay(maxOf(next - now, 1), TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pair-alert", ExistingWorkPolicy.REPLACE, request)
        }
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
