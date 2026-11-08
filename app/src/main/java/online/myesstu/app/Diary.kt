package online.myesstu.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private val Ru = Locale("ru")

private fun shortDay(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(DayStyle.SHORT, Ru).replaceFirstChar { it.uppercase() }

private fun fullDay(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(DayStyle.FULL, Ru).replaceFirstChar { it.uppercase() } + ", " + date.format(DateTimeFormatter.ofPattern("d MMMM", Ru))

private fun remaining(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    if (minutes <= 1) return "меньше минуты"
    if (minutes < 60) return "$minutes мин"
    val hours = minutes / 60
    val rest = minutes % 60
    return "$hours ч" + if (rest > 0) " $rest мин" else ""
}

private fun key(text: String) = text.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9]"), "")

/** Finds the grades given for this lesson: same day, same subject and, when the journal names one, the same kind. */
private fun marksFor(modules: List<GradeModule>, date: String, lesson: Lesson): List<GradeMark> {
    val subject = key(lesson.subject)
    val kind = key(lesson.kind)
    return modules.filter { module ->
        val parts = module.name.split(Regex("[·•|]"))
        val name = key(parts[0].replace(Regex(",?\\s*п/г\\s*[12]", RegexOption.IGNORE_CASE), ""))
        val moduleKind = key(parts.getOrNull(1).orEmpty())
        (name.startsWith(subject.take(10)) || subject.startsWith(name.take(10))) &&
            (moduleKind.isEmpty() || kind.startsWith(moduleKind.take(2)))
    }.flatMap { it.marks }.filter { it.date == date }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(app: AppState, groupId: String? = null, title: String? = null, onBack: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val context = LocalContext.current
    val today = remember { LocalDate.now(UniversityZone) }
    val firstWeek = remember {
        val monday = today.with(DayOfWeek.MONDAY)
        if (today.dayOfWeek.value >= 6) monday.plusWeeks(1) else monday
    }
    var weekStart by remember { mutableStateOf(firstWeek) }
    var selected by remember { mutableStateOf(if (today.dayOfWeek.value >= 6) firstWeek else today) }
    var weekMode by rememberSaveable { mutableStateOf(false) }
    var data by remember { mutableStateOf<Schedule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var composer by remember { mutableStateOf<NoteTarget?>(null) }
    var opened by remember { mutableStateOf<Pair<Lesson, String>?>(null) }
    var picking by remember { mutableStateOf(false) }

    val cacheKey = "${groupId ?: "own"}:$weekStart"
    val revision = AppCache.revision
    LaunchedEffect(cacheKey, reload, revision) {
        data = app.scheduleCache[cacheKey]
        loading = true
        error = null
        try {
            val parsed = ScheduleRepo.week(app, groupId, weekStart)
            app.scheduleCache[cacheKey] = parsed
            if (groupId == null) PairAlert.remember(context, parsed)
            data = parsed
        } catch (e: ApiException) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(15_000)
        }
    }
    // Grades are read from the cabinet quietly, so they can be shown next to the lesson they belong to.
    LaunchedEffect(app.guest, app.token) {
        if (app.guest || groupId != null) return@LaunchedEffect
        GradeStore.refresh(app, context)
    }

    val schedule = data
    val markDays = remember(app.gradeModules, app.guest) {
        if (app.guest) emptySet() else app.gradeModules.flatMap { module -> module.marks.map { it.date } }.toSet()
    }
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM", Ru) }
    val liveLesson = if (groupId == null) {
        schedule?.days?.firstOrNull { it.date == today.toString() }?.lessons?.firstOrNull { it.startTs > 0 && now in it.startTs until it.endTs }
    } else null

    PullToRefreshBox(isRefreshing = loading && schedule != null, onRefresh = { reload++ }) {
        Screen(title = title ?: "Дневник", subtitle = schedule?.title?.takeIf { it.isNotEmpty() }) {
            if (onBack != null) item { BackChip("Группы", onBack) }
            if (liveLesson != null) item { LiveChip(liveLesson, now) }
            item {
                Panel(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(schedule?.subtitle.orEmpty(), color = p.muted, fontSize = 12.sp)
                            Text(
                                "${weekStart.format(dayFormat)} – ${weekStart.plusDays(6).format(dayFormat)}",
                                color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Назад", { weekStart = weekStart.minusWeeks(1); selected = weekStart })
                            IconBtn(Icons.Filled.DateRange, "Выбрать дату", { picking = true })
                            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Вперёд", { weekStart = weekStart.plusWeeks(1); selected = weekStart })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected == today) accent else accent.copy(alpha = 0.14f))
                                .bounce { weekStart = today.with(DayOfWeek.MONDAY); selected = today }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("Сегодня", color = if (selected == today) Color.White else accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1f))
                        Segmented(listOf("День", "Неделя"), if (weekMode) 1 else 0) { weekMode = it == 1 }
                    }
                    DayStrip(weekStart, selected, today, markDays) { selected = it }
                }
            }

            if (schedule == null && loading) item { Loading() }
            if (error != null && schedule == null) item { ErrorBox(error!!, { reload++ }) }
            if (schedule != null) {
                val shownDays = if (weekMode) schedule.days.filter { it.lessons.isNotEmpty() } else schedule.days.filter { it.date == selected.toString() }
                if (shownDays.all { it.lessons.isEmpty() }) {
                    item { EmptyBox("Пар нет", "В этот день занятий не найдено.") }
                } else {
                    shownDays.forEach { day ->
                        item(key = day.date) {
                            DayPanel(
                                day = day,
                                heading = if (weekMode) fullDay(LocalDate.parse(day.date)) else null,
                                isToday = day.date == today.toString(),
                                now = now,
                                canAdd = groupId == null && !app.guest,
                                modules = app.gradeModules,
                                animationKey = cacheKey + weekMode + selected,
                                onOpen = { lesson -> opened = lesson to day.date },
                                onAdd = { lesson ->
                                    composer = NoteTarget(lesson.subject, lesson.kind, day.date, lesson.period, started = lesson.startTs in 1..now)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        selected = date
                        weekStart = date.with(DayOfWeek.MONDAY)
                    }
                    picking = false
                }) { Text("Выбрать", color = accent) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена", color = p.muted) } },
            colors = DatePickerDefaults.colors(containerColor = p.surface),
        ) {
            DatePicker(
                state = state,
                colors = DatePickerDefaults.colors(
                    containerColor = p.surface, titleContentColor = p.muted, headlineContentColor = p.text,
                    weekdayContentColor = p.muted, dayContentColor = p.text, selectedDayContainerColor = accent,
                    selectedDayContentColor = Color.White, todayContentColor = accent, todayDateBorderColor = accent,
                    yearContentColor = p.text, currentYearContentColor = accent, selectedYearContainerColor = accent,
                    selectedYearContentColor = Color.White, navigationContentColor = p.text,
                ),
            )
        }
    }

    composer?.let { target ->
        NoteComposer(target) { saved ->
            composer = null
            if (saved) reload++
        }
    }
    opened?.let { (lesson, date) -> LessonSheet(
        lesson, date, groupId, app.guest,
        onJump = { target -> selected = target; weekStart = target.with(DayOfWeek.MONDAY); weekMode = false; opened = null },
    ) { opened = null } }
}


/** Seven days of the week; the selection slides from one day to another. */
@Composable
/** A dot under a day means grades were given that day, as on the website. */
private fun DayStrip(weekStart: LocalDate, selected: LocalDate, today: LocalDate, markDays: Set<String>, onSelect: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val index = java.time.temporal.ChronoUnit.DAYS.between(weekStart, selected).toInt().coerceIn(0, 6)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val cell = maxWidth / 7
        val offset by animateDpAsState(cell * index, spring(dampingRatio = 0.8f, stiffness = 450f), label = "day")
        Box(Modifier.offset(x = offset).width(cell).height(66.dp).padding(horizontal = 2.dp).clip(RoundedCornerShape(15.dp)).background(accent))
        Row {
            for (i in 0..6) {
                val date = weekStart.plusDays(i.toLong())
                val isSelected = date == selected
                val hasMarks = date.toString() in markDays
                Column(
                    Modifier.width(cell).height(66.dp).bounce { onSelect(date) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(shortDay(date), color = if (isSelected) Color.White else p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        date.dayOfMonth.toString(),
                        color = if (isSelected) Color.White else if (date == today) accent else p.text,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        Modifier.padding(top = 3.dp).size(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (hasMarks) (if (isSelected) Color.White else accent) else Color.Transparent),
                    )
                }
            }
        }
    }
}

/** A lesson in progress, pinned above the week like on the website. */
@Composable
private fun LiveChip(lesson: Lesson, now: Long) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val progress by animateFloatAsState(((now - lesson.startTs).toFloat() / (lesson.endTs - lesson.startTs)).coerceIn(0f, 1f), label = "chip")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(accent.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(accent)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(lesson.subject, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "До конца ${remaining(lesson.endTs - now)} · пара до ${lesson.time.substringAfter("-")}",
                color = p.muted, fontSize = 13.sp,
            )
        }
        Box(Modifier.width(64.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        }
    }
}

/** All lessons of a day in one card, separated by thin lines, as on the website. */
@Composable
internal fun DayPanel(
    day: Day,
    heading: String?,
    isToday: Boolean,
    now: Long,
    canAdd: Boolean,
    modules: List<GradeModule>,
    animationKey: Any,
    onOpen: (Lesson) -> Unit,
    onAdd: (Lesson) -> Unit,
) {
    val p = LocalPalette.current
    Panel(padding = 4.dp) {
        if (heading != null) {
            Text(
                heading, color = if (isToday) LocalAccent.current else p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp),
            )
        }
        if (day.lessons.isEmpty()) {
            Text("Пар нет", color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
        }
        day.lessons.forEachIndexed { index, lesson ->
            if (index > 0) Box(Modifier.padding(start = 66.dp, end = 14.dp).fillMaxWidth().height(0.5.dp).background(p.line))
            LessonRow(
                lesson = lesson,
                marks = marksFor(modules, day.date, lesson),
                isToday = isToday,
                now = now,
                canAdd = canAdd,
                onOpen = { onOpen(lesson) },
                onAdd = { onAdd(lesson) },
                modifier = Modifier.appear(index, animationKey),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonRow(
    lesson: Lesson,
    marks: List<GradeMark>,
    isToday: Boolean,
    now: Long,
    canAdd: Boolean,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val live = lesson.startTs > 0 && now in lesson.startTs until lesson.endTs
    val progress by animateFloatAsState(
        if (live) ((now - lesson.startTs).toFloat() / (lesson.endTs - lesson.startTs)).coerceIn(0f, 1f) else 0f,
        label = "progress",
    )

    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (live) accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(
                // A shared lesson gets the website's gold bar on its left edge.
                if (lesson.merged.isNotEmpty()) Modifier.drawBehind {
                    drawRoundRect(MergeBar, topLeft = Offset(0f, 14.dp.toPx()), size = Size(3.dp.toPx(), size.height - 28.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                } else Modifier,
            )
            .bounce(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(p.soft), contentAlignment = Alignment.Center) {
                Text(lesson.period.toString(), color = p.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            val parts = lesson.time.split("-")
            Text(parts.getOrElse(0) { "" }, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text(parts.getOrElse(1) { "" }, color = p.muted, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(lesson.subject, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                lesson.kind.ifEmpty { "Занятие" } + (if (lesson.place.isNotEmpty()) " · ${lesson.place}" else ""),
                color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
            )
            if (lesson.teacher.isNotEmpty()) Text(lesson.teacher, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lesson.merged.isNotEmpty()) Text("Совмещено: ${lesson.merged}", color = if (p.dark) MergeBar else MergeText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (live) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(accent, 8.dp)
                    Text(
                        "Сейчас · до конца ${remaining(lesson.endTs - now)}",
                        color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(progress).height(5.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                }
            }
            if (marks.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    marks.forEach { mark ->
                        val color = markColor(mark.value, p.dark) ?: p.muted
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.17f)).padding(horizontal = 9.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(mark.value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            if (lesson.notes.isNotEmpty()) Homework(lesson.notes)
        }
        if (canAdd) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (isToday) accent.copy(alpha = 0.14f) else p.soft)
                    .bounce(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, "Добавить заметку", tint = if (isToday) accent else p.muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Homework on a lesson, styled like the website: a thin accent bar on the left, a small caption, friends' names as tags. */
@Composable
internal fun Homework(notes: List<Note>) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    var viewer by remember { mutableStateOf<List<String>?>(null) }
    viewer?.let { PhotoViewer(it, 0) { viewer = null } }
    Row(Modifier.padding(top = 10.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(accent.copy(alpha = 0.3f)))
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("ДОМАШНЕЕ ЗАДАНИЕ", color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.55.sp)
            notes.take(2).forEach { note ->
                Row(verticalAlignment = Alignment.Top) {
                    if (!note.own) {
                        Text(
                            note.author, color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 6.dp, top = 1.dp).clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        note.text.ifEmpty { "Вложение к заданию" }.replace(Regex("\\s+"), " "),
                        color = p.text, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.media.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val urls = (0 until maxOf(1, note.photos)).map { Api.BASE + note.media + "?index=$it" }
                        RemoteImage(urls[0], Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).bounce { viewer = urls })
                        if (note.photos > 1) Text("+${note.photos - 1}", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (notes.size > 2) Text("Открыть остальные задания", color = p.muted, fontSize = 10.sp)
        }
    }
}
