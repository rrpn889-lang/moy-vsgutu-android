package online.myesstu.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateInfo(val name: String, val size: Long, val url: String, val notes: String)

/** What the update window shows, kept outside the window so it survives the installer coming and going. */
object UpdateState {
    var installing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** The version whose window was closed by the person; it is not shown again until the app is restarted. */
    var dismissedVersion: String? = null
}

/** Looks for a newer release on GitHub, downloads its APK and installs it through the system installer. */
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

    /**
     * Downloads the file, reporting progress from 0 to 1. A cut-off download continues from where it stopped, and the file is
     * handed to the installer only when it is exactly as long as GitHub says.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "exports/update.apk").apply { parentFile?.mkdirs() }
        val client = Api.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        target.delete()
        var failure: Exception? = null
        for (attempt in 1..6) {
            try {
                val have = if (target.exists()) target.length() else 0L
                if (info.size > 0 && have == info.size) break
                val request = Request.Builder().url(info.url).apply { if (have > 0) header("Range", "bytes=$have-") }.get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ApiException("Не удалось скачать обновление (${response.code})", response.code)
                    val append = have > 0 && response.code == 206
                    if (!append) target.delete()
                    val body = response.body ?: throw ApiException("GitHub прислал пустой файл")
                    body.byteStream().use { input ->
                        FileOutputStream(target, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = if (append) have else 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (info.size > 0) onProgress((done.toFloat() / info.size).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (info.size <= 0 || target.length() == info.size) break
            } catch (e: IOException) {
                failure = e
            }
            delay(1_000)
        }
        if (info.size > 0 && target.length() != info.size) {
            target.delete()
            throw ApiException(if (failure != null) "Нет связи с GitHub. Проверь интернет и повтори." else "Файл скачался не полностью. Повтори ещё раз.")
        }
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("notes", info.notes).putString("version", info.name).apply()
        target
    }

    /**
     * Installs through the system installer, which reports why it refused if it does. False when Android first needs the person
     * to allow installing from this app (the settings screen is opened).
     */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input -> session.openWrite("update.apk", 0, file.length()).use { output -> input.copyTo(output); session.fsync(output) } }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            UpdateState.installing = true
            UpdateState.error = null
            session.commit(result.intentSender)
        }
        return true
    }
}

/** Receives the installer's answers: asks for confirmation when needed and reports a refusal in words. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateState.installing = false
            else -> {
                UpdateState.installing = false
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    UpdateState.error = "Android не смог установить обновление" + if (reason.isNotEmpty()) ": $reason" else ""
                }
            }
        }
    }
}

/** After the app was replaced by a newer build, says so and shows what changed. */
class UpdatedReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !AppSettings.canShow(context)) return
        val saved = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val notes = saved.getString("notes", "").orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("updates", "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_stat_grade)
            .setContentTitle("Приложение обновлено до ${AppSettings.versionName(context)}")
            .setContentText(notes.lineSequence().firstOrNull().orEmpty().ifEmpty { "Нажми, чтобы открыть" })
            .setContentIntent(open).setAutoCancel(true)
        if (notes.isNotEmpty()) builder.setStyle(NotificationCompat.BigTextStyle().bigText(notes))
        NotificationManagerCompat.from(context).notify(4, builder.build())
        saved.edit().remove("notes").apply()
    }
}

@Composable
fun UpdatePrompt() {
    // Looks again every time the app comes to the front, so a build published meanwhile is noticed.
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    key(tick) { UpdateSheet(manual = false, onClose = {}) }
}

/**
 * A small window in the middle of the screen. Started by hand it first checks and, with nothing new, says so with a single
 * "Закрыть"; when a build is waiting it shows a single "Обновить" with the download progress. Started on launch it stays silent
 * unless there is a build, and it does not come back for a version the person already closed.
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
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // A window that is already installing something must not be opened a second time.
        if (!manual && UpdateState.installing) return@LaunchedEffect
        var latest: UpdateInfo? = null
        for (attempt in 1..3) {
            latest = Updater.latest()
            if (latest != null) break
            delay(2_000)
        }
        if (latest == null) failed = true
        else info = latest.takeIf { Updater.isNewer(it.name, AppSettings.versionName(context)) }
        checked = true
    }
    val update = info
    val hidden = !manual && update != null && UpdateState.dismissedVersion == update.name
    if (dismissed || hidden || (!manual && update == null)) return
    fun close() {
        dismissed = true
        if (update != null) UpdateState.dismissedVersion = update.name
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
                val error = message ?: UpdateState.error
                if (error != null) Text(error, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                if (busy || UpdateState.installing) {
                    // The bar fills while the file downloads, then the installer takes over.
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(if (UpdateState.installing) 1f else progress).clip(RoundedCornerShape(3.dp)).background(accent))
                    }
                    Text(
                        if (UpdateState.installing) "Устанавливаю…" else "Скачиваю ${(progress * 100).toInt()}%",
                        color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    PrimaryButton("Обновить", onClick = {
                        scope.launch {
                            busy = true
                            progress = 0f
                            message = null
                            UpdateState.error = null
                            try {
                                val file = Updater.download(context, update) { progress = it }
                                if (!Updater.install(context, file)) message = "Разреши установку из этого приложения и нажми «Обновить» ещё раз."
                            } catch (e: ApiException) {
                                message = e.message
                            } catch (e: Exception) {
                                message = "Не получилось начать установку: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    })
                }
            }
            else -> {
                Text(if (failed) "Не удалось проверить" else "Обновлений нет", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (failed) "Нет связи с GitHub. Попробуй позже." else "Установлена последняя версия ${AppSettings.versionName(context)}.",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                SecondaryButton("Закрыть", ::close)
            }
        }
    }
}
