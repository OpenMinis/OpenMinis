package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.data.repository.AppIconRepository
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.SharedPreferences
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

// -- Preference Keys --
const val PREF_APPEARANCE = "appearance_prefs"
const val KEY_THEME_MODE = "theme_mode"            // 0=System, 1=Light, 2=Dark
const val KEY_LAUNCH_SESSION = "launch_session"    // 0=Auto, 1=LastSession, 2=NewChat, 3=Home
// iOS-aligned key names — match `@AppStorage("returnKeyBehavior")` and
// `@AppStorage("keepScreenAwakeDuringTasks")` in ContentView.swift so
// future cross-platform sync (if it ever lands) reads the same values.
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"  // Int 0=Newline (default), 1=Send
const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeDuringTasks"  // Boolean, default false
const val KEY_TOOL_PREVIEW = "tool_preview"        // Boolean, default true
// [T-keyboard-auto-pop default flip] Default ON — most users want the
// composer ready for a follow-up immediately after the model finishes.
// Key name mirrors iOS `@AppStorage("chat.autoFocusAfterReply")` so a
// future cross-platform sync reads the same pref.
const val KEY_AUTO_FOCUS_AFTER_REPLY = "chat.autoFocusAfterReply"  // Boolean, default true
// T-chat-title-pill: shows a sticky session-title pill above the chat list
// while the user scrolls back through history. Cross-platform key name
// (matches iOS @AppStorage("appearance.show_chat_title")) so future config
// sync reads the same value.
const val KEY_SHOW_CHAT_TITLE = "appearance.show_chat_title"  // Boolean, default true
// [T-thinking-auto-expand-toggle] When true (default, historical behavior) a
// NEW streaming thinking block auto-expands while the model reasons; when false
// it stays collapsed until tapped. Key name mirrors iOS
// `@AppStorage("chat.autoExpandThinking")` so future config sync reads the same
// value. Read at block-mount time in ThinkingBlock.
const val KEY_AUTO_EXPAND_THINKING = "chat.autoExpandThinking"  // Boolean, default true
// [T-android-auto-grouping] When a chat's title is first generated, also file
// it into a matching EXISTING group. Rides the title-generation call — no
// second round-trip. Key name matches iOS `autoGroupingEnabled` so a future
// config sync reads the same value.
//
// Default ON (both platforms, per product decision 2026-08-16). iOS originally
// shipped this opt-in on the reasoning that it "moves user data without being
// asked"; that concern is bounded here because the feature only ever files a
// chat into a group the user already created, only when the model is confident,
// only once per chat, and never over a hand-filed session (setFolderIfUnfiled).
const val KEY_AUTO_GROUPING = "autoGroupingEnabled"  // Boolean, default true
const val KEY_FONT_CHAT_INPUT = "font_chat_input"  // Int scale level -2..3
const val KEY_FONT_MESSAGE = "font_message"        // Int scale level -2..3
const val KEY_FONT_APP_BASE = "font_app_base"      // Int scale level -2..3
// "" = system, else a BCP-47 tag: "en", "zh", "zh-Hant", "ja", "ko", "fr",
// "de", "ru", "es". Region-qualified tags are stored in BCP-47 form so both
// LocaleList.forLanguageTags (API 33+) and LocaleWrap.parseLocale (pre-33)
// resolve them; every value here must also appear in res/xml/locales_config.xml
// or Android will not offer the language in system per-app settings.
const val KEY_LANGUAGE = "app_language"

/** True when Enter (without Shift) should send the message. iOS calls this
 *  `returnKeyBehavior == 1`. Default 0 = Enter inserts a newline (matches
 *  iOS shipping default + most desktop chat clients). */
fun returnKeySendsMessage(context: Context): Boolean =
    getAppearancePrefs(context).getInt(KEY_RETURN_KEY_BEHAVIOR, 0) == 1

fun keepScreenAwakeEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

/** [T-android-auto-grouping] Default ON — see [KEY_AUTO_GROUPING]. */
fun autoGroupingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_GROUPING, true)

/** Default ON — pill shows up on scroll for everyone unless explicitly disabled. */
fun showChatTitleEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_CHAT_TITLE, true)

/** [T-thinking-auto-expand-toggle] Default ON = historical behavior: a new
 *  streaming thinking block opens expanded. Off = it stays collapsed until the
 *  user taps it. */
fun autoExpandThinkingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_EXPAND_THINKING, true)

/** Font scale levels matching iOS: XS(-2) Small(-1) Default(0) Medium(1) Large(2) XL(3) */
/**
 * [T-android-app-icon-tile-max-width] Upper bound for one app-icon preview tile.
 *
 * Matches the 192px the preview bitmap is rasterized at — a launcher icon's
 * native size, and there is no higher-resolution source to draw from — so the
 * artwork is never asked to scale far past its own resolution. Without a cap the
 * weight(1f) tiles reached ~380dp on a tablet and the icons visibly blurred.
 */
private val APP_ICON_TILE_MAX_WIDTH = 192.dp

private val fontScaleLabels = listOf("XS", "Small", "Default", "Medium", "Large", "XL")
private val fontScaleValues = listOf(-2, -1, 0, 1, 2, 3)
private val fontScaleMultipliers = listOf(0.88f, 0.94f, 1.0f, 1.06f, 1.12f, 1.21f)

private data class LanguageOption(val code: String, val flag: String, val label: String)
// "System" label is resolved at call-site via stringResource so it follows
// the user's chosen UI language. The remaining entries are language self-names
// and stay as literals \u2014 Chinese is always "\u7B80\u4F53\u4E2D\u6587", regardless of UI locale.
private val languageOptions = listOf(
    LanguageOption("", "\uD83C\uDF10", ""),
    LanguageOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
    LanguageOption("zh", "\uD83C\uDDE8\uD83C\uDDF3", "简体中文"),
    // zh-Hant: values-zh-rTW has carried a Traditional Chinese translation
    // all along, but the picker never listed it and locales_config.xml never
    // declared it, so those users could not select it at all. The tag is
    // BCP-47 on purpose \u2014 LocaleList.forLanguageTags and
    // LocaleWrap.parseLocale both resolve it to the zh-rTW resources,
    // whereas a bare "zh" would land on Simplified.
    // [T-android-zh-hant-flag] Flag is HK (\uD83C\uDDED\uD83C\uDDF0), matching
    // iOS ContentView's own zh-Hant entry.
    //
    // It was TW (\uD83C\uDDF9\uD83C\uDDFC), which rendered as an EMPTY BOX on a
    // Huawei MatePad \u2014 Chinese-market ROMs generally ship fonts with that
    // flag glyph withheld, so the row had no icon at all.
    //
    // HK is the same glyph iOS already uses for this row, so it is the smallest
    // change that also keeps the platforms aligned. If it turns out to be
    // withheld on the same devices, the answer is to drop the flag for this row
    // rather than hunt for a third region \u2014 a script is not a country.
    //
    // A region flag is a poor label for a script in any case (zh-Hant is used
    // in TW, HK and MO alike), but keeping the two platforms on the same glyph
    // beats inventing a third answer here.
    LanguageOption("zh-Hant", "\uD83C\uDDED\uD83C\uDDF0", "\u7E41\u9AD4\u4E2D\u6587"),
    LanguageOption("ja", "\uD83C\uDDEF\uD83C\uDDF5", "日本語"),
    LanguageOption("ko", "\uD83C\uDDF0\uD83C\uDDF7", "한국어"),
    LanguageOption("fr", "\uD83C\uDDEB\uD83C\uDDF7", "Français"),
    LanguageOption("de", "\uD83C\uDDE9\uD83C\uDDEA", "Deutsch"),
    // ru: flag \uD83C\uDDF7\uD83C\uDDFA (RU), self-name \u0420\u0443\u0441\u0441\u043A\u0438\u0439 (Russkiy)
    LanguageOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
    // es: flag \uD83C\uDDEA\uD83C\uDDF8 (ES), self-name Espanol
    LanguageOption("es", "\uD83C\uDDEA\uD83C\uDDF8", "Espa\u00F1ol"),
    // id: flag \uD83C\uDDEE\uD83C\uDDE9 (ID), self-name Bahasa Indonesia.
    //
    // The tag is "id", but the resources live in res/values-in. Android's
    // resource resolver normalises Indonesian to the legacy code "in" at every
    // API level \u2014 a values-id directory is never consulted and the app would
    // silently fall back to English. Verified on Android 13 and 17 that "id"
    // resolves to values-in through both code paths this picker feeds:
    // LocaleList.forLanguageTags (API 33+, MainActivity) and new Locale(code)
    // (LocaleWrap, pre-33). Keep the tag modern here and the directory legacy.
    LanguageOption("id", "\uD83C\uDDEE\uD83C\uDDE9", "Bahasa Indonesia"),
    // ms: flag \uD83C\uDDF2\uD83C\uDDFE (MY), self-name Bahasa Melayu. No legacy-code caveat \u2014 the
    // tag and the directory (values-ms) agree, and Indonesian does not resolve
    // to it, so the two stay separate despite the shared vocabulary.
    LanguageOption("ms", "\uD83C\uDDF2\uD83C\uDDFE", "Bahasa Melayu"),
    // fil: flag \uD83C\uDDF5\uD83C\uDDED (PH), self-name Filipino. The tag is "fil", not "tl" \u2014 both
    // resolve to values-fil at runtime, but fil is the standardised national
    // language and the code CLDR and the Play Store use.
    LanguageOption("fil", "\uD83C\uDDF5\uD83C\uDDED", "Filipino"),
    // th: flag \uD83C\uDDF9\uD83C\uDDED (TH), self-name \u0E44\u0E17\u0E22. No naming caveat \u2014 values-th serves
    // both "th" and "th-TH".
    LanguageOption("th", "\uD83C\uDDF9\uD83C\uDDED", "\u0E44\u0E17\u0E22"),
    // tr: flag \uD83C\uDDF9\uD83C\uDDF7 (TR), self-name T\u00FCrk\u00E7e. Note the
    // dotted/dotless i \u2014 see LocaleAwareCase.uppercaseForDisplay for why the
    // section headers must not use a bare .uppercase() in this language.
    LanguageOption("tr", "\uD83C\uDDF9\uD83C\uDDF7", "T\u00FCrk\u00E7e"),
    // pl: flag \uD83C\uDDF5\uD83C\uDDF1 (PL), self-name Polski.
    LanguageOption("pl", "\uD83C\uDDF5\uD83C\uDDF1", "Polski"),
    // ro: flag \uD83C\uDDF7\uD83C\uDDF4 (RO), self-name Rom\u00E2n\u0103.
    LanguageOption("ro", "\uD83C\uDDF7\uD83C\uDDF4", "Rom\u00E2n\u0103"),
    // pt-BR: flag \uD83C\uDDE7\uD83C\uDDF7 (BR), self-name Portugu\u00EAs (Brasil). Region-qualified on
    // purpose \u2014 the resources live in values-pt-rBR and the wording is
    // Brazilian, so the tag should say so rather than claim generic "pt".
    LanguageOption("pt-BR", "\uD83C\uDDE7\uD83C\uDDF7", "Portugu\u00EAs (Brasil)"),
)

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

fun getThemeMode(context: Context): Int =
    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)

fun getFontScale(context: Context, key: String): Float =
    fontScaleForLevel(getAppearancePrefs(context).getInt(key, 0))

fun fontScaleForLevel(level: Int): Float {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleMultipliers.lastIndex)
    return fontScaleMultipliers[idx]
}

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onThemeChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
    var launchSession by remember { mutableIntStateOf(prefs.getInt(KEY_LAUNCH_SESSION, 0)) }
    var returnKeyBehavior by remember { mutableIntStateOf(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)) }
    var toolPreview by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_PREVIEW, true)) }
    var autoFocusAfterReply by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, true)) }
    var autoExpandThinking by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_EXPAND_THINKING, true)) }
    var showChatTitle by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_CHAT_TITLE, true)) }
    var autoGrouping by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_GROUPING, true)) }
    var chatInputLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_CHAT_INPUT, 0)) }
    var messageLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_MESSAGE, 0)) }
    var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
    var selectedLanguage by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, "") ?: "") }
    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.current(context)) }

    val fontsModified = chatInputLevel != 0 || messageLevel != 0 || appBaseLevel != 0

    val tilePurple = Color(0xFF5856D6)
    val tileBlue = Color(0xFF007AFF)
    val tileOrange = Color(0xFFFF9500)
    val tileGreen = Color(0xFF34C759)
    val tileTeal = Color(0xFF5AC8FA)

    SettingsScaffold(title = stringResource(R.string.appearance_title), onBack = onBack) {

        // -- Theme --
        // Each row carries its own leading icon + tile colour, mirroring the
        // Provider / Permissions screens. Earlier only the first row had an
        // icon and rows 2–3 fell through to a 24dp Spacer, which read as a
        // visual hiccup at the section boundary.
        SettingsSection(
            header = stringResource(R.string.appearance_section_theme),
            footer = stringResource(R.string.appearance_theme_footer),
        ) {
            data class ThemeRow(val label: String, val icon: ImageVector, val tint: Color)
            val themeRows = listOf(
                ThemeRow(stringResource(R.string.appearance_theme_system), Icons.Outlined.BrightnessAuto, tilePurple),
                ThemeRow(stringResource(R.string.appearance_theme_light), Icons.Outlined.LightMode, tileOrange),
                ThemeRow(stringResource(R.string.appearance_theme_dark), Icons.Outlined.DarkMode, tilePurple),
            )
            themeRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = themeMode == idx,
                    onSelect = {
                        themeMode = idx
                        prefs.edit().putInt(KEY_THEME_MODE, idx).apply()
                        onThemeChanged(idx)
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < themeRows.size - 1,
                )
            }
        }

        // -- Launch Session --
        // Same per-row icon treatment as the Theme section. Bolt = Auto
        // (system picks), History = Last Session (revisit), ChatBubble =
        // New Chat (compose), Home = Home screen.
        SettingsSection(
            header = stringResource(R.string.appearance_section_launch),
            footer = stringResource(R.string.appearance_launch_footer),
        ) {
            data class LaunchRow(val label: String, val icon: ImageVector, val tint: Color)
            val launchRows = listOf(
                LaunchRow(stringResource(R.string.appearance_launch_auto), Icons.Outlined.Bolt, tileBlue),
                LaunchRow(stringResource(R.string.appearance_launch_last), Icons.Outlined.History, tileTeal),
                LaunchRow(stringResource(R.string.appearance_launch_new), Icons.Outlined.ChatBubbleOutline, tileGreen),
                LaunchRow(stringResource(R.string.appearance_launch_home), Icons.Outlined.Home, tileBlue),
            )
            launchRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = launchSession == idx,
                    onSelect = {
                        launchSession = idx
                        prefs.edit().putInt(KEY_LAUNCH_SESSION, idx).apply()
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < launchRows.size - 1,
                )
            }
        }

        // -- Return Key (mirrors iOS AppearanceSettingsView stringResource(R.string.appearance_section_return_key) section) --
        // 0=Newline (default), 1=Send. Hardware Shift+Enter always inserts a
        // newline regardless of this setting — matches iOS behavior and
        // overrides the read in ChatScreen's onKeyEvent handler.
        SettingsSection(
            header = stringResource(R.string.appearance_section_return_key),
            footer = stringResource(R.string.appearance_return_key_footer),
        ) {
            data class ReturnRow(val label: String, val value: Int)
            val returnRows = listOf(
                ReturnRow(stringResource(R.string.appearance_return_key_newline), 0),
                ReturnRow(stringResource(R.string.appearance_return_key_send), 1),
            )
            returnRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = returnKeyBehavior == row.value,
                    onSelect = {
                        returnKeyBehavior = row.value
                        prefs.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, row.value).apply()
                    },
                    leading = {
                        if (idx == 0) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                tint = tilePurple,
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                tint = tileGreen,
                            )
                        }
                    },
                    showDivider = idx < returnRows.size - 1,
                )
            }
        }

        // -- Keep Screen Awake --
        // Holds FLAG_KEEP_SCREEN_ON on the activity window while any session
        // has an active task (mirrors iOS UIApplication.isIdleTimerDisabled
        // pattern in KeepScreenAwakeController). Default off — battery cost
        // is real and most users don't need it.
        SettingsSection(
            header = stringResource(R.string.appearance_section_keep_awake),
            footer = stringResource(R.string.appearance_keep_awake_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ScreenLockPortrait,
                iconColor = tileGreen,
                title = stringResource(R.string.appearance_keep_awake_title),
                checked = keepScreenAwake,
                onCheckedChange = {
                    keepScreenAwake = it
                    prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Tool Status Bar --
        SettingsSection(
            header = stringResource(R.string.appearance_section_tool_preview),
            footer = stringResource(R.string.appearance_tool_preview_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Visibility,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_tool_preview_title),
                checked = toolPreview,
                onCheckedChange = {
                    toolPreview = it
                    prefs.edit().putBoolean(KEY_TOOL_PREVIEW, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-thinking-auto-expand-toggle] -- Deep Thinking --
        // Whether a NEW streaming thinking block opens expanded (historical
        // behavior, default ON) or stays collapsed. Only affects the streaming
        // auto-expand; manual taps always work either way. Mirrors iOS
        // AppearanceSettingsView "Deep Thinking" section.
        SettingsSection(
            header = stringResource(R.string.appearance_section_deep_thinking),
            footer = stringResource(R.string.appearance_auto_expand_thinking_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Psychology,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpandThinking,
                onCheckedChange = {
                    autoExpandThinking = it
                    prefs.edit().putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-keyboard-auto-pop default flip] -- Auto-Focus After Reply --
        // Default ON — most users want the composer ready for a follow-up
        // immediately after the model finishes.
        SettingsSection(
            header = stringResource(R.string.appearance_section_auto_focus_after_reply),
            footer = stringResource(R.string.appearance_auto_focus_after_reply_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Keyboard,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_focus_after_reply_title),
                checked = autoFocusAfterReply,
                onCheckedChange = {
                    autoFocusAfterReply = it
                    prefs.edit().putBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Chat Title (T-chat-title-pill) --
        // Sticky session-title pill that appears at the top of the chat
        // when the user scrolls back through history. Default ON; toggle
        // also reachable via `minis-config set appearance.show_chat_title`.
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_title),
            footer = stringResource(R.string.appearance_show_chat_title_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_show_chat_title),
                subtitle = stringResource(R.string.appearance_show_chat_title_subtitle),
                checked = showChatTitle,
                onCheckedChange = {
                    showChatTitle = it
                    prefs.edit().putBoolean(KEY_SHOW_CHAT_TITLE, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Auto-Grouping (T-android-auto-grouping) --
        // Rides the title-generation call, so enabling it costs no extra
        // request. Port of iOS ContentView's "Grouping" section.
        SettingsSection(
            header = stringResource(R.string.appearance_section_grouping),
            footer = stringResource(R.string.appearance_auto_grouping_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Folder,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_grouping),
                subtitle = stringResource(R.string.appearance_auto_grouping_subtitle),
                checked = autoGrouping,
                onCheckedChange = {
                    autoGrouping = it
                    prefs.edit().putBoolean(KEY_AUTO_GROUPING, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Font Size --
        SettingsSection(
            header = stringResource(R.string.appearance_section_font_size),
            footer = stringResource(R.string.appearance_font_size_footer),
        ) {
            SettingsRow(
                icon = Icons.Outlined.FormatSize,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_font_scale_title),
                subtitle = stringResource(R.string.appearance_font_scale_subtitle),
                onClick = null,
                showChevron = false,
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_chat_input),
                level = chatInputLevel,
                onLevelChange = {
                    chatInputLevel = it
                    prefs.edit().putInt(KEY_FONT_CHAT_INPUT, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_message),
                level = messageLevel,
                onLevelChange = {
                    messageLevel = it
                    prefs.edit().putInt(KEY_FONT_MESSAGE, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_app_base),
                level = appBaseLevel,
                onLevelChange = {
                    appBaseLevel = it
                    prefs.edit().putInt(KEY_FONT_APP_BASE, it).apply()
                },
                showDivider = fontsModified,
            )
            if (fontsModified) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MinisTextButton(onClick = {
                        chatInputLevel = 0; messageLevel = 0; appBaseLevel = 0
                        prefs.edit()
                            .putInt(KEY_FONT_CHAT_INPUT, 0)
                            .putInt(KEY_FONT_MESSAGE, 0)
                            .putInt(KEY_FONT_APP_BASE, 0)
                            .apply()
                    }) {
                        Text(stringResource(R.string.appearance_font_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // -- App Icon (T-android-dynamic-app-icon) --
        // Grid picker mirrors the iOS Settings → Appearance → App Icon
        // section but uses a 3-column grid layout per spec. Each tile is
        // an adaptive-icon preview (loaded as Bitmap via ResourcesCompat
        // since painterResource can't decode mipmap-anydpi-v26 XMLs);
        // the currently-selected tile gets a checkmark badge in the
        // top-right corner. Tapping a tile flips the corresponding
        // activity-alias enabled state via PackageManager — the launcher
        // refreshes its icon cache within a few seconds.
        SettingsSection(
            header = stringResource(R.string.appearance_section_app_icon),
            footer = stringResource(R.string.appearance_app_icon_footer),
        ) {
            data class IconOption(
                val variant: AppIconRepository.Variant,
                val titleRes: Int,
                val mipmapRes: Int,
            )
            val iconOptions = listOf(
                IconOption(
                    AppIconRepository.Variant.Auto,
                    R.string.appearance_app_icon_auto,
                    R.mipmap.ic_launcher,
                ),
                IconOption(
                    AppIconRepository.Variant.ClassicLight,
                    R.string.appearance_app_icon_light,
                    R.mipmap.ic_launcher_classic_light,
                ),
                IconOption(
                    AppIconRepository.Variant.ClassicDark,
                    R.string.appearance_app_icon_dark,
                    R.mipmap.ic_launcher_classic_dark,
                ),
            )
            // [T-android-app-icon-tile-max-width] Cap each tile and centre the
            // row.
            //
            // The tiles are weight(1f) + aspectRatio(1f), so on a tablet they
            // grew to ~380dp each. The preview bitmap is rasterized at 192px
            // (a launcher icon's native size — there is no larger source), so
            // at that size it is being upscaled roughly 4.6x on this display
            // and visibly blurs. The cap keeps the artwork at or under its own
            // resolution; the row centres so the three stay together as a group
            // instead of drifting apart across a wide settings pane.
            //
            // Phones are unaffected: a third of a phone-width row is already
            // well under the cap, so widthIn is inert there and the layout is
            // byte-identical.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    12.dp,
                    Alignment.CenterHorizontally,
                ),
            ) {
                for (option in iconOptions) {
                    val isSelected = selectedAppIcon == option.variant
                    val iconPainter: Painter = remember(option.mipmapRes) {
                        // painterResource() can't decode adaptive-icon
                        // XML drawables (mipmap-anydpi-v26), so rasterize
                        // the drawable into a Bitmap first.
                        val drawable = ResourcesCompat.getDrawable(
                            context.resources,
                            option.mipmapRes,
                            context.theme,
                        )
                        if (drawable != null) {
                            BitmapPainter(
                                drawable.toBitmap(width = 192, height = 192).asImageBitmap()
                            )
                        } else {
                            BitmapPainter(
                                android.graphics.Bitmap
                                    .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                                    .asImageBitmap()
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // 192dp = the preview bitmap's own 192px, so the
                            // artwork is never scaled past 1:1 at mdpi and only
                            // mildly at higher densities. `fill = false` on the
                            // weight is what lets the tile stop growing at the
                            // cap instead of being forced to its full share.
                            .widthIn(max = APP_ICON_TILE_MAX_WIDTH)
                            .clickable {
                                if (selectedAppIcon != option.variant) {
                                    selectedAppIcon = option.variant
                                    AppIconRepository.apply(context, option.variant)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                ),
                        ) {
                            Image(
                                painter = iconPainter,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .background(
                                            color = Color.White,
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(option.titleRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        // -- Language --
        SettingsSection(
            header = stringResource(R.string.appearance_section_language),
            footer = stringResource(R.string.appearance_language_footer),
        ) {
            languageOptions.forEachIndexed { idx, lang ->
                SettingsChoiceRow(
                    title = if (lang.code.isEmpty()) stringResource(R.string.appearance_theme_system) else lang.label,
                    selected = selectedLanguage == lang.code,
                    onSelect = {
                        selectedLanguage = lang.code
                        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val lm = context.getSystemService(LocaleManager::class.java)
                            lm?.applicationLocales = if (lang.code.isEmpty()) {
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(lang.code)
                            }
                        } else {
                            // T-n01-andmenu-l10n: pre-Tiramisu has no
                            // LocaleManager. The new language is read
                            // from SharedPreferences by
                            // [LocaleWrap.wrap] on the next
                            // attachBaseContext call, so recreate the
                            // Activity so its base Configuration picks
                            // up the change immediately.
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    leading = {
                        Text(lang.flag, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                    },
                    showDivider = idx < languageOptions.size - 1,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleValues.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(idx.toFloat()) }
    val currentLabel = fontScaleLabels.getOrElse(sliderPos.roundToInt()) { "Default" }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val newIdx = sliderPos.roundToInt().coerceIn(0, fontScaleValues.lastIndex)
                    sliderPos = newIdx.toFloat()
                    onLevelChange(fontScaleValues[newIdx])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}
