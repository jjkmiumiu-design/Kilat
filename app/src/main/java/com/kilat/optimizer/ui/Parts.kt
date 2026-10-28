package com.kilat.optimizer.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Kartu membulat, isian permukaan gelap keunguan. Dipakai untuk hampir semua isi layar. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = LocalK.current
    val shape = RoundedCornerShape(28.dp)
    val base = Modifier.fillMaxWidth().clip(shape).background(k.surface).border(1.dp, k.line, shape)
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).then(modifier).padding(18.dp),
        content = content,
    )
}

/** Tombol persegi membulat berisi satu ikon, dipakai di bilah atas. */
@Composable
internal fun IconTile(icon: ImageVector, description: String, onClick: () -> Unit) {
    val k = LocalK.current
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(k.surface)
            .border(1.dp, k.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = k.link, modifier = Modifier.size(26.dp))
    }
}

/** Lingkaran berisi tanda petir, dipakai sebagai logo di bilah atas dan layar masuk. */
@Composable
internal fun LogoDisc(size: Dp) {
    val k = LocalK.current
    Box(Modifier.size(size).clip(CircleShape).background(k.surface2), contentAlignment = Alignment.Center) {
        BoltMark(size * 0.5f, k.link)
    }
}

/** Garis pemisah tipis. Struktur layar dibentuk oleh garis ini, bukan oleh kartu. */
@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalK.current.line))
}

/**
 * Tampilan "3D" tipis: gradasi gelap kekuningan, garis tepi cahaya putih yang memudar ke bawah,
 * dan satu garis terang di tepi atas bagian dalam.
 */
@Composable
internal fun Modifier.glossy(shape: Shape): Modifier {
    val k = LocalK.current
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(k.keyTop, k.keyBottom)))
        .drawBehind {
            val inset = 14.dp.toPx()
            drawLine(
                Color.White.copy(alpha = 0.22f),
                Offset(inset, 1.dp.toPx()), Offset(size.width - inset, 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        .border(1.dp, Brush.verticalGradient(listOf(k.sheen, k.sheen.copy(alpha = 0.10f))), shape)
}

/** Lencana status: isian tipis berwarna, ikon bulat di kiri. */
@Composable
internal fun StatusPill(text: String, color: Color, ok: Boolean) {
    val k = LocalK.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = k.fg, fontSize = 15.sp)
    }
}

/** Tombol standar. [primary] = 3D gelap kekuningan, selain itu hanya garis tepi. */
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
    val shape = RoundedCornerShape(16.dp)
    val look = if (primary) {
        Modifier.glossy(shape)
    } else {
        Modifier.border(1.dp, k.link.copy(alpha = 0.45f), shape)
    }
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f).clip(shape).then(look)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) k.fg else k.link,
            fontFamily = KFont.display,
            fontSize = if (compact) 14.sp else 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Saklar standar. Trek ungu saat aktif. */
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

/** Label kecil berisi ungu, mis. nama kelompok fitur. */
@Composable
internal fun KChip(text: String) {
    val k = LocalK.current
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(k.accent).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = k.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
