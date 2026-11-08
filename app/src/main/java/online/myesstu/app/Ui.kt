package online.myesstu.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Soft "press" feedback like iOS buttons. */
@Composable
fun Modifier.bounce(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val factor by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "bounce")
    return this.scale(factor).clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** Page with a large iOS-style title that scrolls away with the content. */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val p = LocalPalette.current
    LazyColumn(
        Modifier.fillMaxSize().background(p.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    if (!subtitle.isNullOrEmpty()) Text(subtitle, color = p.muted, fontSize = 13.sp, maxLines = 1)
                    Text(title, color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                if (trailing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
                }
            }
        }
        content()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surface).padding(padding),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text, color = p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null, filled: Boolean = false) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
            .background(if (filled) accent.copy(alpha = 0.14f) else p.soft)
            .bounce(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint ?: if (filled) accent else p.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun BackChip(label: String = "Назад", onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.bounce(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = accent, modifier = Modifier.size(18.dp))
        Text(label, color = accent, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent else p.soft)
            .bounce(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = if (selected) Color.White else p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Tag(text: String) {
    val p = LocalPalette.current
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(p.soft).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val accent = LocalAccent.current
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) accent else accent.copy(alpha = 0.45f))
            .bounce(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    Box(
        modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(p.soft).bounce(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (danger) Danger else accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null,
) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    var visible by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.soft)
            .padding(start = 14.dp, end = if (password) 6.dp else 14.dp, top = if (password) 5.dp else 13.dp, bottom = if (password) 5.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, null, tint = p.muted, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Box(Modifier.weight(1f).padding(vertical = if (password) 8.dp else 0.dp)) {
            if (value.isEmpty()) Text(placeholder, color = p.muted, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(accent),
                visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (password) PasswordToggle(visible) { visible = !visible }
    }
}

@Composable
fun PasswordToggle(visible: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).clip(CircleShape).bounce(onClick = onToggle), contentAlignment = Alignment.Center) {
        Icon(
            if (visible) EyeOffIcon else EyeIcon,
            if (visible) "Скрыть пароль" else "Показать пароль",
            tint = p.muted,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun outlineIcon(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    paths.forEach { path ->
        builder.addPath(
            PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

val EyeIcon: ImageVector by lazy {
    outlineIcon("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z", "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z")
}

val EyeOffIcon: ImageVector by lazy {
    outlineIcon(
        "eye-off",
        "M2 12s3.5-7 10-7c1.6 0 3 .4 4.2 1M22 12s-3.5 7-10 7c-1.6 0-3-.4-4.2-1",
        "M9.9 9.9a3 3 0 0 0 4.2 4.2",
        "M3 3l18 18",
    )
}

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "switch")
    Box(
        Modifier.width(51.dp).height(31.dp).clip(CircleShape)
            .background(if (checked) accent else p.line)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
            .padding(2.dp),
    ) {
        Box(Modifier.offset(x = x).size(27.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = p.text, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        IosSwitch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        contentColor = p.text,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            content = content,
        )
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Panel {
        Text(message, color = p.text, fontSize = 15.sp)
        if (onRetry != null) {
            Box(Modifier.padding(top = 12.dp)) { SecondaryButton("Повторить", onRetry) }
        }
    }
}

@Composable
fun EmptyBox(title: String, text: String) {
    val p = LocalPalette.current
    Panel {
        Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Palette icon in the same outline style as the system icons. */
val PaletteIcon: ImageVector by lazy {
    ImageVector.Builder("palette", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            PathParser().parsePathString(
                "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z",
            ).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            PathParser().parsePathString("M6.5 11.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M8.5 7.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M13.5 7.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M16 11.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(),
            fill = SolidColor(Color.Black),
        )
        .build()
}

/** Options with a thumb that slides between them; [fill] stretches it over the whole width. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, fill: Boolean = false, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    BoxWithConstraints(modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier)) {
        val cell = if (fill) (maxWidth - 6.dp) / options.size else 84.dp
        val offset by animateDpAsState(cell * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "segment")
        Box(Modifier.width(cell * options.size + 6.dp).clip(RoundedCornerShape(13.dp)).background(p.soft).padding(3.dp)) {
            Box(Modifier.offset(x = offset).width(cell).height(34.dp).clip(RoundedCornerShape(10.dp)).background(p.surface))
            Row {
                options.forEachIndexed { index, label ->
                    Box(Modifier.width(cell).height(34.dp).bounce { onSelect(index) }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (index == selected) accent else p.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
