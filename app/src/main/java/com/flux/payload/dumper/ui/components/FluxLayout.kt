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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * ColorOS 17 卡片列表背景：柔和暖灰底色（coui_color_background_with_card）。
 * COUI 17 相比 16 去掉了所有玻璃质感，使用更纯净的平面表面，让白色分组卡片
 * 读作原生设置组。
 */
@Composable
fun FluxBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

/**
 * COUI 17 分组圆角卡片——整个列表的基础构件。子项在 Column 中堆叠；
 * 行之间用 [RowDivider] 获得 COUI 的内嵌发丝分割线。
 *
 * COUI 17 相比 16：圆角从 17dp 增大到 22dp，暗色模式加了极淡轮廓。
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
 * 卡片内行之间的内嵌发丝分割线（coui_color_divider）。
 * COUI 17 的分割线更淡、更细，几乎不可见。
 */
@Composable
fun RowDivider(startInset: Dp = 24.dp, endInset: Dp = 0.dp) {
    val flux = LocalFluxColors.current
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = endInset),
        thickness = 0.5.dp,
        color = flux.divider,
    )
}

/**
 * 卡片组上方的小灰字标题（support_preference_category_title_size 13sp）。
 * COUI 17 间距更宽松：top 从 18dp 增到 22dp。
 */
@Composable
fun CategoryHeader(text: String, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = flux.labelSecondary,
        modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 10.dp),
    )
}

/**
 * COUI 17 assignment 行——标题左 / 值右对齐同行。
 * 这是 COUI 17 preference_assignment_in_right 布局的 Compose 等价物。
 * 值文本使用 labelMedium（14sp Medium）而非旧的 bodyMedium。
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
            .heightIn(min = 60.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
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
 * COUI 17 分段控件（COUITabView）：灰色轨道 + 白色滑块。
 * 圆角增大到 12dp，滑块圆角 9dp，轨道 padding 加宽。
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
 * COUI 17 图标圆角方块容器——COUIRoundImageView 的 Compose 等价物。
 * 给图标包一层柔和的着色圆角方块背景，是 COUI 17 相比 16 最显著的视觉变化。
 */
@Composable
fun IconBox(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = LocalFluxColors.current.accent,
    bgColor: Color = LocalFluxColors.current.iconBoxBg,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(FluxRadius.IconBox))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * COUI 17 实心强调悬浮按钮（COUIFloatingButton）。
 * 圆角增大到 16dp，高度保持 54dp。
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
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = flux.onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = flux.onAccent, textAlign = TextAlign.Center)
    }
}
