package online.myesstu.app

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.TimeUnit

class PortalException(message: String, val auth: Boolean = false) : Exception(message)

class GradeMark(val value: String, val date: String)
class GradeModule(val name: String, val marks: List<GradeMark>)

class Credentials(val login: String, val password: String, val fingerprint: String)

/** The cabinet password lives only on this phone, in Android's encrypted storage. */
object Vault {
    private fun open(context: Context) = EncryptedSharedPreferences.create(
        context, "vault",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** If the phone's key store ever invalidates the key, the unreadable file is dropped and a fresh one is made. */
    private fun prefs(context: Context) = try {
        open(context)
    } catch (e: Exception) {
        context.deleteSharedPreferences("vault")
        open(context)
    }

    fun save(context: Context, login: String, password: String) {
        val store = prefs(context)
        val fingerprint = store.getString("fingerprint", null) ?: newFingerprint()
        // commit() writes to disk before returning, so the password survives the app being killed right after sign-in.
        store.edit().putString("login", login).putString("password", password).putString("fingerprint", fingerprint).commit()
    }

    fun load(context: Context): Credentials? {
        val store = prefs(context)
        val login = store.getString("login", null) ?: return null
        val password = store.getString("password", null) ?: return null
        return Credentials(login, password, store.getString("fingerprint", null) ?: newFingerprint())
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    private fun newFingerprint(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Talks to the university cabinet (spo.esstu.ru) directly from the phone. */
object Portal {
    private const val ROOT = "https://spo.esstu.ru"
    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var token = ""

    private fun request(path: String, bearer: String? = null) = Request.Builder().url(ROOT + path).apply {
        header("Accept", "application/json, text/plain, */*")
        header("Origin", ROOT)
        header("Referer", "$ROOT/WebApp/")
        header("User-Agent", "Mozilla/5.0 (Linux; Android) MoyVsgutu/0.2")
        if (bearer != null) {
            header("Authorization", "Bearer $bearer")
            header("Cookie", "authToken=$bearer")
        }
    }

    private suspend fun send(builder: Request.Builder): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        var failure: IOException? = null
        repeat(2) { attempt ->
            try {
                return@withContext client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    response.code to runCatching { JSONObject(text) }.getOrNull()
                }
            } catch (e: IOException) {
                failure = e
                if (attempt == 0) delay(400)
            }
        }
        throw PortalException("Кабинет не отвечает (${failure?.javaClass?.simpleName}). Проверь интернет и повтори")
    }

    private fun find(value: Any?, names: Set<String>): String? {
        when (value) {
            is JSONObject -> {
                for (key in value.keys()) {
                    val item = value.opt(key)
                    if (key.lowercase() in names && item != null && item !== JSONObject.NULL && item.toString().isNotEmpty()) return item.toString()
                }
                for (key in value.keys()) find(value.opt(key), names)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) find(value.opt(i), names)?.let { return it }
        }
        return null
    }

    private fun authMessage(payload: JSONObject?): String {
        val text = find(payload, setOf("message", "errormessage", "description", "error")).orEmpty().trim()
        if (text.isEmpty() || text.length > 240) return "Кабинет отклонил логин или пароль"
        val lowered = text.lowercase()
        return if (listOf("парол", "логин", "пользовател", "не верн", "неверн").any { it in lowered }) {
            "Кабинет сообщил, что логин или пароль неверны"
        } else text
    }

    private suspend fun authenticate(credentials: Credentials): String {
        val body = JSONObject()
            .put("userName", credentials.login.trim())
            .put("password", credentials.password)
            .put("isParent", false)
            .put("fingerprint", credentials.fingerprint)
            .put("recaptchaToken", JSONObject.NULL)
        val (status, payload) = send(request("/api/tokenauth").post(body.toString().toRequestBody(JSON)))
        if (status in listOf(400, 401, 403)) throw PortalException(authMessage(payload), auth = true)
        if (status >= 500) throw PortalException("Кабинет временно недоступен")
        val access = find(payload, setOf("accesstoken", "token"))
        if (access.isNullOrEmpty() || find(payload, setOf("state")) == "-1") throw PortalException(authMessage(payload), auth = true)
        return access
    }

    private suspend fun get(path: String, bearer: String): JSONObject {
        val (status, payload) = send(request(path, bearer))
        if (status == 401 || status == 403) throw PortalException("Сессия кабинета истекла", auth = true)
        if (status >= 500) throw PortalException("Кабинет временно недоступен")
        return payload ?: throw PortalException("Кабинет вернул неверный ответ")
    }

    /** The student id sits in a claim whose name ends with /sid. */
    private fun studentId(token: String): String {
        return runCatching {
            val part = token.split(".")[1]
            val claims = JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            claims.keys().asSequence().firstNotNullOfOrNull { key ->
                val lowered = key.lowercase()
                if (lowered.endsWith("/sid") || lowered in setOf("sid", "userid", "user_id")) {
                    claims.opt(key).toString().trim().trimStart('-').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                } else null
            }
        }.getOrNull().orEmpty()
    }

    /** Loads every grade the student has. Signs in again once if the saved token has expired. */
    suspend fun grades(credentials: Credentials): List<GradeModule> {
        var retried = false
        while (true) {
            try {
                if (token.isEmpty()) token = authenticate(credentials)
                return loadGrades(token)
            } catch (e: PortalException) {
                if (e.auth && !retried) {
                    retried = true
                    token = ""
                    continue
                }
                throw e
            }
        }
    }

    fun forget() {
        token = ""
    }

    private suspend fun loadGrades(token: String): List<GradeModule> {
        val userId = studentId(token)
        val now = Calendar.getInstance()
        val semester = if (now.get(Calendar.MONTH) + 1 in 2..7) 2 else 1
        val ratingPath = "/api/UserInfo/Rating?year=${now.get(Calendar.YEAR)}&sem=$semester" + if (userId.isNotEmpty()) "&userID=$userId" else ""
        val fromRating = ratingModules(get(ratingPath, token))
        if (fromRating.isNotEmpty()) return fromRating

        val list = get("/api/Journals/JournalList", token)
        val journals = list.optJSONObject("data")?.optJSONArray("returnList")?.objects().orEmpty()
        val semaphore = Semaphore(6)
        return coroutineScope {
            journals.filter { !it.isNull("id") }.map { journal ->
                async {
                    semaphore.withPermit {
                        val payload = get("/api/Journals/Journal?journalID=${journal.s("id")}", token)
                        journalModule(payload, journal, userId) ?: throw PortalException("Не удалось распознать журнал")
                    }
                }
            }.awaitAll()
        }
    }

    private fun text(value: Any?): String = if (value == null || value === JSONObject.NULL) "" else value.toString().trim()

    /** One journal: cells hold dictionary ids, so "9" means a mark, "Н" an absence, and blanks are skipped. */
    private fun journalModule(payload: JSONObject, journal: JSONObject, studentId: String): GradeModule? {
        val data = payload.optJSONObject("data") ?: return null
        if (!data.has("journalData") || !data.has("journalDates") || !data.has("journalVal")) return null
        val dates = data.arr("journalDates").objects().associateBy { it.s("dateID") }
        val rows = data.arr("journalData").objects()
        val info = data.optJSONObject("journalInfo") ?: JSONObject()
        var subject = info.s("dis").ifEmpty { journal.s("dis") }.ifEmpty { "Без названия" }
        val kind = info.s("type").ifEmpty { journal.s("type") }
        if (kind.isNotEmpty()) subject = "$subject · $kind"
        val values = data.arr("journalVal").objects().filter { it.has("id") }.associateBy { it.s("id") }
        if (dates.isNotEmpty() && rows.isNotEmpty() && values.isEmpty()) return null
        val row = rows.firstOrNull { studentId.isNotEmpty() && it.s("id").trimStart('-') == studentId.trimStart('-') }
            ?: if (studentId.isEmpty() && rows.size == 1) rows[0] else null
        if (rows.isNotEmpty() && row == null) return null
        val marks = ArrayList<GradeMark>()
        for ((dateId, meta) in dates) {
            val code = text(row?.opt(dateId))
            if (code.isEmpty() || code == "-") continue
            val definition = values[code] ?: return null
            val value = text(definition.opt("value"))
            if (value.isEmpty()) continue
            marks += GradeMark(value, meta.s("date").take(10))
        }
        return GradeModule(subject, marks)
    }

    private fun ratingModules(payload: JSONObject): List<GradeModule> {
        val rows = payload.optJSONObject("data")?.optJSONArray("rating")?.objects().orEmpty()
        val modules = LinkedHashMap<String, Pair<String, ArrayList<GradeMark>>>()
        for (row in rows) {
            val name = listOf("discipline", "subject", "name", "categoryName").map { text(row.opt(it)) }.firstOrNull { it.isNotEmpty() } ?: "Без названия"
            val key = listOf("disciplineID", "subjectID", "categoryID").map { text(row.opt(it)) }.firstOrNull { it.isNotEmpty() } ?: name
            val entry = modules.getOrPut(key) { name to ArrayList() }
            val mark = listOf("mark", "value", "score", "points").map { text(row.opt(it)) }.firstOrNull { it.isNotEmpty() } ?: continue
            val date = listOf("date", "dateMark", "createdAt").map { text(row.opt(it)) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            entry.second += GradeMark(mark, date.take(10))
        }
        return modules.values.filter { it.second.isNotEmpty() }.map { GradeModule(it.first, it.second) }
    }

    /** The server's copy of the same data, used only when the cabinet cannot be reached directly. */
    fun fromServer(data: JSONObject): List<GradeModule> = data.arr("modules").objects().map { module ->
        GradeModule(
            module.s("module_name"),
            module.arr("ratings").objects().filter { it.s("mark").isNotEmpty() }.map { GradeMark(it.s("mark"), it.s("awarded_at").take(10)) },
        )
    }
}
