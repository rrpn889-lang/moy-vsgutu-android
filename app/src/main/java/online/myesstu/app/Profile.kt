package online.myesstu.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ProfileTab(app: AppState) {
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(page != "main") { page = "main" }
    when (page) {
        "settings" -> SettingsPage(app) { page = "main" }
        "appearance" -> AppearancePage(app) { page = "main" }
        "grades" -> GradesPage(app) { page = "main" }
        else -> ProfileMain(app) { page = it }
    }
}

@Composable
private fun ProfileMain(app: AppState, open: (String) -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        try {
            app.refreshMe()
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }

    val me = app.me
    Screen(
        title = "Профиль",
        trailing = {
            IconBtn(PaletteIcon, "Оформление", { open("appearance") })
            IconBtn(Icons.Filled.Settings, "Настройки", { open("settings") })
        },
    ) {
        if (error != null && me == null) item { ErrorBox(error!!, { reload++ }) }
        if (me == null && error == null) item { Loading() }
        if (me != null) {
            item { ProfileHeader(me) }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(accent).bounce { open("grades") }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Таблица оценок", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Весь семестр по предметам", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
                }
            }
            item { FriendsCard() }
            item { StudyCard(app, me) }
            item { RemindersCard(app, me) }
        }
    }
}

@Composable
private fun ProfileHeader(me: JSONObject) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val name = me.s("display_name").ifEmpty { me.s("email") }.ifEmpty { "Студент" }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, Api.BASE + "profile/avatar", Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(name, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val email = me.s("email")
                if (email.isNotEmpty()) Text(email, color = p.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val group = me.s("group_title")
                    if (group.isNotEmpty()) Tag(group)
                    val sub = me.i("subgroup")
                    if (sub > 0) Tag("Подгруппа $sub")
                }
            }
        }
    }
}

@Composable
private fun StudyCard(app: AppState, me: JSONObject) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var title by remember(me.s("group_title")) { mutableStateOf(me.s("group_title")) }
    var subgroup by remember(me.i("subgroup")) { mutableIntStateOf(me.i("subgroup")) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Panel {
        Text("Учебные данные", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(top = 12.dp)) { Field(title, { title = it.take(32) }, "Название группы") }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Все", subgroup == 0) { subgroup = 0 }
            Chip("1 подгруппа", subgroup == 1) { subgroup = 1 }
            Chip("2 подгруппа", subgroup == 2) { subgroup = 2 }
        }
        if (message != null) Text(message!!, color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.padding(top = 12.dp)) {
            PrimaryButton("Сохранить", enabled = title.trim().length >= 2, loading = busy, onClick = {
                scope.launch {
                    busy = true
                    message = null
                    try {
                        Api.post("profile/group", JSONObject().put("title", title.trim()).put("subgroup", subgroup))
                        app.scheduleCache.clear()
                        app.refreshMe()
                        message = "Сохранено"
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

private val reminderRows = listOf(
    Triple("remind_before", "За 5 минут до пары", "Напоминание в Telegram"),
    Triple("remind_after", "После пары", "Сообщение о том, что пара закончилась"),
    Triple("evening", "Вечером на завтра", "Расписание на следующий день"),
    Triple("notes_enabled", "Заметки перед парой", "Присылать задания перед занятием"),
)

@Composable
private fun RemindersCard(app: AppState, me: JSONObject) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val prefs = me.obj("preferences")
    var error by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("Напоминания", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        reminderRows.forEach { (field, title, subtitle) ->
            SwitchRow(title, subtitle, prefs.flag(field)) { value ->
                scope.launch {
                    try {
                        val data = Api.post("profile/settings", JSONObject().put("field", field).put("value", value)) as? JSONObject
                        if (data != null) app.me = data
                        error = null
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            }
        }
        if (error != null) Text(error!!, color = Danger, fontSize = 13.sp)
        Text("Уведомления сейчас приходят через Telegram-бота.", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FriendsCard() {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var accepting by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        try {
            friends = (Api.get("friends") as? JSONArray)?.objects() ?: emptyList()
        } catch (e: ApiException) {
            error = e.message
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Друзья", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(accent.copy(alpha = 0.14f)).bounce {
                    scope.launch {
                        try {
                            val data = Api.post("friends/invite") as? JSONObject
                            val url = data?.s("url").orEmpty()
                            if (url.isNotEmpty()) {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, "Добавь меня в друзья в «Мой ВСГУТУ»: $url")
                                context.startActivity(Intent.createChooser(send, "Пригласить друга"))
                            }
                        } catch (e: ApiException) {
                            error = e.message
                        }
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("Пригласить", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (error != null) Text(error!!, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        val list = friends
        if (list == null && error == null) Loading()
        if (list != null && list.isEmpty()) {
            Text("Друзья видят твои заметки к парам, а ты — их.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        list?.forEach { friend ->
            Row(
                Modifier.fillMaxWidth().bounce {
                    scope.launch {
                        try {
                            info = Api.get("friends/${friend.s("id")}") as? JSONObject
                        } catch (e: ApiException) {
                            error = e.message
                        }
                    }
                }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(friend.s("name"), color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.muted)
            }
        }
        Box(Modifier.padding(top = 6.dp)) { SecondaryButton("У меня есть приглашение", { accepting = true }) }
    }

    info?.let { f ->
        Sheet(onDismiss = { info = null }) {
            Text(f.s("name"), color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Группа: " + f.s("group_title").ifEmpty { "не выбрана" }, color = p.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Подгруппа: " + (if (f.i("subgroup") > 0) f.i("subgroup").toString() else "все"), color = p.muted, fontSize = 15.sp)
            Text("Заметок: ${f.i("notes_count")}", color = p.muted, fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton("Удалить", {
                        scope.launch {
                            try {
                                Api.delete("friends/${f.s("id")}")
                                info = null
                                reload++
                            } catch (e: ApiException) {
                                error = e.message
                                info = null
                            }
                        }
                    }, danger = true)
                }
            }
        }
    }

    if (accepting) AcceptInviteSheet { done ->
        accepting = false
        if (done) reload++
    }
}

@Composable
private fun AcceptInviteSheet(onClose: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Sheet(onDismiss = { onClose(false) }) {
        Text("Принять приглашение", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Вставь ссылку, которую прислал друг.", color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Field(link, { link = it }, "Ссылка-приглашение")
        if (error != null) Text(error!!, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.padding(top = 14.dp)) {
            PrimaryButton("Принять", enabled = link.isNotBlank(), loading = busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val token = Uri.parse(link.trim()).getQueryParameter("invite") ?: link.trim()
                        Api.post("friends/accept", JSONObject().put("token", token))
                        onClose(true)
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            })
        }
    }
}

@Composable
private fun SettingsPage(app: AppState, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = app.me
    var message by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var wanted by remember { mutableStateOf(AppSettings.notificationsWanted(context)) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) wanted = false.also { AppSettings.setNotificationsWanted(context, false) }
        PairAlert.refresh(context)
        scope.launch { Device.report(context) }
    }

    Screen(title = "Настройки", subtitle = "Аккаунт и приложение") {
        item { BackChip("Профиль", onBack) }
        val telegram = me?.obj("telegram")
        val portal = me?.obj("portal")
        item {
            Panel {
                Text("Telegram", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val connected = telegram?.flag("connected") == true
                Text(
                    if (connected) "Подключён" + (telegram?.s("username")?.takeIf { it.isNotEmpty() }?.let { " · @$it" } ?: "")
                    else "Не подключён. Подключи, чтобы получать напоминания.",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                if (connected) {
                    SecondaryButton("Отключить", {
                        scope.launch {
                            try {
                                Api.post("telegram/disconnect")
                                app.refreshMe()
                            } catch (e: ApiException) {
                                message = e.message
                            }
                        }
                    }, danger = true)
                } else {
                    PrimaryButton("Подключить Telegram", onClick = {
                        scope.launch {
                            try {
                                val data = Api.post("telegram/link") as? JSONObject
                                val url = listOf("url", "link", "deep_link").map { data?.s(it).orEmpty() }.firstOrNull { it.isNotEmpty() }
                                if (url != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                else message = "Не удалось получить ссылку для Telegram"
                            } catch (e: ApiException) {
                                message = e.message
                            }
                        }
                    })
                }
            }
        }
        item {
            Panel {
                Text("Кабинет ВСГУТУ", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (portal?.flag("connected") == true) "Подключён · оценки обновляются автоматически" else "Не подключён",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Уведомления", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (!Notifications.permitted(context) && wanted) "Android не разрешает показывать уведомления. Нажми переключатель, чтобы разрешить."
                            else "Новые оценки и таймер до конца пары. Пока включены, Telegram их не дублирует.",
                            color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                        )
                    }
                    IosSwitch(wanted && Notifications.permitted(context)) { on ->
                        AppSettings.setNotificationsWanted(context, on)
                        wanted = on
                        if (on && !Notifications.permitted(context)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            if (!on) PairAlert.pause(context) else PairAlert.refresh(context)
                            scope.launch { Device.report(context) }
                        }
                    }
                }
                if (wanted && Notifications.permitted(context)) {
                    listOf(
                        Triple("grades", "Новые оценки", "Сразу, как преподаватель поставил"),
                        Triple("schedule", "Изменения в расписании", "Когда в расписании группы что-то поменялось"),
                        Triple("pair", "Таймер до конца пары", "Остаётся в шторке, пока идёт пара"),
                    ).forEach { (kind, title, hint) ->
                        var on by remember { mutableStateOf(AppSettings.wants(context, kind)) }
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(hint, color = p.muted, fontSize = 12.sp)
                            }
                            IosSwitch(on) { value ->
                                on = value
                                AppSettings.setWants(context, kind, value)
                                if (kind == "pair") if (value) PairAlert.refresh(context) else PairAlert.pause(context)
                            }
                        }
                    }
                }
            }
        }
        item {
            Panel {
                Text("Приложение", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Версия ${AppSettings.versionName(context)}",
                    color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                SecondaryButton("Проверить обновления", {
                    checkingUpdate = true
                })
            }
        }
        if (message != null) item { Text(message!!, color = Danger, fontSize = 14.sp) }
        item { SecondaryButton("Выйти из аккаунта", { confirmLogout = true }, danger = true) }
    }

    if (checkingUpdate) UpdateSheet(manual = true, onClose = { checkingUpdate = false })

    if (confirmLogout) {
        Sheet(onDismiss = { confirmLogout = false }) {
            Text("Выйти из аккаунта?", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Чтобы вернуться, нужно снова ввести логин и пароль.", color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Отмена", { confirmLogout = false }) }
                Box(Modifier.weight(1f)) {
                    SecondaryButton("Выйти", {
                        scope.launch {
                            runCatching { Api.logout() }
                            runCatching { Vault.clear(context) }
                            PairAlert.clear(context)
                            Portal.forget()
                            app.logout()
                        }
                    }, danger = true)
                }
            }
        }
    }
}

@Composable
fun AppearancePage(app: AppState, onBack: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()

    fun save(field: String, value: String) {
        if (app.guest) return
        scope.launch {
            runCatching { Api.post("profile/settings", JSONObject().put("field", field).put("value", value)) }
        }
    }

    Screen(title = "Оформление", subtitle = "Тема и цвета") {
        item { BackChip("Профиль", onBack) }
        item {
            Panel {
                Text("Тема приложения", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppearanceNames.forEach { (id, label, description) ->
                        val palette = Palettes.getValue(id)
                        val selected = app.appearance == id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (selected) LocalAccent.current.copy(alpha = 0.12f) else p.soft)
                                .bounce { app.setLook(newAppearance = id); save("appearance", id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(palette.bg)) {
                                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).fillMaxWidth(0.6f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(palette.surface))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(label, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(description, color = p.muted, fontSize = 12.sp)
                            }
                            if (selected) Text("✓", color = LocalAccent.current, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            Panel {
                Text("Цвет акцента", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Accents.forEach { (id, color) ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(color)
                                .bounce { app.setLook(newAccent = id); save("accent", id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (app.accent == id) Text("✓", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(AccentNames[app.accent].orEmpty(), color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        item { IconPicker() }
    }
}

/** Launcher icon in one of the accent colours, chosen from small previews. */
@Composable
private fun IconPicker() {
    val p = LocalPalette.current
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcon.current(context)) }
    Panel {
        Text("Иконка приложения", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Меняется на экране телефона, лаунчер может обновить её не сразу.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Accents.forEach { (id, color) ->
                val selected = current == id
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(color)
                        .then(if (selected) Modifier.border(3.dp, p.text, RoundedCornerShape(15.dp)) else Modifier)
                        .bounce { current = id; AppIcon.set(context, id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(24.dp, 21.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                }
            }
        }
    }
}
