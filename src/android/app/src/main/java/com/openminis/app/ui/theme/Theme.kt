package com.openminis.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent: iOS blue, desaturated. [T-android-accent-blue-parity]
//
// Was a teal (#2E8B8B / #4DD9D9) that predated iOS settling on blue. The hue
// now comes from iOS Assets.xcassets/AccentColor.colorset (sRGB components
// r0.212 g0.525 b0.933 -> #3686EE light, r0.329 g0.565 b0.894 -> #5490E4 dark),
// but iOS's saturation (84% / 73%) read as glaring on Android's darker
// surfaces, so SATURATION is dialled back ~30% with hue and lightness kept:
//   light  #3686EE  S84% L57%  ->  #528AD2  S59% L57%
//   dark   #5490E4  S73% L61%  ->  #6A94CE  S51% L61%
//
// Lightness is deliberately NOT raised, which is the other way to "lighten".
// It would have softened dark mode further but pushed light-mode contrast on
// white from 3.62 to 2.62 — below WCAG AA's 4.5 for text. Desaturating keeps
// dark mode at 5.93 (passing) and leaves light mode where it was.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
private val TealPrimary = Color(0xFF528AD2)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFB2DFDB)
private val TealOnPrimaryContainer = Color(0xFF00332F)
private val TealSecondary = Color(0xFF4A6360)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFCCE8E4)
private val TealOnSecondaryContainer = Color(0xFF05201D)
private val TealTertiary = Color(0xFF46617A)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFCDE5FF)
private val TealOnTertiaryContainer = Color(0xFF001D32)
private val TealBackground = Color(0xFFF5FAFA)
private val TealOnBackground = Color(0xFF171D1C)
private val TealSurface = Color(0xFFF5FAFA)
private val TealOnSurface = Color(0xFF171D1C)
private val TealSurfaceVariant = Color(0xFFDAE5E2)
private val TealOnSurfaceVariant = Color(0xFF3F4947)
private val TealOutline = Color(0xFF6F7977)

private val TealDarkPrimary = Color(0xFF6A94CE)
private val TealDarkOnPrimary = Color(0xFF003737)
private val TealDarkPrimaryContainer = Color(0xFF1A6B6B)
private val TealDarkOnPrimaryContainer = Color(0xFFB2DFDB)
private val TealDarkSecondary = Color(0xFFB1CCC8)
private val TealDarkOnSecondary = Color(0xFF1C3532)
private val TealDarkSecondaryContainer = Color(0xFF334B48)
private val TealDarkOnSecondaryContainer = Color(0xFFCCE8E4)
private val TealDarkBackground = Color(0xFF0E1514)
private val TealDarkOnBackground = Color(0xFFDEE4E2)
private val TealDarkSurface = Color(0xFF0E1514)
private val TealDarkOnSurface = Color(0xFFDEE4E2)
private val TealDarkSurfaceVariant = Color(0xFF3F4947)
private val TealDarkOnSurfaceVariant = Color(0xFFBEC9C6)
private val TealDarkOutline = Color(0xFF899390)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
// Light: page = #F2F2F7 gray, card = white
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFF2F2F7)
private val NeutralGroupedCard = Color(0xFFFFFFFF)
private val NeutralGroupedCardElevated = Color(0xFFF7F7FA)
private val NeutralOutline = Color(0xFFD1D1D6)

private val NeutralDarkGroupedBg = Color(0xFF000000)
private val NeutralDarkGroupedCard = Color(0xFF1C1C1E)
private val NeutralDarkGroupedCardElevated = Color(0xFF2C2C2E)
private val NeutralDarkOutline = Color(0xFF38383A)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedBg,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// Icon tint paired with [minisFabColor]. Reads from the palette so the dynamic
// path can pair onPrimary with the wallpaper-derived fabAccent; the static
// path keeps the White the icon hardcoded before this slot existed.
@Composable
fun minisFabContentColor(): Color = LocalChatPalette.current.fabOnAccent

// True only while the wallpaper scheme is actually in effect (dynamic color ON
// and Android 12+). Consumers use it to swap hand-tuned iOS-parity accents for
// scheme roles without re-deriving the gate themselves.
val LocalMonetDynamic = compositionLocalOf { false }

// iOS-parity tile hues that follow the wallpaper when dynamic color is on.
// Red (destructive) and systemGray (neutral/informational) deliberately stay
// static: their hue IS the message.
internal val monetAccentTiles = setOf(
    Color(0xFF007AFF), // iOS systemBlue
    Color(0xFF34C759), // iOS systemGreen
    Color(0xFFFF9500), // iOS systemOrange
    Color(0xFF5856D6), // iOS indigo
    Color(0xFFAF52DE), // iOS purple
    Color(0xFF30B0C7), // iOS teal
    Color(0xFF5AC8FA), // iOS light teal
)

/** Tint for plain (chip-less) icons: wallpaper accent in dynamic mode. */
@Composable
fun monetIconTint(static: Color): Color =
    if (LocalMonetDynamic.current && static in monetAccentTiles) {
        MaterialTheme.colorScheme.primary
    } else {
        static
    }

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // [T-android-monet-dynamic-color] The framework only carries the system_*
    // color resources on Android 12+, so the gate is a correctness
    // requirement, not a nicety: below S those resources do not resolve.
    // minSdk is 26, hence the explicit gate. In dynamic mode the wallpaper
    // scheme supplies EVERY slot — accents AND surfaces; Material You derives
    // surface/onSurface pairs with contrast built in, so the hand-measured
    // WCAG notes on the static palettes do not apply to this branch.
    val dynamicActive = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (dynamicActive) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = scaledTypography(fontScale)
    val fallbackChatPalette = if (darkTheme) DarkChatPalette else LightChatPalette
    val chatPalette =
        if (dynamicActive) {
            dynamicChatPalette(colorScheme, fallbackChatPalette)
        } else {
            fallbackChatPalette
        }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette,
            LocalMonetDynamic provides dynamicActive,
            content = content,
        )
    }
}

/**
 * [T-android-monet-dynamic-color] The chat palette follows the wallpaper too.
 *
 * The chat palette is where Minis is most itself — 34 slots of hand-tuned iOS
 * system colors — and it does not read [MaterialTheme.colorScheme], so a
 * scheme-only change left the biggest visible surface (chat) untouched, which
 * is why toggling dynamic color changed almost nothing a user could see.
 *
 * Surface, text and accent slots all derive from the wallpaper scheme; each
 * takes a slot the framework already contrast-tunes rather than a
 * hand-blended guess:
 *  - userBubble = primaryContainer: Monet designs primaryContainer to pair
 *    with dark-on-light / light-on-dark text, and the bubble text is
 *    onSurface (black/white) — the pairing holds in both modes without any
 *    alpha arithmetic (the T-android-user-bubble-dark-contrast note in
 *    ChatColors.kt is exactly the failure mode translucent guesses produce).
 *  - sendButton / link / thinking / fabAccent = primary: tone-40 on white and
 *    tone-80 on black are the pairings Material You derives primary FOR.
 *  - fabOnAccent = onPrimary: the FAB icon tint, paired by construction. The
 *    static palettes keep White, matching the icon's previous hardcoded tint,
 *    so the OFF path is byte-identical.
 *  - toastBg = primary at the same 0x2E alpha both static palettes use.
 *
 * Semantic slots (warning orange, syntax green, blockquote bar) and the
 * dark-mode elevation shadow stay on [fallback] — their hue IS the message.
 *
 * Pure so the rule is unit-testable alongside the static palettes.
 */
internal fun dynamicChatPalette(scheme: ColorScheme, fallback: ChatPalette): ChatPalette =
    fallback.copy(
        // Surfaces — the chat tints with the wallpaper like everything else.
        background = scheme.background,
        secondaryBg = scheme.surfaceContainerLow,
        inputBg = scheme.surfaceContainerLowest,
        inputIconBg = scheme.surfaceContainerHigh,
        inputIconBorder = scheme.outlineVariant.copy(alpha = 0.4f),
        inputBorder = scheme.outlineVariant.copy(alpha = 0.6f),
        toolBg = scheme.surfaceContainer,
        toolBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        toolCapsuleBg = scheme.surfaceContainerLow,
        separator = scheme.outlineVariant,
        tableBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        thumbnailBorder = scheme.outlineVariant.copy(alpha = 0.4f),
        sheetHeaderBg = scheme.surfaceContainerLow,
        sheetHeaderBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        inlineCodeBg = scheme.surfaceContainerLow,
        // Text pairs come with the scheme — contrast already tuned.
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        tertiaryText = scheme.outline,
        disabledText = scheme.onSurface.copy(alpha = 0.18f),
        sendButtonDisabled = scheme.onSurface.copy(alpha = 0.18f),
        // Accents (see the FAB pairing note above; userBubble text is
        // onSurface, which pairs with primaryContainer in both modes).
        userBubble = scheme.primaryContainer,
        sendButton = scheme.primary,
        link = scheme.primary,
        thinking = scheme.primary,
        toastBg = scheme.primary.copy(alpha = 0.18f),
        fabAccent = scheme.primaryContainer,
        fabOnAccent = scheme.onPrimaryContainer,
        // Kept on the fallback on purpose: codeBlockBg/Text and inlineCodeText
        // are terminal-green syntax identity, blockquoteBar/warning* are
        // semantic hues, inputShadow is the dark-mode elevation shadow, and
        // isDark marks the mode for consumers that branch on it.
    )

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

private fun scaledTypography(factor: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}

/**
 * [T-android-spacing-rhythm] App-wide spacing tokens — 4dp base unit, 8dp
 * rhythm. Use these for structural padding/gaps instead of raw dp literals so
 * the visual rhythm stays consistent and a future density pass has one place
 * to tune. Existing hand-tuned micro-spacings (6dp chip insets, 10dp badge
 * padding) are left as-is; new code should prefer these tokens.
 *
 * xs=4  sm=8  md=12  lg=16  xl=20  xxl=24
 */
object MinisSpacing {
    val xs = 4.dp   // hairline gaps, icon-to-text
    val sm = 8.dp   // primary rhythm unit — default row/column gap
    val md = 12.dp  // card inner padding, list item horizontal
    val lg = 16.dp  // section padding, screen horizontal
    val xl = 20.dp  // card-to-card, section header gap
    val xxl = 24.dp // screen edge, large separation
}
