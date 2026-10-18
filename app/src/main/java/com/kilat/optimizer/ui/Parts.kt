package com.kilat.optimizer.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Garis pemisah tipis. Struktur layar dibentuk oleh garis ini, bukan oleh kartu. */
@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalK.current.line))
}

/** Tombol standar. [primary] = isi kuning, selain itu hanya garis tepi. */
@Composable
internal fun KButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val k = LocalK.current
    val shape = RoundedCornerShape(10.dp)
    val look = if (primary) {
        Modifier.background(k.accent)
    } else {
        Modifier.border(1.dp, k.fg.copy(alpha = 0.28f), shape)
    }
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f).clip(shape).then(look)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) k.accentInk else k.fg,
            fontFamily = KFont.display,
            fontSize = if (compact) 14.sp else 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Saklar standar. Trek kuning saat aktif. */
@Composable
internal fun KToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val k = LocalK.current
    val shape = RoundedCornerShape(14.dp)
    val x by animateDpAsState(if (checked) 24.dp else 4.dp, tween(120), label = "toggle")
    Box(
        Modifier.size(width = 48.dp, height = 28.dp).alpha(if (enabled) 1f else 0.45f).clip(shape)
            .background(if (checked) k.accent else k.track)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Box(
            Modifier.offset(x = x, y = 4.dp).size(20.dp).clip(CircleShape)
                .background(if (checked) k.accentInk else k.muted),
        )
    }
}

/** Lampu indikator bulat kecil. */
@Composable
internal fun Lamp(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Tanda petir Kilat, digambar sendiri. */
@Composable
internal fun BoltMark(dim: Dp, color: Color) {
    Canvas(Modifier.size(dim)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.68f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.32f, h)
            lineTo(w * 0.90f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(p, color)
    }
}

/** Batang ukur tipis. */
@Composable
internal fun Meter(fraction: Float, color: Color) {
    val k = LocalK.current
    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(k.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(color))
    }
}
