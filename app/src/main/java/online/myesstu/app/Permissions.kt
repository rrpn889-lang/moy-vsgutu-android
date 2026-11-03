package online.myesstu.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Explains why notifications are useful and asks for the permission once, right after sign-in. */
@Composable
fun NotificationAsk(app: AppState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.notificationsAsked()
        show = false
        if (granted) {
            Notifications.ensureChannel(context)
            GradeWatcher.schedule(context)
        }
    }

    LaunchedEffect(app.token) {
        if (app.token.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.permitted(context) && !app.asked) {
            delay(1200)
            show = true
        }
    }

    if (show) {
        Sheet(onDismiss = { app.notificationsAsked(); show = false }) {
            Text("Сообщать о новых оценках?", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Приложение само заглядывает в кабинет и пришлёт уведомление, когда преподаватель поставит оценку или отметку.",
                color = p.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            PrimaryButton("Включить уведомления", onClick = { request.launch(Manifest.permission.POST_NOTIFICATIONS) })
            Box(Modifier.padding(top = 10.dp)) { SecondaryButton("Не сейчас", { app.notificationsAsked(); show = false }) }
        }
    }
}
