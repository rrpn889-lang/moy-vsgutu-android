package online.myesstu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class Group(val title: String, val url: String)

/** One cell of the timetable page, before the subgroup is applied. */
class RawLesson(val day: Int, val period: Int, val text: String, val kind: String, val room: String, val merged: String)

class ParsedPage(val title: String, val weeks: Map<Int, List<RawLesson>>)

/** Reads the public timetable at portal.esstu.ru directly; no server of ours is involved. */
object University {
    private const val ROOT = "https://portal.esstu.ru/"
    private val sections = listOf("spezialitet/raspisan.htm", "bakalavriat/raspisan.htm", "zo1/raspisan.htm", "zo2/raspisan.htm")
    private val periods = listOf("09:00" to "10:35", "10:45" to "12:20", "13:00" to "14:35", "14:45" to "16:20", "16:25" to "18:00", "18:05" to "19:40")
        .map { (start, end) -> LocalTime.parse(start) to LocalTime.parse(end) }
    private val dayNames = mapOf("пнд" to 0, "втр" to 1, "срд" to 2, "чтв" to 3, "птн" to 4, "сбт" to 5)

    /** Monday of the first of the two alternating weeks; the server uses the same anchor. */
    private val weekOne = LocalDate.parse("2026-09-28")

    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var groupsCache: List<Group>? = null
    private var groupsAt = 0L
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ParsedPage>>()
    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) MoyVsgutu/0.2").build()).awaitResponse().use { response ->
                if (!response.isSuccessful) throw ApiException("Сайт университета ответил кодом ${response.code}")
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val declared = response.body?.contentType()?.charset()
                String(bytes, declared ?: Charset.forName("windows-1251"))
            }
        } catch (e: IOException) {
            throw ApiException("Сайт университета не отвечает")
        }
    }

    fun clean(value: String?): String {
        val text = (value ?: "").replace(Regex("\\s+"), " ").trim()
        return if (text in setOf("_", "-", "–", "—")) "" else text
    }

    private fun normal(value: String) = value.uppercase().replace('Ё', 'Е').replace(Regex("[^A-ZА-Я0-9]"), "")

    suspend fun groups(): List<Group> {
        val cached = groupsCache
        if (cached != null && System.currentTimeMillis() - groupsAt < 3_600_000) return cached
        val found = coroutineScope {
            sections.map { section ->
                async {
                    runCatching {
                        val base = ROOT + section
                        Jsoup.parse(fetch(base), base).select("a[href]").mapNotNull { link ->
                            val title = clean(link.text())
                            if (title.isNotEmpty() && Regex("[А-ЯA-Z]\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(title)) Group(title, link.absUrl("href")) else null
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        val unique = found.distinctBy { it.url }
        if (unique.isEmpty()) throw ApiException("Не удалось загрузить список групп")
        groupsCache = unique
        groupsAt = System.currentTimeMillis()
        return unique
    }

    suspend fun findGroup(title: String): Group? {
        val wanted = normal(title)
        val all = groups()
        return all.firstOrNull { normal(it.title) == wanted } ?: all.firstOrNull { normal(it.title).let { n -> n.contains(wanted) || wanted.contains(n) } }
    }

    /**
     * A timetable page, never waiting when an older copy exists: the copy in memory or on the phone is returned
     * at once and the page is refreshed quietly; screens reload when the refresh found changes.
     */
    suspend fun page(url: String): ParsedPage {
        val key = "page_$url"
        pages[url]?.let { (at, parsed) ->
            if (System.currentTimeMillis() - at >= 90_000) refreshInBackground(url)
            return parsed
        }
        AppCache.get(key)?.let { html ->
            val parsed = parse(html)
            pages[url] = 0L to parsed
            refreshInBackground(url)
            return parsed
        }
        val html = fetch(url)
        AppCache.put(key, html)
        val parsed = parse(html)
        pages[url] = System.currentTimeMillis() to parsed
        return parsed
    }

    /** Always reads the page from the site; used by the background check for changes. */
    suspend fun fresh(url: String): ParsedPage {
        val html = fetch(url)
        AppCache.put("page_$url", html)
        val parsed = parse(html)
        pages[url] = System.currentTimeMillis() to parsed
        return parsed
    }

    private fun refreshInBackground(url: String) {
        if (!refreshing.add(url)) return
        AppCache.scope.launch {
            try {
                val key = "page_$url"
                val html = fetch(url)
                if (html != AppCache.get(key)) {
                    AppCache.put(key, html)
                    pages[url] = System.currentTimeMillis() to parse(html)
                    AppCache.revision++
                } else {
                    pages[url]?.let { pages[url] = System.currentTimeMillis() to it.second }
                }
            } catch (_: Exception) {
            } finally {
                refreshing.remove(url)
            }
        }
    }

    private fun parse(html: String): ParsedPage {
        val doc = Jsoup.parse(html)
        val text = clean(doc.text())
        val title = Regex("групп[ыа]\\s*:\\s*([КK]\\s*\\d+\\s*/?\\s*[А-ЯA-Z]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.replace(" ", "").orEmpty()
        val weeks = mapOf(1 to ArrayList<RawLesson>(), 2 to ArrayList())
        val table = doc.select("table").firstOrNull() ?: return ParsedPage(title, weeks)
        val rows = table.select("tr").drop(2).mapNotNull { tr ->
            val cells = tr.select("td")
            val day = if (cells.size >= 7) dayNames[clean(cells[0].text()).lowercase()] else null
            day?.let { it to cells.subList(1, 7) }
        }
        rows.forEachIndexed { i, (day, cells) ->
            val week = if (i < 6) 1 else 2
            cells.forEachIndexed { period, cell ->
                val text = clean(cell.text())
                if (text.isEmpty()) return@forEachIndexed
                val lower = text.lowercase()
                val kind = when {
                    lower.startsWith("лек") -> "лекция"
                    lower.startsWith("пр") -> "практика"
                    lower.startsWith("лаб") -> "лабораторная"
                    else -> "занятие"
                }
                val room = Regex("(?:^|\\s)а\\.\\s*([^\\s]+)").findAll(text).lastOrNull()?.groupValues?.get(1).orEmpty()
                val beforeRoom = text.split(Regex("\\sа\\."), limit = 2)[0]
                val refs = Regex("(?<![А-ЯЁA-Z])([А-ЯЁA-Z]{1,8}\\s*\\d{1,3}(?:\\s*/\\s*[А-ЯЁA-Z]+)?)(?![А-ЯЁA-Z0-9])")
                    .findAll(beforeRoom).map { clean(it.groupValues[1]) }.distinct().joinToString(", ")
                weeks.getValue(week).add(RawLesson(day, period, text, kind, room, refs))
            }
        }
        return ParsedPage(title, weeks)
    }

    // ---- text helpers, ported one to one from the bot ----

    fun teachers(text: String): List<String> {
        val patterns = listOf(
            Regex("([А-ЯЁ][А-ЯЁа-яё-]{2,}\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.)"),
            Regex("([А-ЯЁ]\\.\\s*[А-ЯЁ]\\.\\s*[А-ЯЁ][А-ЯЁа-яё-]{2,})"),
        )
        return patterns.flatMap { p -> p.findAll(text).map { clean(it.groupValues[1]) }.toList() }.distinct()
    }

    fun title(text: String): String {
        var value = text.replace(Regex("^(?:лек|пр|лаб)\\.\\s*", RegexOption.IGNORE_CASE), "")
        val first = teachers(value).firstOrNull()
        if (first != null) {
            val pos = value.indexOf(first)
            if (pos > 0) value = value.substring(0, pos)
        }
        value = value.replace(Regex("\\s*-?\\s*[12]\\s*п\\s*/\\s*г\\s*", RegexOption.IGNORE_CASE), " ")
        return clean(value).trim(' ', ',', '.', '-').ifEmpty { "Занятие" }
    }

    private fun validMerge(value: String): String = value.split(",").map { clean(it) }.filter {
        it.length <= 40 && it.any(Char::isDigit) &&
            (Regex("[А-ЯЁA-Z]").containsMatchIn(it) || Regex("\\d+(?:[./-]\\w+)+").matches(it))
    }.distinct().joinToString(", ")

    private fun roomLabel(room: String): String {
        val value = room.trim()
        val (building, cabinet) = when {
            Regex("^0\\d{3}").containsMatchIn(value) -> "10" to value.substring(1, 4)
            Regex("^\\d{4}").containsMatchIn(value) -> value.substring(0, 1) to value.substring(1, 4)
            else -> {
                val m = Regex("^(\\d{1,2})[- ]?(.*)").find(value)
                when {
                    m != null && m.groupValues[1].toInt() in 1..25 -> m.groupValues[1] to m.groupValues[2].ifEmpty { value }
                    "проф" in value.lowercase() -> "14" to value
                    "спорт" in value.lowercase() -> "спорт" to value
                    else -> "" to value
                }
            }
        }
        return when {
            building.isNotEmpty() && cabinet.isNotEmpty() -> "$building корпус, кабинет $cabinet"
            building.isNotEmpty() -> "$building корпус"
            else -> room.ifEmpty { "корпус уточняется" }
        }
    }

    /** Keeps the lessons of one subgroup; a cell listing two teachers and rooms is cut down to the subgroup's own. */
    private fun forSubgroup(lessons: List<RawLesson>, subgroup: Int): List<RawLesson> {
        if (subgroup != 1 && subgroup != 2) return lessons
        val rooms = Regex("(?:^|\\s)а\\.\\s*([^\\s]+)")
        return lessons.mapNotNull { lesson ->
            val label = Regex("([12])\\s*п\\s*/\\s*г").find(lesson.text.lowercase())
            if (label != null && label.groupValues[1].toInt() != subgroup) return@mapNotNull null
            val names = teachers(lesson.text)
            val roomList = rooms.findAll(lesson.text).map { it.groupValues[1] }.toList()
            if (label == null && names.size >= 2 && roomList.size >= 2) {
                val i = if (subgroup == 1) 0 else 1
                val start = lesson.text.indexOf(names[0])
                val prefix = if (start >= 0) lesson.text.substring(0, start) else ""
                RawLesson(lesson.day, lesson.period, clean("$prefix${names[i]} а.${roomList[i]}"), lesson.kind, roomList[i], lesson.merged)
            } else lesson
        }
    }

    // ---- the alternating weeks ----

    fun localBaseWeek(): Int {
        val today = LocalDate.now(UniversityZone)
        val monday = today.with(DayOfWeek.MONDAY)
        return if (Math.floorMod(Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(weekOne, monday), 7L), 2L) == 0L) 1 else 2
    }

    private fun weekFor(baseWeek: Int, target: LocalDate): Int {
        val today = LocalDate.now(UniversityZone)
        val shift = Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(today.with(DayOfWeek.MONDAY), target.with(DayOfWeek.MONDAY)), 7L)
        return if (Math.floorMod(shift, 2L) == 0L) baseWeek else 3 - baseWeek
    }

    private fun epoch(date: LocalDate, time: LocalTime): Long = LocalDateTime.of(date, time).atZone(UniversityZone).toEpochSecond()

    /** One week of one group, shaped like the app's [Schedule]. Notes are attached by date and period. */
    fun week(page: ParsedPage, subgroup: Int, baseWeek: Int, anchor: LocalDate, fallbackTitle: String, notes: Map<Pair<String, Int>, List<Note>>): Schedule {
        val monday = anchor.with(DayOfWeek.MONDAY)
        val number = weekFor(baseWeek, monday)
        val lessons = forSubgroup(page.weeks[number].orEmpty(), subgroup)
        val days = (0..6).map { d ->
            val date = monday.plusDays(d.toLong())
            val items = lessons.filter { it.day == d }.sortedBy { it.period }.map { raw ->
                val (start, end) = periods[raw.period]
                Lesson(
                    period = raw.period + 1,
                    time = "$start-$end",
                    kind = raw.kind.replaceFirstChar { it.uppercase() },
                    subject = title(raw.text),
                    teacher = teachers(raw.text).joinToString(", "),
                    place = roomLabel(raw.room),
                    merged = validMerge(raw.merged),
                    startTs = epoch(date, start),
                    endTs = epoch(date, end),
                    notes = notes[date.toString() to raw.period + 1].orEmpty(),
                )
            }
            Day(date.toString(), "", items)
        }
        return Schedule(page.title.ifEmpty { fallbackTitle }, "$number-я неделя", monday.toString(), days)
    }
}

/** Decides where a week comes from: the university site first, our server only as a safety net. */
object ScheduleRepo {
    private var loadedExtras = false

    /** Homework notes by lesson, as last received from our server (kept on the phone too). */
    @Volatile
    private var lastNotes: Map<Pair<String, Int>, List<Note>> = emptyMap()

    /** Lessons shared with other groups, by week; also kept on the phone. */
    private val mergeCache = java.util.concurrent.ConcurrentHashMap<String, Map<Pair<String, Int>, String>>()
    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Reads what the server told us last time, so the first screen needs no answer from it. */
    private fun restoreExtras() {
        if (loadedExtras) return
        loadedExtras = true
        runCatching {
            AppCache.get("notes_targets")?.let { text ->
                lastNotes = JSONArray(text).objects().groupBy({ it.s("date") to it.i("period") }, { parseNote(it) })
            }
            AppCache.get("merges")?.let { text ->
                val all = JSONObject(text)
                for (key in all.keys()) {
                    val week = all.getJSONObject(key)
                    mergeCache[key] = week.keys().asSequence().associate { entry ->
                        val (date, period) = entry.split("|")
                        (date to period.toInt()) to week.getString(entry)
                    }
                }
            }
        }
    }

    private fun saveMerges() {
        val all = JSONObject()
        for ((key, value) in mergeCache) {
            val week = JSONObject()
            for ((slot, text) in value) week.put("${slot.first}|${slot.second}", text)
            all.put(key, week)
        }
        AppCache.put("merges", all.toString())
    }

    suspend fun week(app: AppState, groupId: String?, monday: LocalDate): Schedule {
        if (app.guest) {
            val group = app.guestGroup ?: throw ApiException("Выбери группу в профиле")
            return University.week(University.page(group.url), group.subgroup, University.localBaseWeek(), monday, group.title, emptyMap())
        }
        restoreExtras()
        return try {
            withTimeoutOrNull(9_000) { direct(app, groupId, monday) } ?: fromServer(groupId, monday)
        } catch (e: ApiException) {
            if (e.code == 401) throw e
            fromServer(groupId, monday)
        }
    }

    /**
     * The group settings, and whether they are fresh. The copy saved on the phone is used at once and the server is
     * asked quietly, so the schedule never waits for it; the week number is then worked out locally.
     */
    private suspend fun config(app: AppState): Pair<JSONObject, Boolean> {
        if (app.configFresh) return (app.config ?: throw ApiException("Нет настроек группы")) to true
        val saved = app.config
        if (saved != null) {
            if (!app.configRefreshing) {
                app.configRefreshing = true
                AppCache.scope.launch {
                    try {
                        val data = Api.get("app/config") as? JSONObject
                        if (data != null) {
                            if (data.toString() != saved.toString()) AppCache.revision++
                            app.config = data
                            app.configFresh = true
                        }
                    } catch (_: Exception) {
                    } finally {
                        app.configRefreshing = false
                    }
                }
            }
            return saved to false
        }
        // First start: nothing is saved yet, so the server has to answer once.
        val data = Api.get("app/config") as? JSONObject ?: throw ApiException("Нет настроек группы")
        app.config = data
        app.configFresh = true
        return data to true
    }

    private suspend fun direct(app: AppState, groupId: String?, monday: LocalDate): Schedule {
        val (config, fresh) = config(app)
        val baseWeek = (if (fresh) config.i("base_week") else 0).takeIf { it in 1..2 } ?: University.localBaseWeek()
        if (groupId == null) {
            val url = config.s("group_url")
            if (url.isEmpty()) throw ApiException("Сначала выбери группу")
            refreshExtras(groupId = null, monday = monday, withNotes = true)
            val schedule = University.week(University.page(url), config.i("subgroup"), baseWeek, monday, config.s("group_title"), lastNotes)
            return withMerges(schedule, mergeCache["own:$monday"])
        }
        val quick = quickGroups().firstOrNull { it.s("id") == groupId } ?: throw ApiException("Группа не найдена")
        refreshExtras(groupId = groupId, monday = monday, withNotes = false)
        val group = University.findGroup(quick.s("group_title")) ?: throw ApiException("Группа не найдена на сайте университета")
        val schedule = University.week(University.page(group.url), quick.i("subgroup"), baseWeek, monday, quick.s("group_title"), emptyMap())
        return withMerges(schedule, mergeCache["$groupId:$monday"])
    }

    /** The saved groups: the last answer is returned at once and a fresh one is fetched in the background. */
    suspend fun quickGroups(): List<JSONObject> {
        val saved = AppCache.get("quick_groups")?.let { runCatching { JSONArray(it).objects() }.getOrNull() }
        if (saved != null) {
            if (refreshing.add("quick")) {
                AppCache.scope.launch {
                    try {
                        val data = (Api.get("quick-groups") as? JSONArray) ?: return@launch
                        if (data.toString() != AppCache.get("quick_groups")) {
                            AppCache.put("quick_groups", data.toString())
                            AppCache.revision++
                        }
                    } catch (_: Exception) {
                    } finally {
                        refreshing.remove("quick")
                    }
                }
            }
            return saved
        }
        val data = Api.get("quick-groups") as? JSONArray ?: return emptyList()
        AppCache.put("quick_groups", data.toString())
        return data.objects()
    }

    /**
     * Notes and shared lessons come from our server, which can be slow to reach. They are fetched in the background;
     * the screen shows what was received last time and reloads when something new arrives.
     */
    private fun refreshExtras(groupId: String?, monday: LocalDate, withNotes: Boolean) {
        val key = "${groupId ?: "own"}:$monday"
        if (!refreshing.add(key)) return
        AppCache.scope.launch {
            try {
                var changed = false
                if (withNotes) {
                    runCatching {
                        val list = (Api.get("notes/targets") as? JSONArray)?.objects().orEmpty()
                        val fresh = list.groupBy({ it.s("date") to it.i("period") }, { parseNote(it) })
                        if (fresh.toString() != lastNotes.toString()) changed = true
                        lastNotes = fresh
                        AppCache.put("notes_targets", JSONArray(list).toString())
                    }
                }
                runCatching {
                    val fresh = fromServer(groupId, monday).days.flatMap { day ->
                        day.lessons.filter { it.merged.isNotBlank() }.map { (day.date to it.period) to it.merged }
                    }.toMap()
                    if (fresh != mergeCache[key]) changed = true
                    mergeCache[key] = fresh
                    saveMerges()
                }
                if (changed) AppCache.revision++
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun withMerges(schedule: Schedule, merges: Map<Pair<String, Int>, String>?): Schedule {
        if (merges == null) return schedule
        return schedule.copy(days = schedule.days.map { day ->
            day.copy(lessons = day.lessons.map { it.copy(merged = merges[day.date to it.period].orEmpty()) })
        })
    }

    private suspend fun fromServer(groupId: String?, monday: LocalDate): Schedule {
        val path = "schedule?view=week&date=$monday" + (if (groupId != null) "&group_id=$groupId" else "")
        return parseSchedule(Api.get(path) as JSONObject)
    }
}
