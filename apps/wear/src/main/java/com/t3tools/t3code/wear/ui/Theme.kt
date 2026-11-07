package com.t3tools.t3code.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ColorScheme
import com.t3tools.t3code.wear.domain.ThreadStatus

/** Neutral, near-black surfaces like the T3 Code apps, with status colors carrying the meaning. */
val T3ColorScheme = ColorScheme(
    primary = Color(0xFFE8E8EC),
    onPrimary = Color(0xFF111114),
    primaryContainer = Color(0xFF2A2A30),
    onPrimaryContainer = Color(0xFFE8E8EC),
    secondary = Color(0xFFB4B4BE),
    onSecondary = Color(0xFF111114),
    secondaryContainer = Color(0xFF232328),
    onSecondaryContainer = Color(0xFFE8E8EC),
    tertiary = Color(0xFF9DB8FF),
    onTertiary = Color(0xFF0B1630),
    tertiaryContainer = Color(0xFF1C2742),
    onTertiaryContainer = Color(0xFFD6E0FF),
    surfaceContainerLow = Color(0xFF16161A),
    surfaceContainer = Color(0xFF1E1E23),
    surfaceContainerHigh = Color(0xFF28282E),
    onSurface = Color(0xFFF2F2F5),
    onSurfaceVariant = Color(0xFFA4A4AE),
    outline = Color(0xFF55555E),
    outlineVariant = Color(0xFF34343A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F5),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2B0503),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6),
)

object StatusColors {
    val attention = Color(0xFFFFB74D)
    val working = Color(0xFF64B5F6)
    val waiting = Color(0xFFB39DDB)
    val failed = Color(0xFFFF8A80)
    val ready = Color(0xFF6E6E78)
    val healthy = Color(0xFF81C784)
}

fun ThreadStatus.color(): Color = when (this) {
    ThreadStatus.Approval, ThreadStatus.Input, ThreadStatus.Request -> StatusColors.attention
    ThreadStatus.Working -> StatusColors.working
    ThreadStatus.Waiting -> StatusColors.waiting
    ThreadStatus.Failed, ThreadStatus.Limited -> StatusColors.failed
    ThreadStatus.Ready -> StatusColors.ready
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** Remaining quota reads green until it gets tight. */
fun remainingColor(remainingPercent: Int): Color = when {
    remainingPercent <= 10 -> StatusColors.failed
    remainingPercent <= 30 -> StatusColors.attention
    else -> StatusColors.healthy
}
