package com.liu.overtime.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared with the web's visual language; static artwork adds no recurring frame work. */
@Composable
internal fun WebScene(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
    Canvas(modifier) {
        drawRect(Brush.linearGradient(if (dark) listOf(Color(0xFF101B16), Color(0xFF15221D))
            else listOf(Color(0xFFF6F6F2), Color(0xFFEFF3F1), Color(0xFFEDF2EE))))
        val green = if (dark) Color(0xFF213B2D) else Color(0xFFD8E7DC)
        val lavender = if (dark) Color(0xFF252D38) else Color(0xFFE3E5F0)
        drawCircle(Brush.radialGradient(listOf(green, Color.Transparent), Offset(size.width, 0f), size.width * 1.2f),
            size.width * 1.2f, Offset(size.width, 0f))
        drawCircle(Brush.radialGradient(listOf(lavender.copy(alpha = .65f), Color.Transparent),
            Offset(0f, size.height * .6f), size.width), size.width, Offset(0f, size.height * .6f))
        val rim = if (dark) Color(0xFFACCDB6).copy(alpha = .035f) else Color.White.copy(alpha = .38f)
        drawOval(rim, Offset(size.width * .66f, -size.width * .48f), Size(size.width * .72f, size.width * 1.3f),
            style = Stroke(20.dp.toPx()))
        drawOval(rim, Offset(size.width * .6f, -size.width * .42f), Size(size.width * .72f, size.width * 1.3f),
            style = Stroke(1.dp.toPx()))
    }
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hero: Boolean = false,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .2f
    val shape = RoundedCornerShape(26.dp)
    val fill = when {
        hero && dark -> listOf(Color(0xFF2C4E35), Color(0xFF1C3427))
        hero -> listOf(Color(0xFFD8ECDB), Color(0xFFEEF7EC))
        dark -> listOf(colors.surface.copy(alpha = .97f), colors.surface.copy(alpha = .92f))
        else -> listOf(Color.White.copy(alpha = .86f), Color.White.copy(alpha = .68f))
    }
    Box(modifier.fillMaxWidth()
        .shadow(if (hero) 10.dp else 6.dp, shape, clip = false,
            ambientColor = Color(0xFF1D3D2D).copy(alpha = if (dark) .35f else .07f),
            spotColor = Color.Black.copy(alpha = if (dark) .22f else .045f))
        .clip(shape).background(Brush.linearGradient(fill))
        .border(1.dp, Brush.linearGradient(if (dark) listOf(Color(0xFFC8ECD4).copy(alpha = .19f), colors.outlineVariant.copy(alpha = .35f))
            else listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .56f))), shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        if (hero) Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * 1.03f, size.height * .25f)
            drawCircle(Color.White.copy(alpha = if (dark) .045f else .21f), size.width * .31f, center, style = Stroke(17.dp.toPx()))
            drawCircle(Color.White.copy(alpha = if (dark) .035f else .14f), size.width * .39f, center, style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.fillMaxWidth().padding(padding), content = content)
    }
}

@Composable
internal fun CardHeading(title: String, eyebrow: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(eyebrow, fontSize = 10.sp, letterSpacing = .8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.3).sp)
        }
    }
}

@Composable
internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/** Fit long totals and large accessibility fonts without wrapping digits or their unit. */
@Composable
internal fun NumericValue(value: String, modifier: Modifier = Modifier, unit: String = "", large: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText(value, modifier = Modifier.weight(1f, fill = false), maxLines = 1, softWrap = false,
            style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (large) 60.sp else 34.sp, lineHeight = if (large) 70.sp else 42.sp,
                fontWeight = FontWeight.Medium, letterSpacing = if (large) (-2).sp else (-1).sp, fontFeatureSettings = "tnum"),
            autoSize = TextAutoSize.StepBased(minFontSize = if (large) 18.sp else 12.sp, maxFontSize = if (large) 60.sp else 34.sp, stepSize = 1.sp))
        if (unit.isNotEmpty()) Text(unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = if (large) 12.dp else 6.dp))
    }
}

@Composable
internal fun SoftLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f))
        .padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer)
}

@Composable
internal fun ActionLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, maxLines = 1, softWrap = false,
        style = LocalTextStyle.current.copy(color = LocalContentColor.current, fontWeight = FontWeight.SemiBold),
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 13.sp, stepSize = .5.sp))
}

@Composable
internal fun AppearancePicker(selected: String, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.onSurface.copy(alpha = .045f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
            val fill by animateColorAsState(if (selected == key) colors.surface else Color.Transparent,
                tween(GlassMotion.ContentMillis, easing = GlassMotion.Easing), label = "appearance")
            Box(Modifier.weight(if (key == "system") 1.4f else 1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .background(fill).selectable(selected == key, role = Role.RadioButton, onClick = { onSelect(key) })
                .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, fontWeight = if (selected == key) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected == key) colors.primary else colors.onSurfaceVariant)
            }
        }
    }
}
