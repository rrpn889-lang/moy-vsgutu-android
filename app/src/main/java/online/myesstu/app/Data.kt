package online.myesstu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** The university is in Ulan-Ude; the server schedules lessons in this zone. */
val UniversityZone: ZoneId = ZoneId.of("Asia/Irkutsk")

class ApiException(message: String, val code: Int = 0) : Exception(message)

// ---------- JSON helpers (org.json returns the text "null" for JSON null, so read safely) ----------
fun JSONObject.s(key: String): String {
    val v = opt(key)
    return if (v == null || v === JSONObject.NULL) "" else v.toString()
}

fun JSONObject.i(key: String): Int = if (isNull(key)) 0 else optInt(key, 0)
fun JSONObject.l(key: String): Long = if (isNull(key)) 0L else optLong(key, 0L)
fun JSONObject.flag(key: String): Boolean = when (val v = opt(key)) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v == "1" || v == "true"
    else -> false
}

fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

// ---------- Server API ----------
object Api {
    const val SITE = "https://my-esstu.online/esstu/"
    const val BASE = SITE + "public/api/"

    @Volatile
    var token: String = ""
    var onUnauthorized: () -> Unit = {}

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * The site sits behind Cloudflare, which some providers throttle. If that route fails the app
     * falls back to the server's own address; the certificate is the same, so the connection stays secure.
     */
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
            return if (hostname == "my-esstu.online") system + InetAddress.getByName("94.156.115.121") else system
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .dns(dns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder().header("X-Client", "app")
            if (token.isNotEmpty() && chain.request().url.host == "my-esstu.online") {
                builder.header("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }
        .build()

    private fun messageOf(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            JSONObject(trimmed).optString("message").ifEmpty { null }
        } catch (_: Exception) {
            if (trimmed.length < 300 && !trimmed.startsWith("<")) trimmed else null
        }
    }

    /**
     * Signs in again with the cabinet password saved on the phone. When a session expires the app
     * renews it quietly instead of throwing the person back to the login screen.
     */
    var refresh: (suspend () -> Boolean)? = null
    private val refreshLock = Mutex()

    private suspend fun run(request: Request, renew: Boolean = true): Any? {
        var attempt = 0
        while (true) {
            try {
                return execute(request)
            } catch (e: ApiException) {
                // A dropped connection on a read-only request is simply tried again.
                if (e.code == 0 && request.method == "GET" && attempt < 2) {
                    attempt++
                    delay(500L * attempt)
                    continue
                }
                if (e.code != 401) throw e
                val renewer = refresh
                if (renew && renewer != null && refreshLock.withLock { renewer() }) return execute(request)
                onUnauthorized()
                throw e
            }
        }
    }

    private suspend fun execute(request: Request): Any? = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401) {
                    throw ApiException(messageOf(text) ?: "Сессия истекла, войди снова", 401)
                }
                if (!response.isSuccessful) {
                    throw ApiException(messageOf(text) ?: "Ошибка сервера (${response.code})", response.code)
                }
                if (text.isBlank()) return@use null
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    throw ApiException("Некорректный ответ сервера", response.code)
                }
                if (!json.optBoolean("ok", true)) throw ApiException(json.optString("message", "Ошибка"), response.code)
                json.opt("data")
            }
        } catch (e: IOException) {
            throw ApiException("Нет связи с сервером (${e.javaClass.simpleName})", 0)
        }
    }

    suspend fun get(path: String): Any? = run(Request.Builder().url(BASE + path).get().build())

    suspend fun post(path: String, body: JSONObject? = null): Any? =
        run(Request.Builder().url(BASE + path).post((body ?: JSONObject()).toString().toRequestBody(JSON)).build())

    suspend fun patch(path: String, body: JSONObject): Any? =
        run(Request.Builder().url(BASE + path).patch(body.toString().toRequestBody(JSON)).build())

    suspend fun delete(path: String): Any? = run(Request.Builder().url(BASE + path).delete().build())

    suspend fun login(login: String, password: String): String {
        val body = JSONObject().put("login", login).put("password", password)
        val data = run(Request.Builder().url(SITE + "public/portal-login").post(body.toString().toRequestBody(JSON)).build(), renew = false)
        val token = (data as? JSONObject)?.s("token").orEmpty()
        if (token.isEmpty()) throw ApiException("Сервер не вернул токен входа")
        return token
    }

    suspend fun logout() {
        run(Request.Builder().url(SITE + "public/logout").post("{}".toRequestBody(JSON)).build())
    }

    suspend fun createNote(subject: String, kind: String, text: String, date: String?, period: Int?, photos: List<ByteArray>) {
        if (photos.isEmpty()) {
            val body = JSONObject().put("subject", subject).put("kind", kind).put("text", text)
            if (date != null && period != null) body.put("date", date).put("period", period)
            post("notes", body)
            return
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("subject", subject)
            .addFormDataPart("kind", kind)
            .addFormDataPart("text", text)
        if (date != null && period != null) form.addFormDataPart("date", date).addFormDataPart("period", period.toString())
        photos.forEachIndexed { index, bytes ->
            form.addFormDataPart("photo", "photo$index.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
        }
        run(Request.Builder().url(BASE + "notes").post(form.build()).build())
    }
}

// ---------- Models ----------
data class Note(val id: Long, val text: String, val author: String, val own: Boolean, val media: String, val photos: Int)

data class Lesson(
    val period: Int, val time: String, val kind: String, val subject: String, val teacher: String,
    val place: String, val merged: String, val startTs: Long, val endTs: Long, val notes: List<Note>,
)

data class Day(val date: String, val label: String, val lessons: List<Lesson>)
data class Schedule(val title: String, val subtitle: String, val weekStart: String, val days: List<Day>)

fun parseNote(o: JSONObject) = Note(o.l("id"), o.s("text"), o.s("author"), o.flag("own"), o.s("media_url"), o.i("photo_count"))

fun parseLesson(o: JSONObject) = Lesson(
    o.i("period"), o.s("time"), o.s("kind"), o.s("subject").ifEmpty { o.s("text") }, o.s("teacher"),
    o.s("place"), o.s("merged"), o.l("start_ts"), o.l("end_ts"), o.arr("notes").objects().map(::parseNote),
)

fun parseSchedule(o: JSONObject) = Schedule(
    o.s("title"), o.s("subtitle"), o.s("week_start"),
    o.arr("days").objects().map { d -> Day(d.s("date"), d.s("label"), d.arr("lessons").objects().map(::parseLesson)) },
)

/** Downloads a file (the grade table as a PDF) from our server. */
suspend fun Api.download(path: String): ByteArray = withContext(Dispatchers.IO) {
    try {
        client.newCall(Request.Builder().url(BASE + path).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Не удалось скачать файл (${response.code})", response.code)
            response.body?.bytes() ?: throw ApiException("Сервер прислал пустой файл")
        }
    } catch (e: IOException) {
        throw ApiException("Нет связи с сервером")
    }
}
