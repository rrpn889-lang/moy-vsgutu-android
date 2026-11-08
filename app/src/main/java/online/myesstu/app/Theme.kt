package online.myesstu.app

import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

class Palette(
    val bg: Color, val surface: Color, val soft: Color, val text: Color,
    val muted: Color, val line: Color, val dark: Boolean,
)

// Same values as the website's four appearances (style.css), so the app and the site look alike.
val Palettes = mapOf(
    "light" to Palette(Color(0xFFF2F2F7), Color(0xFFFFFFFF), Color(0xFFF3F3F8), Color(0xFF19191D), Color(0xFF6C6C76), Color(0x1A323241), false),
    "black" to Palette(Color(0xFF000000), Color(0xFF141414), Color(0xFF222222), Color(0xFFFAFAFA), Color(0xFFA1A1A8), Color(0xFF2A2A2D), true),
    "graphite" to Palette(Color(0xFF1B1C20), Color(0xFF27282D), Color(0xFF33343B), Color(0xFFF4F4F7), Color(0xFFB1B2BD), Color(0x1FF0F0FF), true),
    "paper" to Palette(Color(0xFFF3EEE5), Color(0xFFFFFAF2), Color(0xFFECE5DA), Color(0xFF322B25), Color(0xFF786D60), Color(0x21533F27), false),
)

val Accents = mapOf(
    "blue" to Color(0xFF087CF0),
    "violet" to Color(0xFF7758E8),
    "mint" to Color(0xFF188E78),
    "pink" to Color(0xFFC3317E),
    "burgundy" to Color(0xFFA52D53),
    "orange" to Color(0xFFD9641F),
)

val Danger = Color(0xFFE5484D)

val AccentNames = mapOf(
    "blue" to "Синий", "violet" to "Фиолетовый", "mint" to "Мятный",
    "pink" to "Розовый", "burgundy" to "Бордовый", "orange" to "Оранжевый",
)

val AppearanceNames = listOf(
    Triple("black", "Чёрная", "Чёрный фон"),
    Triple("light", "Светлая", "Светлые поверхности"),
    Triple("graphite", "Графит", "Тёмные оттенки"),
    Triple("paper", "Бумага", "Тёплый фон"),
)

val LocalPalette = compositionLocalOf { Palettes.getValue("light") }
val LocalAccent = compositionLocalOf { Accents.getValue("blue") }

@Composable
fun AppTheme(appearance: String, accent: String, content: @Composable () -> Unit) {
    // Every colour glides to its new value, so changing the theme or the accent is a smooth fade, not a jump.
    val target = Palettes[appearance] ?: Palettes.getValue("light")
    val spec = tween<Color>(450)
    val palette = Palette(
        bg = animateColorAsState(target.bg, spec, label = "bg").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        soft = animateColorAsState(target.soft, spec, label = "soft").value,
        text = animateColorAsState(target.text, spec, label = "text").value,
        muted = animateColorAsState(target.muted, spec, label = "muted").value,
        line = animateColorAsState(target.line, spec, label = "line").value,
        dark = target.dark,
    )
    val accentColor by animateColorAsState(Accents[accent] ?: Accents.getValue("blue"), spec, label = "accent")
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides accentColor,
        content = content,
    )
}

class GuestGroup(val title: String, val url: String, val subgroup: Int)

/** Everything the screens share: session token, look and the cached profile. */
class AppState(private val prefs: SharedPreferences) {
    var token by mutableStateOf(prefs.getString("token", "") ?: "")
    var appearance by mutableStateOf(prefs.getString("appearance", "light") ?: "light")
    var accent by mutableStateOf(prefs.getString("accent", "blue") ?: "blue")
    var me by mutableStateOf<JSONObject?>(null)
    val scheduleCache = HashMap<String, Schedule>()

    /** Group settings read from our server once per session; the schedule itself comes from the university site. */
    var config: JSONObject? = prefs.getString("config", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(value) {
            field = value
            prefs.edit().apply { if (value == null) remove("config") else putString("config", value.toString()) }.apply()
        }

    /** True once the settings were read from the server in this run; until then the saved copy is used. */
    var configFresh = false

    @Volatile
    var configRefreshing = false

    /** Every grade read from the cabinet, shared by the diary and the grades page. */
    var gradeModules by mutableStateOf<List<GradeModule>>(emptyList())

    /** Guest mode: no account at all, only a group and a subgroup kept on this phone. */
    var guest by mutableStateOf(prefs.getBoolean("guest", false))

    /** The notification question is asked once; the answer is remembered. */
    var asked by mutableStateOf(prefs.getBoolean("asked_notifications", false))

    fun notificationsAsked() {
        asked = true
        prefs.edit().putBoolean("asked_notifications", true).apply()
    }
    var guestGroup by mutableStateOf(
        prefs.getString("guest_url", null)?.let { GuestGroup(prefs.getString("guest_title", "").orEmpty(), it, prefs.getInt("guest_subgroup", 0)) },
    )

    fun enterGuest(group: GuestGroup) {
        guest = true
        chooseGuestGroup(group)
        prefs.edit().putBoolean("guest", true).apply()
    }

    fun chooseGuestGroup(group: GuestGroup) {
        guestGroup = group
        scheduleCache.clear()
        prefs.edit().putString("guest_title", group.title).putString("guest_url", group.url).putInt("guest_subgroup", group.subgroup).apply()
    }

    fun leaveGuest() {
        guest = false
        guestGroup = null
        scheduleCache.clear()
        prefs.edit().putBoolean("guest", false).remove("guest_title").remove("guest_url").remove("guest_subgroup").apply()
    }

    fun signedIn(newToken: String) {
        token = newToken
        Api.token = newToken
        prefs.edit().putString("token", newToken).apply()
    }

    fun logout() {
        token = ""
        Api.token = ""
        me = null
        config = null
        configFresh = false
        gradeModules = emptyList()
        scheduleCache.clear()
        AppCache.clear()
        prefs.edit().remove("token").apply()
    }

    fun setLook(newAppearance: String = appearance, newAccent: String = accent) {
        appearance = newAppearance
        accent = newAccent
        prefs.edit().putString("appearance", newAppearance).putString("accent", newAccent).apply()
    }

    suspend fun refreshMe() {
        val data = Api.get("profile") as? JSONObject ?: return
        me = data
        AppCache.put("me", data.toString())
        val serverAppearance = data.s("appearance")
        val serverAccent = data.s("accent")
        if (serverAppearance in Palettes || serverAccent in Accents) {
            setLook(
                if (serverAppearance in Palettes) serverAppearance else appearance,
                if (serverAccent in Accents) serverAccent else accent,
            )
        }
    }
}

/** Grades keep their own colours whatever accent is chosen: 2 red, 3 orange, 4 blue, 5 green. */
fun markColor(value: String, dark: Boolean): Color? = when (value.trim()) {
    "2" -> if (dark) Color(0xFFFF7B80) else Color(0xFFD93A3F)
    "3" -> if (dark) Color(0xFFFFA95C) else Color(0xFFD56F18)
    "4" -> if (dark) Color(0xFF6AAEFF) else Color(0xFF1A70EB)
    "5" -> if (dark) Color(0xFF5FDC96) else Color(0xFF1C9A55)
    else -> null
}

/** Shared lessons: the website's gold bar and its darker amber text colour. */
val MergeBar = Color(0xFFD4A843)
val MergeText = Color(0xFFA26A00)
