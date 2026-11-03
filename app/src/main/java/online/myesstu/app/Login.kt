package online.myesstu.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private class Tone(val ink: Color, val subtle: Color, val bg: Color, val line: Color)

/** The sign-in screen has its own cool ink-and-paper look; the rest of the app follows the website. */
@Composable
private fun tone(): Tone {
    val palette = LocalPalette.current
    return if (palette.dark) Tone(palette.text, palette.muted, palette.bg, palette.line)
    else Tone(Color(0xFF0E1B2E), Color(0xFF6B7788), Color(0xFFEFF2F7), Color(0xFFD9DFE9))
}

/** The page assembles itself once, top to bottom: each block fades in and rises a little. */
@Composable
private fun Reveal(visible: Boolean, order: Int, content: @Composable () -> Unit) {
    val delay = order * 90
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(450, delayMillis = delay)) +
            slideInVertically(tween(550, delayMillis = delay, easing = FastOutSlowInEasing)) { it / 8 },
    ) { content() }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val t = tone()
    val accent = LocalAccent.current
    var visible by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val underline by animateFloatAsState(if (focused) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "underline")

    Box(Modifier.fillMaxWidth().height(54.dp)) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = if (password) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (focused) accent else t.ink, fontSize = 16.sp, modifier = Modifier.width(80.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(hint, color = t.subtle, fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    interactionSource = source,
                    textStyle = TextStyle(color = t.ink, fontSize = 16.sp, fontFamily = AppFont),
                    cursorBrush = SolidColor(accent),
                    visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (password) PasswordToggle(visible) { visible = !visible }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(underline).height(2.dp).background(accent))
    }
}

@Composable
private fun LoginContent(app: AppState, onGuest: () -> Unit) {
    val t = tone()
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(false) }
    var failures by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current
    val context = LocalContext.current

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(failures) {
        if (failures > 0) {
            shake.animateTo(0f, keyframes {
                durationMillis = 380
                -10f at 60; 9f at 140; -6f at 220; 4f at 300; 0f at 380
            })
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Reveal(shown, 0) { Text("Мой ВСГУТУ", color = t.ink, fontSize = 38.sp, fontWeight = FontWeight.Bold) }
            Reveal(shown, 1) {
                Text(
                    "Расписание, оценки и заметки в одном месте.",
                    color = t.subtle, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 32.dp),
                )
            }
            Reveal(shown, 2) {
                Column(
                    Modifier.fillMaxWidth().offset { IntOffset(with(density) { shake.value.dp.roundToPx() }, 0) }
                        .clip(RoundedCornerShape(16.dp)).background(LocalPalette.current.surface),
                ) {
                    FieldRow("Логин", login, { login = it }, "почта или логин", keyboard = KeyboardType.Email)
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(t.line))
                    FieldRow("Пароль", password, { password = it }, "пароль от кабинета", password = true)
                }
            }
            if (error != null) Text(error!!, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            Reveal(shown, 3) {
                Box(Modifier.padding(top = 16.dp)) {
                    PrimaryButton(
                        "Войти",
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    val token = Api.login(login.trim(), password)
                                    runCatching { Vault.save(context, login.trim(), password) }
                                    app.signedIn(token)
                                } catch (e: ApiException) {
                                    error = e.message
                                    failures++
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = login.isNotBlank() && password.isNotEmpty(),
                        loading = busy,
                    )
                }
            }
            Reveal(shown, 4) {
                Text(
                    buildAnnotatedString {
                        append("Данные те же, что в кабинете ")
                        withLink(
                            LinkAnnotation.Url(
                                "https://spo.esstu.ru",
                                TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                            ),
                        ) { append("ВСГУТУ") }
                        append(". Пароль хранится в защищённой памяти телефона.")
                    },
                    color = t.subtle, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
                )
            }
            Reveal(shown, 5) {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Продолжить без входа",
                        color = accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.bounce(onClick = onGuest).padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(app: AppState, onGuest: () -> Unit) {
    ProvideTextStyle(TextStyle(fontFamily = AppFont)) { LoginContent(app, onGuest) }
}
