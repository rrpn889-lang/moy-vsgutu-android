package online.myesstu.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppCache.init(applicationContext)
        val app = AppState(getSharedPreferences("app", MODE_PRIVATE))
        app.gradeModules = GradeStore.load()
        // The profile of the last run is shown at once; the server's answer replaces it when it comes.
        if (app.token.isNotEmpty()) app.me = AppCache.get("me")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        Api.token = app.token
        Api.onUnauthorized = { Handler(Looper.getMainLooper()).post { app.logout() } }
        Api.refresh = {
            val saved = runCatching { Vault.load(applicationContext) }.getOrNull()
            if (saved == null) false else try {
                app.signedIn(Api.login(saved.login, saved.password))
                true
            } catch (e: ApiException) {
                false
            }
        }
        val loader = ImageLoader.Builder(this).okHttpClient(Api.client).crossfade(true).build()
        setContent {
            CompositionLocalProvider(LocalLoader provides loader) { Root(app) }
        }
    }
}

@Composable
private fun SystemBars(dark: Boolean) {
    val activity = LocalContext.current as ComponentActivity
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

@Composable
private fun Root(app: AppState) {
    AppTheme(app.appearance, app.accent) {
        val p = LocalPalette.current
        SystemBars(p.dark)
        Box(Modifier.fillMaxSize().background(p.bg)) {
            var choosingGroup by remember { mutableStateOf(false) }
            when {
                app.token.isNotEmpty() || app.guest -> MainTabs(app)
                choosingGroup -> GuestSetup(app) { choosingGroup = false }
                else -> LoginScreen(app) { choosingGroup = true }
            }
        }
    }
}

private enum class Page { Diary, Groups, Search, Profile }

private data class TabItem(val title: String, val icon: ImageVector, val page: Page)

private val accountTabs = listOf(
    TabItem("Дневник", Icons.Filled.DateRange, Page.Diary),
    TabItem("Группы", Icons.AutoMirrored.Filled.List, Page.Groups),
    TabItem("Поиск", Icons.Filled.Search, Page.Search),
    TabItem("Профиль", Icons.Filled.Person, Page.Profile),
)

/** A guest has no account, so only the schedule and the profile make sense. */
private val guestTabs = listOf(accountTabs[0], accountTabs[3])

@Composable
private fun MainTabs(app: AppState) {
    val p = LocalPalette.current
    val accent = LocalAccent.current
    val tabs = if (app.guest) guestTabs else accountTabs
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tab = selected.coerceIn(0, tabs.lastIndex)

    val context = LocalContext.current
    LaunchedEffect(app.token) {
        if (app.token.isNotEmpty()) runCatching { app.refreshMe() }
    }
    // New grades are checked for in the background, so they can be announced even when the app is closed.
    LaunchedEffect(app.token, app.guest) {
        if (!app.guest && app.token.isNotEmpty()) {
            Notifications.ensureChannel(context)
            GradeWatcher.schedule(context)
            Device.report(context)
            AnnounceWatcher.schedule(context)
        }
        PairAlert.refresh(context)
        ScheduleWatcher.schedule(context)
    }
    UpdatePrompt()
    if (!app.guest && app.token.isNotEmpty()) AnnouncePrompt()

    Box(Modifier.fillMaxSize()) {
        when (tabs[tab].page) {
            Page.Diary -> DiaryScreen(app)
            Page.Groups -> GroupsScreen(app)
            Page.Search -> SearchScreen()
            Page.Profile -> if (app.guest) GuestProfile(app) else ProfileTab(app)
        }
        val shape = RoundedCornerShape(28.dp)
        BoxWithConstraints(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
                .clip(shape).background(p.surface).border(0.5.dp, p.line, shape).padding(6.dp),
        ) {
            val cell = maxWidth / tabs.size
            val offset by animateDpAsState(cell * tab, spring(dampingRatio = 0.8f, stiffness = 500f), label = "tab")
            Box(Modifier.offset(x = offset).width(cell).height(54.dp).clip(RoundedCornerShape(22.dp)).background(accent.copy(alpha = 0.14f)))
            Row {
                tabs.forEachIndexed { index, item ->
                    val active = index == tab
                    Column(
                        Modifier.width(cell).height(54.dp).bounce { selected = index },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.icon, item.title, tint = if (active) accent else p.muted, modifier = Modifier.size(22.dp))
                        Text(item.title, color = if (active) accent else p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (!app.guest) NotificationAsk(app)
    }
}
