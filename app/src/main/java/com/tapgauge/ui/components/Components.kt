package com.tapgauge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tapgauge.calibration.Confidence

/** Live input-level meter (spec section 3.4/7): teaches the user how hard to tap. */
@Composable
fun LevelMeter(rms: Double, modifier: Modifier = Modifier) {
    // Map RMS (~0..0.4 for a knock) to a 0..1 bar with a little headroom.
    val level = (rms / 0.4).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(level, label = "level")
    val color by animateColorAsState(
        when {
            level > 0.85f -> Color(0xFFD62828) // clipping-ish, back off
            level > 0.25f -> Color(0xFF2A9D8F) // good knock range
            else -> Color(0xFF9AA0A6)          // too quiet
        },
        label = "levelColor",
    )
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(color),
            )
        }
    }
}

@Composable
fun ReadingConfidenceBadge(c: ReadingConfidence, modifier: Modifier = Modifier) {
    val (label, color) = when (c) {
        ReadingConfidence.HIGH -> "High confidence" to Color(0xFF2A9D8F)
        ReadingConfidence.MEDIUM -> "Medium confidence" to Color(0xFFE9C46A)
        ReadingConfidence.LOW -> "Low confidence \u2014 tap again" to Color(0xFFE76F51)
    }
    Badge(label, color, modifier)
}

@Composable
fun CalibrationConfidenceBadge(c: Confidence, modifier: Modifier = Modifier) {
    val (label, color) = when (c) {
        Confidence.HIGH -> "High confidence" to Color(0xFF2A9D8F)
        Confidence.MEDIUM -> "Medium (3 points)" to Color(0xFFB5C99A)
        Confidence.ROUGH -> "Rough estimate (2 points)" to Color(0xFFE9C46A)
        Confidence.UNCALIBRATED -> "Uncalibrated" to Color(0xFF9AA0A6)
    }
    Badge(label, color, modifier)
}

@Composable
private fun Badge(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge)
    }
}
