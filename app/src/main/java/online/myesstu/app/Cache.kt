package online.myesstu.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Everything the screens show first comes from here, so opening the app never waits for the network. */
object AppCache {
    private var dir: File? = null

    /** Work that must outlive a screen, such as refreshing a page in the background. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped when a background refresh brought something new; screens that show cached data read it to reload. */
    var revision by mutableIntStateOf(0)

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "cache").apply { mkdirs() }
    }

    private fun file(key: String): File? = dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80) + "_" + hash(key)) }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)

    fun get(key: String): String? = runCatching { file(key)?.takeIf { it.exists() }?.readText() }.getOrNull()

    fun put(key: String, value: String) {
        runCatching { file(key)?.writeText(value) }
    }

    fun clear() {
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }
}

/** Grades saved on the phone: the last copy is shown at once while a fresh one is being fetched. */
object GradeStore {
    fun load(): List<GradeModule> {
        val text = AppCache.get("grades") ?: return emptyList()
        return runCatching {
            JSONArray(text).objects().map { module ->
                GradeModule(module.s("name"), module.arr("marks").objects().map { GradeMark(it.s("v"), it.s("d")) })
            }
        }.getOrDefault(emptyList())
    }

    fun save(modules: List<GradeModule>) {
        val array = JSONArray()
        modules.forEach { module ->
            val marks = JSONArray()
            module.marks.forEach { marks.put(JSONObject().put("v", it.value).put("d", it.date)) }
            array.put(JSONObject().put("name", module.name).put("marks", marks))
        }
        AppCache.put("grades", array.toString())
    }

    /**
     * Asks our server and the cabinet at the same time. Whichever answers first is shown, and the cabinet's
     * answer, which is the freshest, replaces it. Returns an error text only when nothing could be shown at all.
     */
    private var lastRefresh = 0L

    suspend fun refresh(app: AppState, context: Context, force: Boolean = false): GradeResult = coroutineScope {
        if (!force && app.gradeModules.isNotEmpty() && System.currentTimeMillis() - lastRefresh < 120_000) return@coroutineScope GradeResult.Ok
        lastRefresh = System.currentTimeMillis()
        val credentials = runCatching { Vault.load(context) }.getOrNull()
        val server = async {
            runCatching {
                val data = Api.get("grades") as? JSONObject
                if (data != null && data.flag("connected")) Portal.fromServer(data) else null
            }.getOrNull()
        }
        val direct = async { credentials?.let { runCatching { Portal.grades(it) } } }

        fun publish(modules: List<GradeModule>) {
            app.gradeModules = modules
            save(modules)
        }
        select<Unit> {
            server.onAwait { found -> if (found != null && !direct.isCompleted) publish(found) }
            direct.onAwait { }
        }
        val result = direct.await()
        val serverCopy = server.await()
        when {
            result == null -> {
                // No password saved: the server's copy is all there is.
                if (serverCopy != null) {
                    publish(serverCopy)
                    GradeResult.Ok
                } else GradeResult.NeedPassword(null)
            }
            result.isSuccess -> {
                publish(result.getOrThrow())
                GradeResult.Ok
            }
            else -> {
                val error = result.exceptionOrNull() as? PortalException
                when {
                    serverCopy != null -> {
                        if (app.gradeModules.isEmpty()) publish(serverCopy)
                        GradeResult.Ok
                    }
                    error?.auth == true -> GradeResult.NeedPassword(error.message)
                    app.gradeModules.isNotEmpty() -> GradeResult.Ok
                    else -> GradeResult.Failed(error?.message ?: "Оценки недоступны")
                }
            }
        }
    }
}

sealed interface GradeResult {
    data object Ok : GradeResult
    class NeedPassword(val message: String?) : GradeResult
    class Failed(val message: String) : GradeResult
}
