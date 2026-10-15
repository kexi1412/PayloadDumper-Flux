package com.flux.payload.dumper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ColorOS 17 系统字体阶梯。
 *
 * 数值提取自 设置_17.0.0.apk 的 resources.arsc：
 *   coui_appbar_title_expanded_text_size = 32.0dip（展开折叠工具栏大标题）
 *   coui_appbar_title_collapsed_text_size = 24.0dip（折叠后标题）
 *   coui_toolbar_title_text_size = 16.0dip（普通工具栏标题）
 *   coui_title_text_size = 20.0sp（区域标题）
 *   coui_dialog_title_text_size = 16.0sp（对话框标题）
 *   coui_btn_text_size = 16.0sp（按钮标签）
 *   coui_textview_text_size = 15.0sp（通用文本）
 *   coui_textview_content_text_size = 12.0sp（说明文本）
 *   coui_toolbar_subtitle_text_size = 13.0dip（副标题）
 *   coui_toptips_view_default_text_size = 13.0sp（提示）
 */
val FluxTypography = Typography(
    // 展开/折叠工具栏大标题（coui_appbar_title_expanded_text_size 32dp）
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    // 折叠后工具栏标题（coui_appbar_title_collapsed_text_size 24dp）
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    // 区域标题（coui_title_text_size 20sp）
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    // 对话框标题（coui_dialog_title_text_size 16sp）
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // 列表行标题（coui_textview_text_size 15sp ≈ preference 标题）
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // 摘要 / 值文本（coui_textview_content_text_size 12sp）
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // 填充按钮标签（coui_btn_text_size 16sp）
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    // assignment 值 / 副标题（coui_toolbar_subtitle_text_size 13dp）
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    // 分类标题 / 提示（coui_toptips_view_default_text_size 13sp）
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
)
