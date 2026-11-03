package online.myesstu.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

data class NoteTarget(val subject: String, val kind: String, val date: String, val period: Int, val started: Boolean)

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

@Composable
fun NoteComposer(target: NoteTarget, onClose: (saved: Boolean) -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { picked ->
        photos = (photos + picked).distinct().take(10)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { photos = (photos + it).take(10) }
    }
    fun takePhoto() {
        val file = File(context.cacheDir, "camera/shot_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraUri = uri
        camera.launch(uri)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else error = "Без доступа к камере снимок не сделать. Разреши камеру в настройках телефона."
    }

    Sheet(onDismiss = { onClose(false) }) {
        Text(
            (if (target.started) "Задание на следующую пару" else "Новая заметка") + " · " + target.kind.ifEmpty { "занятие" },
            color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(target.subject, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (target.started) "Пара уже началась или прошла, поэтому задание попадёт на следующую пару этого предмета и типа. Друзья тоже увидят его."
            else "Задание появится у выбранной пары. Друзья тоже увидят эту заметку.",
            color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        Field(text, { text = it.take(1000) }, "Текст, домашнее задание или подпись к фото", singleLine = false, minLines = 4)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                SecondaryButton("Выбрать фото", { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            }
            Box(Modifier.weight(1f)) {
                SecondaryButton("Сделать фото", {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
                    else askCamera.launch(Manifest.permission.CAMERA)
                })
            }
        }
        if (photos.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos) { uri ->
                    Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp))) {
                        RemoteImage(uri, Modifier.size(76.dp))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(RoundedCornerShape(11.dp))
                                .background(Color(0x99000000)).bounce { photos = photos - uri },
                            contentAlignment = Alignment.Center,
                        ) { Text("×", color = Color.White, fontSize = 15.sp) }
                    }
                }
            }
        }
        Text("До 10 фото. Они сжимаются перед отправкой.", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        if (error != null) Text(error!!, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.padding(top = 14.dp)) {
            PrimaryButton(
                "Сохранить",
                enabled = text.isNotBlank() || photos.isNotEmpty(),
                loading = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val bytes = withContext(Dispatchers.IO) { photos.map { compressPhoto(context, it) } }
                            Api.createNote(target.subject, target.kind.lowercase(), text.trim(), target.date, target.period, bytes)
                            onClose(true)
                        } catch (e: ApiException) {
                            error = e.message
                        } catch (e: Exception) {
                            error = "Не удалось обработать фото"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

/** "05.11" to a full date: the year is whichever puts it within the last two months or the future. */
fun dateFromDayMonth(text: String): java.time.LocalDate? {
    val parts = text.split(".")
    val day = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val today = java.time.LocalDate.now(UniversityZone)
    val date = runCatching { java.time.LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
    return if (date < today.minusDays(60)) date.plusYears(1) else date
}

@Composable
fun LessonSheet(lesson: Lesson, date: String, groupId: String?, guest: Boolean, onJump: (java.time.LocalDate) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        if (guest) return@LaunchedEffect
        try {
            val query = "lesson-details?subject=${enc(lesson.subject)}&kind=${enc(lesson.kind.lowercase())}&date=$date&period=${lesson.period}" +
                (if (groupId != null) "&group_id=$groupId" else "")
            details = Api.get(query) as? JSONObject
        } catch (e: ApiException) {
            error = e.message
        }
    }

    Sheet(onDismiss = onClose) {
        Text(lesson.kind.ifEmpty { "Занятие" } + " · " + lesson.time, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(lesson.subject, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        val d = details
        if (guest) {
            if (lesson.teacher.isNotEmpty()) InfoLine("Преподаватель", lesson.teacher)
            if (lesson.place.isNotEmpty()) InfoLine("Аудитория", lesson.place)
            if (lesson.merged.isNotEmpty()) InfoLine("Совмещено", lesson.merged)
        }
        if (!guest && d == null && error == null) Loading()
        if (error != null) ErrorBox(error!!)
        if (d != null) {
            val teachers = d.arr("teachers").strings()
            val rooms = d.arr("rooms").strings()
            if (teachers.isNotEmpty()) InfoLine("Преподаватель", teachers.joinToString(", "))
            if (rooms.isNotEmpty()) InfoLine("Аудитория", rooms.joinToString(", "))
            val merged = d.arr("merged").strings()
            if (merged.isNotEmpty()) InfoLine("Совмещено", merged.joinToString(", "))

            val next = d.arr("next").objects()
            if (next.isNotEmpty()) {
                Label("Ближайшие пары", Modifier.padding(top = 14.dp, bottom = 4.dp))
                next.forEach { n ->
                    val target = dateFromDayMonth(n.s("date"))
                    // The line is plain text that works as a link: it opens the diary at that lesson.
                    Text(
                        "${n.s("day")}, ${n.s("date")} · ${n.s("time")}" + (if (n.s("place").isNotEmpty()) " · ${n.s("place")}" else ""),
                        color = p.text, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().then(if (target != null) Modifier.bounce { onJump(target) } else Modifier).padding(vertical = 5.dp),
                    )
                }
            }

            val notes = d.arr("notes").objects()
            Label("Задания и заметки", Modifier.padding(top = 14.dp, bottom = 4.dp))
            if (notes.isEmpty()) Text("Пока ничего нет.", color = p.muted, fontSize = 14.sp)
            notes.forEach { n ->
                val note = parseNote(n)
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(p.soft).padding(12.dp)) {
                    if (!note.own) Text(note.author, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (note.text.isNotEmpty()) Text(note.text, color = p.text, fontSize = 15.sp)
                    if (note.media.isNotEmpty()) {
                        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until maxOf(1, note.photos)).toList()) { index ->
                                RemoteImage(Api.BASE + note.media + "?index=$index", Modifier.size(110.dp).clip(RoundedCornerShape(12.dp)))
                            }
                        }
                    }
                    if (note.own) {
                        Text(
                            "Удалить", color = Danger, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp).bounce {
                                scope.launch {
                                    try {
                                        Api.delete("notes/${note.id}")
                                        reload++
                                    } catch (e: ApiException) {
                                        error = e.message
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val p = LocalPalette.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = p.muted, fontSize = 12.sp)
        Text(value, color = p.text, fontSize = 15.sp)
    }
}
