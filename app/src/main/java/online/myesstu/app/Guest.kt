package online.myesstu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Searches the list of groups published by the university and reports the chosen one. */
@Composable
private fun GroupSearch(onPick: (Group) -> Unit, extra: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    var all by remember { mutableStateOf<List<Group>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        try {
            all = University.groups()
        } catch (e: ApiException) {
            error = e.message
        }
    }

    Field(query, { query = it }, "Название группы, например К86БД")
    extra()
    if (error != null) ErrorBox(error!!, { reload++ })
    if (all == null && error == null) Loading()
    val wanted = query.uppercase().replace('Ё', 'Е').replace(Regex("[^A-ZА-Я0-9]"), "")
    val shown = all.orEmpty().filter { wanted.isEmpty() || it.title.uppercase().replace('Ё', 'Е').replace(Regex("[^A-ZА-Я0-9]"), "").contains(wanted) }.take(40)
    if (all != null && shown.isEmpty()) EmptyBox("Группа не найдена", "Проверь название: буквы и цифры как в расписании.")
    shown.forEach { group ->
        Panel(Modifier.bounce { onPick(group) }, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.muted)
            }
        }
    }
}

/** First screen of guest mode: pick a group, then a subgroup. */
@Composable
fun GuestSetup(app: AppState, onBack: (() -> Unit)?) {
    val p = LocalPalette.current
    var group by remember { mutableStateOf<Group?>(null) }
    var subgroup by remember { mutableIntStateOf(app.guestGroup?.subgroup ?: 0) }
    if (onBack != null) BackHandler(onBack = onBack)

    Screen(title = if (app.guest) "Группа" else "Гостевой вход", subtitle = "Без пароля и аккаунта") {
        if (onBack != null) item { BackChip(if (app.guest) "Профиль" else "Вход", onBack) }
        val chosen = group
        if (chosen == null) {
            item { Text("Выбери свою группу. Расписание приложение возьмёт прямо с сайта университета.", color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupSearch(onPick = { group = it })
                }
            }
        } else {
            item {
                Panel {
                    Text(chosen.title, color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Какая у тебя подгруппа?", color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Все", subgroup == 0) { subgroup = 0 }
                        Chip("1 подгруппа", subgroup == 1) { subgroup = 1 }
                        Chip("2 подгруппа", subgroup == 2) { subgroup = 2 }
                    }
                    Box(Modifier.padding(top = 16.dp)) {
                        PrimaryButton("Готово", onClick = {
                            val value = GuestGroup(chosen.title, chosen.url, subgroup)
                            if (app.guest) app.chooseGuestGroup(value) else app.enterGuest(value)
                            onBack?.invoke()
                        })
                    }
                    Box(Modifier.padding(top = 10.dp)) { SecondaryButton("Выбрать другую группу", { group = null }) }
                }
            }
        }
    }
}

/** Profile tab for a guest: the chosen group, the look of the app and a way to sign in. */
@Composable
fun GuestProfile(app: AppState) {
    val p = LocalPalette.current
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(page != "main") { page = "main" }
    when (page) {
        "group" -> GuestSetup(app) { page = "main" }
        "look" -> AppearancePage(app) { page = "main" }
        else -> Screen(
            title = "Профиль",
            subtitle = "Гостевой режим",
            trailing = { IconBtn(PaletteIcon, "Оформление", { page = "look" }) },
        ) {
            val group = app.guestGroup
            item {
                Panel {
                    Text("Моя группа", color = p.muted, fontSize = 13.sp)
                    Text(group?.title ?: "Не выбрана", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (group != null) {
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Все", group.subgroup == 0) { app.chooseGuestGroup(GuestGroup(group.title, group.url, 0)) }
                            Chip("1 подгруппа", group.subgroup == 1) { app.chooseGuestGroup(GuestGroup(group.title, group.url, 1)) }
                            Chip("2 подгруппа", group.subgroup == 2) { app.chooseGuestGroup(GuestGroup(group.title, group.url, 2)) }
                        }
                    }
                    Box(Modifier.padding(top = 14.dp)) { SecondaryButton("Сменить группу", { page = "group" }) }
                }
            }
            item {
                Panel {
                    Text("Что доступно без входа", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Расписание, текущая пара и смена недели. Оценки, заметки с фото, друзья и поиск преподавателей появятся после входа в аккаунт.",
                        color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                    PrimaryButton("Войти в аккаунт", onClick = { app.leaveGuest() })
                }
            }
        }
    }
}
