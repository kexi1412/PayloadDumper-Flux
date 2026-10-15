package com.flux.payload.dumper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ColorOS 17 (COUI 17) 语义化色彩 token。
 *
 * 所有数值精确提取自 设置_17.0.0.apk 的 resources.arsc（androguard 4.1.4，
 * _extract_cou17.py，default config + _light/_dark 变体）。
 *
 * 资源名映射：
 *   coui_color_background_with_card      → 页面底色
 *   coui_color_background_elevated       → 卡片表面（浅色 #FFFFFF / 暗色 #1E1E1E）
 *   coui_color_background_elevatedWithCard → 卡片表面（与背景同色 #F0F1F2 浅）
 *   coui_color_divider                   → 行间发丝分割线
 *   coui_color_label_primary             → 行标题 ≈90%
 *   coui_color_label_secondary           → 摘要 ≈54%
 *   coui_color_label_tertiary            → 禁用/提示 ≈26%
 *   coui_color_label_quaternary          → 极淡 ≈8%（新增轮廓用）
 *
 * 关键发现：COUI 17 的背景色和 COUI 16 一样是 #F0F1F2，不是更暖的值。
 * 分割线也是同样的 #1F000000。COUI 17 的主要视觉变化在于更大的圆角
 * 和 IconBox 图标容器，而非配色。
 *
 * Accent 保持 Aqua（项目品牌色）——ColorOS 是可主题化的，aqua 色调
 * 仍然读作原生 OS 表面。
 */
@Immutable
data class FluxColors(
    val card: Color,              // coui_color_background_elevated（分组卡片表面）
    val cardPressed: Color,       // 行按下状态
    val cardOutline: Color,       // coui_color_label_quaternary（卡片边缘轮廓）
    val divider: Color,           // coui_color_divider
    val labelPrimary: Color,      // coui_color_label_primary
    val labelSecondary: Color,    // coui_color_label_secondary
    val labelTertiary: Color,     // coui_color_label_tertiary
    val accent: Color,            // 项目主题色
    val onAccent: Color,          // 强调色上的内容
    val accentContainer: Color,   // 着色填充（列表行按钮）
    val success: Color,           // coui_color_label_theme_green
    val warning: Color,           // coui_color_label_theme_orange
    val danger: Color,            // coui_color_label_theme_red
    val fieldBg: Color,           // coui_color_background_elevatedWithCard（输入框容器）
    val iconBoxBg: Color,         // IconBox 着色背景（accent 6% tint）
    val iconLocal: Color,         // "本地文件" 前导图标色
    val iconUrl: Color,           // "网络链接" 前导图标色
)

val LocalFluxColors = staticCompositionLocalOf { LightFlux }

// ---- 浅色 —— 精确值来自 APK ARSC ----
// coui_color_background_with_card_light = #FFF0F1F2
// coui_color_background_elevated_light  = #FFFFFFFF
// coui_color_divider_light              = #1F000000
// coui_color_label_primary_light        = #E6000000
// coui_color_label_secondary_light      = #8A000000
// coui_color_label_tertiary_light       = #42000000
// coui_color_label_quaternary_light     = #14000000
// coui_color_label_theme_red_light      = #FFDB382C
// coui_color_label_theme_green_light    = #FF00990F
// coui_color_label_theme_orange_light   = #FFEB6E00
val LightBackground = Color(0xFFF0F1F2)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F1F2)         // elevatedWithCard
val LightPrimary = Color(0xFF0E9BD8)                // 项目 aqua accent
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xE6000000)
val LightOnSurfaceVariant = Color(0x8A000000)
val LightOutline = Color(0x14000000)                // label_quaternary
val LightError = Color(0xFFDB382C)                  // theme_red

val LightFlux = FluxColors(
    card = Color(0xFFFFFFFF),                        // background_elevated
    cardPressed = Color(0xFFE6E6E6),
    cardOutline = Color(0x14000000),                 // label_quaternary
    divider = Color(0x1F000000),                     // coui_color_divider
    labelPrimary = Color(0xE6000000),                // label_primary
    labelSecondary = Color(0x8A000000),              // label_secondary
    labelTertiary = Color(0x42000000),               // label_tertiary
    accent = Color(0xFF0E9BD8),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0x1A0E9BD8),
    success = Color(0xFF00990F),                      // theme_green
    warning = Color(0xFFEB6E00),                      // theme_orange
    danger = Color(0xFFDB382C),                       // theme_red
    fieldBg = Color(0xFFF0F1F2),                      // elevatedWithCard
    iconBoxBg = Color(0x0F0E9BD8),
    iconLocal = Color(0xFF0E9BD8),
    iconUrl = Color(0xFF00990F),
)

// ---- 暗色 —— 精确值来自 APK ARSC ----
// coui_color_background_with_card_dark = #FF000000
// coui_color_background_elevated_dark  = #FF1E1E1E
// coui_color_divider_dark              = #33FFFFFF
// coui_color_label_primary_dark        = #E6FFFFFF
// coui_color_label_secondary_dark      = #8AFFFFFF
// coui_color_label_tertiary_dark       = #4DFFFFFF
// coui_color_label_quaternary_dark     = #26FFFFFF
// coui_color_label_theme_red_dark      = #FFFF6C61
// coui_color_label_theme_green_dark    = #FF24B232
// coui_color_label_theme_orange_dark   = #FFF08222
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1E1E1E)                  // background_elevated_dark
val DarkSurfaceVariant = Color(0xFF1E1E1E)           // elevatedWithCard_dark
val DarkPrimary = Color(0xFF4AC4E8)
val DarkOnPrimary = Color(0xFF000000)
val DarkOnSurface = Color(0xE6FFFFFF)
val DarkOnSurfaceVariant = Color(0x8AFFFFFF)
val DarkOutline = Color(0x26FFFFFF)                  // label_quaternary_dark
val DarkError = Color(0xFFFF6C61)                    // theme_red_dark

val DarkFlux = FluxColors(
    card = Color(0xFF1E1E1E),                         // background_elevated_dark
    cardPressed = Color(0x33FFFFFF),
    cardOutline = Color(0x26FFFFFF),                  // label_quaternary_dark
    divider = Color(0x33FFFFFF),                      // coui_color_divider_dark
    labelPrimary = Color(0xE6FFFFFF),                 // label_primary_dark
    labelSecondary = Color(0x8AFFFFFF),               // label_secondary_dark
    labelTertiary = Color(0x4DFFFFFF),                // label_tertiary_dark
    accent = Color(0xFF4AC4E8),
    onAccent = Color(0xFF000000),
    accentContainer = Color(0x264AC4E8),
    success = Color(0xFF24B232),                      // theme_green_dark
    warning = Color(0xFFF08222),                      // theme_orange_dark
    danger = Color(0xFFFF6C61),                       // theme_red_dark
    fieldBg = Color(0xFF1E1E1E),                      // elevatedWithCard_dark
    iconBoxBg = Color(0x1A4AC4E8),
    iconLocal = Color(0xFF5BC8E8),
    iconUrl = Color(0xFF24B232),
)
