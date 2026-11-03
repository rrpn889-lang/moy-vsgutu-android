package online.myesstu.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ru = Locale("ru")

/** Other groups' schedules, laid out like the website: a row of group chips, a view switch, the schedule, a remove link. */
@Composable
fun GroupsScreen(app: AppState) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableIntStateOf(1) }
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        try {
            val list = (Api.get("quick-groups") as? JSONArray)?.objects() ?: emptyList()
            groups = list
            if (list.none { it.s("id") == selected }) selected = list.firstOrNull()?.s("id").orEmpty()
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }

    val list = groups
    Screen(
        title = "Группы",
        subtitle = "Быстрый доступ к чужим расписаниям",
    ) {
        if (error != null) item { ErrorBox(error!!, { reload++ }) }
        if (list == null && error == null) item { Loading() }
        if (list != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list) { group -> Chip(group.s("group_title"), group.s("id") == selected) { selected = group.s("id") } }
                    }
                    Box(Modifier.padding(start = 10.dp)) { IconBtn(Icons.Filled.Add, "Добавить группу", { adding = !adding }, filled = true) }
                }
            }
            if (adding || list.isEmpty()) {
                item {
                    Panel {
                        Field(title, { title = it.take(32) }, "Например, К86АД")
                        if (message != null) Text(message!!, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        Box(Modifier.padding(top = 12.dp)) {
                            PrimaryButton("Добавить", enabled = title.trim().length >= 2, loading = busy, onClick = {
                                scope.launch {
                                    busy = true
                                    message = null
                                    try {
                                        val result = Api.post("quick-groups", JSONObject().put("title", title.trim())) as? JSONObject
                                        selected = result?.s("id").orEmpty()
                                        title = ""
                                        adding = false
                                        reload++
                                    } catch (e: ApiException) {
                                        message = e.message
                                    } finally {
                                        busy = false
                                    }
                                }
                            })
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                item { EmptyBox("Групп пока нет", "Добавь группу, чтобы посмотреть её расписание.") }
            } else {
                item { Segmented(listOf("Сейчас", "Сегодня", "Завтра", "Неделя"), view, fill = true) { view = it } }
                item(key = "schedule-$selected-$view") { GroupSchedule(app, selected, view) }
                item {
                    Text(
                        "Убрать выбранную группу", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().bounce {
                            scope.launch {
                                try {
                                    Api.delete("quick-groups/$selected")
                                    selected = ""
                                    reload++
                                } catch (e: ApiException) {
                                    message = e.message
                                }
                            }
                        }.padding(12.dp),
                    )
                }
            }
        }
    }
}

/** The chosen group's lessons for one of four views: now, today, tomorrow, week. */
@Composable
private fun GroupSchedule(app: AppState, groupId: String, view: Int) {
    val p = LocalPalette.current
    val today = remember { LocalDate.now(UniversityZone) }
    var days by remember(groupId) { mutableStateOf<List<Day>?>(null) }
    var error by remember(groupId) { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var opened by remember { mutableStateOf<Pair<Lesson, String>?>(null) }

    LaunchedEffect(groupId) {
        error = null
        try {
            val monday = today.with(DayOfWeek.MONDAY)
            val thisWeek = ScheduleRepo.week(app, groupId, monday)
            val nextWeek = runCatching { ScheduleRepo.week(app, groupId, monday.plusWeeks(1)) }.getOrNull()
            days = thisWeek.days + nextWeek?.days.orEmpty()
        } catch (e: ApiException) {
            error = e.message
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(15_000)
        }
    }

    val all = days
    when {
        error != null -> ErrorBox(error!!)
        all == null -> Loading()
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val lessons = all.flatMap { day -> day.lessons.map { day.date to it } }
            fun panel(day: Day, heading: String?) = @Composable {
                DayPanel(
                    day = day, heading = heading, isToday = day.date == today.toString(), now = now, canAdd = false,
                    modules = emptyList(), animationKey = groupId + view,
                    onOpen = { lesson -> opened = lesson to day.date }, onAdd = {},
                )
            }
            when (view) {
                0 -> {
                    val live = lessons.firstOrNull { (_, lesson) -> lesson.startTs > 0 && now in lesson.startTs until lesson.endTs }
                    val next = lessons.firstOrNull { (_, lesson) -> lesson.startTs > now }
                    Caption("Сейчас")
                    if (live != null) panel(Day(live.first, "", listOf(live.second)), null)() else EmptyBox("Сейчас пары нет", "В расписании группы сейчас занятий нет.")
                    Caption("Следующая пара")
                    if (next != null) panel(Day(next.first, "", listOf(next.second)), dayHeading(next.first))() else EmptyBox("Пар не видно", "В ближайшие две недели занятий не найдено.")
                }
                1, 2 -> {
                    val date = today.plusDays(if (view == 1) 0 else 1).toString()
                    val day = all.firstOrNull { it.date == date }
                    if (day == null || day.lessons.isEmpty()) EmptyBox("Пар нет", "В этот день занятий не найдено.") else panel(day, null)()
                }
                else -> {
                    val week = all.filter { it.date >= today.with(DayOfWeek.MONDAY).toString() && it.date <= today.with(DayOfWeek.MONDAY).plusDays(6).toString() && it.lessons.isNotEmpty() }
                    if (week.isEmpty()) EmptyBox("Пар нет", "На этой неделе занятий не найдено.")
                    week.forEach { panel(it, dayHeading(it.date))() }
                }
            }
        }
    }

    opened?.let { (lesson, date) ->
        LessonSheet(lesson, date, groupId, app.guest, onJump = { opened = null }) { opened = null }
    }
}

private fun dayHeading(date: String): String =
    LocalDate.parse(date).let { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Ru).replaceFirstChar { c -> c.uppercase() } + ", " + it.format(DateTimeFormatter.ofPattern("d MMMM", Ru)) }

@Composable
private fun Caption(text: String) {
    Text(text, color = LocalPalette.current.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
}
