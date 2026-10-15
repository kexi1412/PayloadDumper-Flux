package com.flux.payload.dumper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * ColorOS 17 (COUI 17) 圆角体系。
 *
 * 数值提取自 设置_17.0.0.apk 的 resources.arsc（androguard 4.1.4）：
 *   - settingslib_preference_corner_radius = 20dp（偏好设置分组容器）
 *   - coui_preference_card_radius = 12dp（卡片内嵌组件）
 *   - coui_dialog_background_corner_radius = 24dp（对话框）
 *   - coui_corner_radius = 8dp（通用圆角）
 *   - coui_snack_bar_radius = 16dp
 *   - search_bar_corner_radius = 28dp
 *
 * 来源：_extract_cou17.py 对 ARSC 的精确提取（default config）。
 */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),    // coui_corner_radius
    small = RoundedCornerShape(12.dp),        // coui_preference_card_radius
    medium = RoundedCornerShape(16.dp),       // coui_snack_bar_radius
    large = RoundedCornerShape(20.dp),        // settingslib_preference_corner_radius
    extraLarge = RoundedCornerShape(24.dp),   // coui_dialog_background_corner_radius
)

object FluxRadius {
    val Card = 20.dp       // settingslib_preference_corner_radius（偏好分组容器）
    val Inner = 12.dp      // coui_preference_card_radius（卡片内嵌组件）
    val IconBox = 10.dp    // COUIRoundImageView 圆角方块（视觉推断）
    val Field = 12.dp      // coui_preference_card_radius（输入框与内嵌控件一致）
    val Button = 16.dp     // coui_snack_bar_radius（填充按钮）
    val Dialog = 24.dp     // coui_dialog_background_corner_radius
    val Pill = 100.dp      // 完全圆角（搜索框 search_bar_corner_radius=28dp 用于搜索，分段滑块用全圆）
}
