package online.myesstu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ru = Locale("ru")

private fun dayLabel(date: String): String =
    runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EE, d MMM", Ru)) }.getOrDefault(date)

/** Teacher directory, laid out like the website: a search card, then result rows. */
@Composable
fun SearchScreen() {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var ready by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<JSONObject?>(null) }

    BackHandler(opened != null) { opened = null }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        try {
            val data = Api.get("search/teachers?q=" + URLEncoder.encode(query.trim(), "UTF-8")) as? JSONObject
            results = data?.arr("teachers")?.objects().orEmpty()
            ready = data?.optBoolean("ready", true) ?: true
            error = null
        } catch (e: ApiException) {
            error = e.message
        } finally {
            searching = false
        }
    }

    val teacher = opened
    if (teacher != null) {
        TeacherScreen(teacher.s("id"), teacher.s("name")) { opened = null }
        return
    }

    Screen(title = "Поиск", subtitle = "Расписание преподавателей") {
        item {
            Panel {
                Text("Кто и где ведёт пару", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Найти преподавателя", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                Text(
                    "Напиши фамилию или её начало. Найду текущую пару и расписание на неделю.",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                Field(query, { query = it }, "Фамилия преподавателя", leadingIcon = Icons.Filled.Search)
                when {
                    query.trim().length < 2 -> Text(
                        "Начни вводить фамилию: достаточно двух букв.",
                        color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
                    )
                    searching -> Loading()
                    error != null -> Text(error!!, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    results.isEmpty() -> Text(
                        if (ready) "Не нашёл преподавателя. Проверь фамилию или попробуй без инициалов." else "Индекс расписаний ещё загружается. Попробуй через несколько минут.",
                        color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        if (!searching && error == null) {
            items(results.size) { index ->
                val item = results[index]
                Row(
                    Modifier.fillMaxWidth().appear(index, "search").clip(RoundedCornerShape(20.dp)).background(p.surface)
                        .bounce { opened = item }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, null, tint = accent, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.s("name"), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Сейчас и расписание недели", color = p.muted, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.muted)
                }
            }
        }
    }
}

@Composable
private fun TeacherScreen(id: String, name: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onBack)
    LaunchedEffect(id, week, reload) {
        error = null
        try {
            val path = "search/teachers/" + URLEncoder.encode(id, "UTF-8") + (if (week != null) "?date=$week" else "")
            data = Api.get(path) as? JSONObject
        } catch (e: ApiException) {
            error = e.message
        }
    }

    Screen(title = name, subtitle = "Преподаватель") {
        item { BackChip("К поиску", onBack) }
        item {
            Panel {
                Segmented(listOf("Сейчас", "Расписание"), mode, fill = true) { mode = it }
            }
        }
        if (error != null) item { ErrorBox(error!!, { reload++ }) }
        if (data == null && error == null) item { Loading() }
        val d = data
        if (d != null && mode == 0) {
            val current = d.arr("current").objects()
            val next = d.arr("next").objects()
            item { SectionTitle("Текущая пара") }
            if (current.isEmpty()) item { EmptyBox("Сейчас пары нет", "Сейчас занятий в публичном расписании не найдено.") }
            current.forEach { item { TeacherLesson(it, showDate = true) } }
            item { SectionTitle("Следующая пара") }
            if (next.isEmpty()) item { EmptyBox("Пар не видно", "В ближайшие две недели занятия не найдены.") }
            next.forEach { item { TeacherLesson(it, showDate = true) } }
        }
        if (d != null && mode == 1) {
            val start = d.s("week_start")
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущая неделя", { week = LocalDate.parse(start).minusWeeks(1).toString() })
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val from = LocalDate.parse(start)
                            val format = DateTimeFormatter.ofPattern("d MMM", Ru)
                            Text("${from.format(format)} — ${from.plusDays(6).format(format)}", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("${d.i("week")}-я неделя", color = p.muted, fontSize = 12.sp)
                        }
                        IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующая неделя", { week = LocalDate.parse(start).plusWeeks(1).toString() })
                    }
                }
            }
            val days = d.arr("days").objects().filter { it.arr("lessons").length() > 0 }
            if (days.isEmpty()) item { EmptyBox("Занятий нет", "На этой неделе пар в публичном расписании не найдено.") }
            days.forEach { day ->
                item { SectionTitle(dayLabel(day.s("date")).replaceFirstChar { it.uppercase() }) }
                day.arr("lessons").objects().forEach { lesson -> item { TeacherLesson(lesson, showDate = false) } }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalPalette.current.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeacherLesson(lesson: JSONObject, showDate: Boolean) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(lesson.s("time"), color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "${lesson.i("period")}-я пара" + if (showDate) " · " + dayLabel(lesson.s("date")) else "",
                color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
        Text(lesson.s("subject"), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(
            lesson.s("kind") + " · " + lesson.s("place").ifEmpty { "Кабинет не указан" },
            color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
        )
        val groups = lesson.arr("groups").strings()
        if (groups.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { Tag(it) }
            }
        }
        if (lesson.flag("conflict")) {
            Text(
                "В источнике есть несколько занятий в это время. Уточни по группе и кабинету.",
                color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
