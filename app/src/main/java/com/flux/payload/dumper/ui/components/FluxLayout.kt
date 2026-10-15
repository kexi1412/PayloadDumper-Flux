package com.flux.payload.dumper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * ColorOS 17 卡片列表背景。
 *
 * 底色精确值：coui_color_background_with_card = #F0F1F2（浅色）/ #000000（暗色）。
 */
@Composable
fun FluxBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

/**
 * COUI 17 分组圆角卡片。
 *
 * 圆角：settingslib_preference_corner_radius = 20dp。
 * 暗色模式加 0.5dp 极淡轮廓（coui_color_label_quaternary）。
 */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val flux = LocalFluxColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluxRadius.Card))
            .background(flux.card)
            .border(0.5.dp, flux.cardOutline, RoundedCornerShape(FluxRadius.Card)),
        content = content,
    )
}

/**
 * 行间分割线——COUI 17 使用极淡的内嵌发丝线。
 * 起始缩进 72dp（与 COUI 17 preference 的 paddingStart 32dp + icon 区域对齐）。
 */
@Composable
fun RowDivider(startInset: Dp = 72.dp, endInset: Dp = 0.dp) {
    val flux = LocalFluxColors.current
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = endInset),
        thickness = 0.5.dp,
        color = flux.divider,
    )
}

/**
 * COUI 17 分类标题——coui_preference_classification_title_layout。
 * 字号 12sp，字重 sans-serif-medium，左边距 16dp（category_marginStart）。
 */
@Composable
fun CategoryHeader(text: String, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = flux.labelSecondary,
        modifier = modifier.padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * COUI 17 assignment 行——coui_preference_assignment_in_right 布局的 Compose 等价。
 *
 * 精确参数（从 AXML 提取）：
 *   paddingStart = 32dp, paddingEnd = 32dp
 *   minHeight = 48dp
 *   标题左、值右对齐同行
 *
 * 值文字颜色使用 coui_preference_secondary_text_color。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = LocalFluxColors.current.labelSecondary,
) {
    val flux = LocalFluxColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = flux.labelPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * COUI 17 分段控件（COUITabView）。
 * 灰色轨道 + 白色滑块。
 */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flux = LocalFluxColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(flux.fieldBg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(if (selected) flux.card else Color.Transparent, label = "segbg")
            val fg by animateColorAsState(if (selected) flux.accent else flux.labelSecondary, label = "segfg")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}

/**
 * COUI 17 圆角方块图标容器——COUIRoundImageView 风格。
 *
 * COUI 17 给每个 preference 图标包了一层着色圆角方块。
 * couiType=1 表示圆角方形。
 * 尺寸 40dp × 40dp（视觉适配），圆角 coui_textinput_corner_radius = 10dp。
 */
@Composable
fun IconBox(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = LocalFluxColors.current.accent,
    bgColor: Color = LocalFluxColors.current.iconBoxBg,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(FluxRadius.IconBox))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * COUI 17 实心强调悬浮按钮。
 */
@Composable
fun ExtractFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.96f else 1f, label = "fab")
    Row(
        modifier = modifier
            .scale(scale)
            .height(54.dp)
            .clip(RoundedCornerShape(FluxRadius.Button))
            .background(flux.accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = flux.onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = flux.onAccent, textAlign = TextAlign.Center)
    }
}
