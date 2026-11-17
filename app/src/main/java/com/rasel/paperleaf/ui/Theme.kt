package com.rasel.paperleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.paperleaf.R

/** Warm, faded 70s palette: paper, ink and a few poster colors. */
@Immutable
data class Palette(
    val paper: Color,
    val paperDark: Color,
    val card: Color,
    val ink: Color,
    /** The hard offset shadow of a box (a light one would glow at night). */
    val shadow: Color,
    val night: Boolean,
)

object Poster {
    val terracotta = Color(0xFFC4572F)
    val mustard = Color(0xFFE3A72F)
    val teal = Color(0xFF2E6F6A)
    val navy = Color(0xFF2B3A55)

    /** Text on mustard and highlight colors. */
    val onLight = Color(0xFF2A1E17)

    /** Text on terracotta, teal and navy. */
    val onDark = Color(0xFFFCF6E6)
    val covers = listOf(terracotta, teal, mustard, navy)
}

val DayPalette = Palette(
    paper = Color(0xFFF4EAD5),
    paperDark = Color(0xFFE6D7B6),
    card = Color(0xFFFCF6E6),
    ink = Color(0xFF2A1E17),
    shadow = Color(0xFF2A1E17),
    night = false,
)

/** Used everywhere while the reader's page color is Night. */
val NightPalette = Palette(
    paper = Color(0xFF15120F),
    paperDark = Color(0xFF2E2822),
    card = Color(0xFF221D19),
    ink = Color(0xFFE6DCC8),
    shadow = Color(0xFF000000),
    night = true,
)

val LocalPalette = staticCompositionLocalOf { DayPalette }

object Fonts {
    /** Heavy retro serif for titles. */
    val display = FontFamily(Font(R.font.abril_fatface))

    /** Typewriter face for body text and labels. */
    val body = FontFamily(Font(R.font.special_elite))
}

@Composable
fun PaperleafTheme(palette: Palette, content: @Composable () -> Unit) {
    val ink = palette.ink
    val scheme = if (palette.night) {
        darkColorScheme(
            primary = Poster.terracotta, onPrimary = Poster.onDark,
            secondary = Poster.mustard, onSecondary = Poster.onLight,
            tertiary = Poster.teal, onTertiary = Poster.onDark,
            background = palette.paper, onBackground = ink,
            surface = palette.paper, onSurface = ink, onSurfaceVariant = ink,
            surfaceContainer = palette.card, surfaceContainerHigh = palette.card,
            surfaceContainerLow = palette.card, surfaceContainerHighest = palette.paperDark,
            outline = ink,
        )
    } else {
        lightColorScheme(
            primary = Poster.terracotta, onPrimary = Poster.onDark,
            secondary = Poster.mustard, onSecondary = Poster.onLight,
            tertiary = Poster.teal, onTertiary = Poster.onDark,
            background = palette.paper, onBackground = ink,
            surface = palette.paper, onSurface = ink, onSurfaceVariant = ink,
            surfaceContainer = palette.card, surfaceContainerHigh = palette.card,
            surfaceContainerLow = palette.card, surfaceContainerHighest = palette.paperDark,
            outline = ink,
        )
    }
    // No color here: text takes the color of what it sits on (ink on paper, paper on a dark bar).
    fun body(size: Int) = TextStyle(fontFamily = Fonts.body, fontSize = size.sp)
    fun display(size: Int) = TextStyle(fontFamily = Fonts.display, fontSize = size.sp)
    val type = Typography(
        headlineSmall = display(26),
        titleLarge = display(22),
        titleMedium = display(17),
        titleSmall = display(15),
        bodyLarge = body(16),
        bodyMedium = body(14),
        bodySmall = body(12),
        labelLarge = body(14),
        labelMedium = body(12),
        labelSmall = body(11),
    )
    CompositionLocalProvider(LocalPalette provides palette, androidx.compose.material3.LocalContentColor provides ink) {
        MaterialTheme(colorScheme = scheme, typography = type) {
            // Text outside a panel (headings on the paper) takes the ink color too.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink, content = content)
        }
    }
}

/** Ink-outlined box with a hard offset shadow (the core retro look). */
@Composable
fun RetroBox(
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.card,
    radius: Dp = 6.dp,
    shadow: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    // The box fills the size it is given (a fixed size, a weight), not only its content.
    Box(modifier, propagateMinConstraints = true) {
        if (shadow > 0.dp) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(shadow, shadow)
                    .background(p.shadow, shape),
            )
        }
        Box(
            Modifier
                .clip(shape)
                .background(color)
                .border(2.dp, p.ink, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            content = content,
        )
    }
}

/** Flat, outlined button with a hard shadow. */
@Composable
fun RetroButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Poster.mustard,
    foreground: Color = Poster.onLight,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
) {
    RetroBox(modifier, color = color, shadow = 3.dp, onClick = onClick) {
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = foreground)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label.uppercase(),
                style = TextStyle(fontFamily = Fonts.body, fontSize = 13.sp, letterSpacing = 1.2.sp, color = foreground),
                maxLines = 1,
            )
        }
    }
}
