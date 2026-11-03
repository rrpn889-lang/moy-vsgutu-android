package online.myesstu.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

private class SubjectRow(val subject: String, val marks: List<GradeMark>)

private sealed interface GradesState {
    data object Loading : GradesState
    class NeedPassword(val message: String? = null) : GradesState
    class Failed(val message: String) : GradesState
    class Ready(val rows: List<SubjectRow>, val direct: Boolean) : GradesState
}


private fun cleanSubject(raw: String): String =
    raw.split(Regex("[·•|]"))[0].replace(Regex(",?\\s*п/г\\s*[12]", RegexOption.IGNORE_CASE), "").trim()

private fun dateLabel(raw: String): String {
    val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(raw) ?: return ""
    return "${match.groupValues[3]}.${match.groupValues[2]}"
}

/** Lectures and practicals of one subject are shown together, like on the website. */
private fun buildRows(modules: List<GradeModule>): List<SubjectRow> {
    val grouped = LinkedHashMap<String, MutableList<GradeMark>>()
    for (module in modules) {
        val subject = cleanSubject(module.name)
        if (subject.isEmpty()) continue
        grouped.getOrPut(subject) { mutableListOf() }.addAll(module.marks)
    }
    return grouped.map { (subject, marks) -> SubjectRow(subject, marks.sortedBy { it.date }) }.sortedBy { it.subject.lowercase() }
}

private fun average(marks: List<GradeMark>): String? {
    val numbers = marks.mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
    if (numbers.isEmpty()) return null
    return String.format(Locale("ru"), "%.1f", numbers.average())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradesPage(app: AppState, onBack: () -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<GradeResult?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    // The saved copy is on screen at once; the fresh one replaces it when it arrives.
    LaunchedEffect(reload) {
        result = null
        result = GradeStore.refresh(app, context, force = true)
    }

    val modules = app.gradeModules
    val done = result
    val current: GradesState = when {
        modules.isNotEmpty() -> GradesState.Ready(buildRows(modules), direct = done is GradeResult.Ok)
        done == null -> GradesState.Loading
        done is GradeResult.NeedPassword -> GradesState.NeedPassword(done.message)
        done is GradeResult.Failed -> GradesState.Failed(done.message)
        else -> GradesState.Ready(emptyList(), direct = true)
    }
    Screen(title = "Оценки", subtitle = "Весь семестр по предметам") {
        item { BackChip("Профиль", onBack) }
        when (current) {
            is GradesState.Loading -> item { Loading() }
            is GradesState.Failed -> item { ErrorBox(current.message, { reload++ }) }
            is GradesState.NeedPassword -> item {
                PasswordForm(app, current.message) { login, password ->
                    scope.launch {
                        runCatching { Vault.save(context, login, password) }
                        Portal.forget()
                        reload++
                    }
                }
            }
            is GradesState.Ready -> {
                if (current.rows.isEmpty()) item { EmptyBox("Оценок пока нет", "Они появятся, когда преподаватели поставят баллы.") }
                items(current.rows.size) { index ->
                    val row = current.rows[index]
                    Panel(Modifier.appear(index, "grades")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.subject, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            average(row.marks)?.let { Tag("Средний $it") }
                        }
                        if (row.marks.isEmpty()) {
                            Text("Пока без оценок", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            FlowRow(
                                Modifier.padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.marks.forEach { mark ->
                                    val color = markColor(mark.value, p.dark) ?: p.muted
                                    Column(
                                        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.17f))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(mark.value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        val label = dateLabel(mark.date)
                                        if (label.isNotEmpty()) Text(label, color = p.muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item { ExportPanel() }
                item {
                    Text(
                        when {
                            done == null -> "Обновляю оценки…"
                            current.direct -> "Оценки обновлены."
                            else -> "Не удалось обновить, показаны сохранённые оценки."
                        },
                        color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

private suspend fun serverFallback(reason: String?): GradesState = try {
    val data = Api.get("grades") as? JSONObject
    if (data == null || !data.flag("connected")) GradesState.Failed(reason ?: "Оценки недоступны")
    else GradesState.Ready(buildRows(Portal.fromServer(data)), direct = false)
} catch (e: ApiException) {
    GradesState.Failed(reason ?: e.message ?: "Оценки недоступны")
}

@Composable
private fun PasswordForm(app: AppState, message: String?, onSubmit: (String, String) -> Unit) {
    val p = LocalPalette.current
    var login by remember { mutableStateOf(app.me?.s("email").orEmpty()) }
    var password by remember { mutableStateOf("") }
    Panel {
        Text("Подключить кабинет напрямую", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Оценки приложение берёт прямо из кабинета ВСГУТУ. Для этого введи данные кабинета ещё раз: пароль хранится только в защищённой памяти этого телефона.",
            color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        Field(login, { login = it }, "Почта или логин", keyboard = KeyboardType.Email)
        Box(Modifier.padding(top = 10.dp)) { Field(password, { password = it }, "Пароль", password = true) }
        if (message != null) Text(message, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.padding(top = 14.dp)) {
            PrimaryButton("Подключить", enabled = login.isNotBlank() && password.isNotEmpty(), onClick = { onSubmit(login.trim(), password) })
        }
    }
}

/** Saves the grade table as a PDF, the same file the website gives, and opens the share sheet. */
@Composable
private fun ExportPanel() {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("Таблица оценок", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "Все предметы и даты одним файлом PDF, как на сайте.",
            color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (error != null) Text(error!!, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        PrimaryButton("Выгрузить таблицу", loading = busy, onClick = {
            scope.launch {
                busy = true
                error = null
                try {
                    val bytes = Api.download("grades/export")
                    val uri = withContext(Dispatchers.IO) {
                        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
                        val file = File(folder, "esstu-grades.pdf")
                        file.writeBytes(bytes)
                        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Таблица оценок"))
                } catch (e: ApiException) {
                    error = e.message
                } catch (e: Exception) {
                    error = "Не удалось сохранить файл"
                } finally {
                    busy = false
                }
            }
        })
    }
}
