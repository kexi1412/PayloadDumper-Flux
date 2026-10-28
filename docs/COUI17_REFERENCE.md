# COUI17 样本资源核对与 Compose 映射

## 结论与证据等级

基于用户提供的 [coloros17apk.zip](<C:/Users/小杰/Downloads/coloros17apk.zip>)，选择 **设置 17.0.0** 进行资源级取证，没有全量反编译 ZIP，也没有反编译其 14 个 dex。主题与组件依据下列资源证据独立实现。

- **已验证**：资源标识、数值、限定配置、选择器和部分布局/样式继承链。
- **没有验证**：设备实时主题覆盖、实际页面截图、COUI 运行时平滑圆角/模糊/动效实现、独立 COUI 库版本元数据。因此“COUI17”在本文指这份 17.0.0 设置样本里的 COUI 资源，不是对所有 ColorOS 17 构建的普遍断言。
- `settingslib_preference_corner_radius=20dp` 确实存在，但配置是 **v36**；没有证据支持“COUI 外层分组 20dp、内嵌卡 12dp”这一旧注释。此次选用明确命名的 `coui_preference_card_radius=12dp`。
- 标题和摘要必须沿样式继承验证：实际 preference 标题为 **16sp / sans-serif-medium**，右侧 assignment / summary 为 **13sp**；不能因旧 `support_preference_summary_size=12sp` 仍存在就把当前样式写成 12sp。

## 1. 样本清单与身份

ZIP 大小：400,906,702 bytes。归档实际仅有以下四个成员，没有独立“系统更新”APK。

| ZIP 成员 | 解压大小 bytes |
|---|---:|
| `信息_17.62.50.apk` | 89,470,476 |
| `时钟_17.6.94.apk` | 49,545,882 |
| `笔记_17.1.17.apk` | 53,811,764 |
| `设置_17.0.0.apk` | 208,077,894 |

设置样本：10,578 个 ZIP 条目；`resources.arsc` 50,564,776 bytes；`AndroidManifest.xml` 615,556 bytes；14 个 dex（未反编）。

`aapt2 dump badging` 验证：`com.android.settings`，`versionCode=17000000`，`versionName=17.0.0`，`minSdk=33`，`targetSdk=37`，`compileSdkVersion=37`，platform build name `17`。这证明 APK 自身的版本声明，不证明真机固件版本或签名可信链。

```text
ZIP SHA-256
8ffca7670686b5d5791f175412aa7e22d5e8a883449bee15b9a021493ce1b967

设置 APK SHA-256
 efb7ded0a36d9f05a2cb4e43bda9150ca7c9297bf7016b2ce0b2a9e815dd1041

resources.arsc SHA-256
780fada415908fad3b512f1d72636cf415e909b123166d3e2fe58b97dfffd5c7
```

可复查证据：

- [资源表转储](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt>)
- [应用版本转储](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#badging.txt>)
- [哈希与选择性布局清单](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#metadata.json>)
- [归档清单与图片尺寸](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#sample-inventory.json>)

以上文件收录于 Release 的 COUI17-evidence.zip；ZIP 链接的片段标注其内部路径。证据不打包进 APK，工作区临时副本在交付后清理。

## 2. 色彩：保留 ARGB 和限定配置

所有 `#AARRGGBB` 均保留原始 alpha，不能把透明色直接当成不透明十六进制 RGB。

| 资源名 / ID | 默认 / 浅色 | night / 深色 | Compose 映射 |
|---|---|---|---|
| `coui_color_background_with_card_light` / `0x7f0603f2`；`_dark` / `0x7f0603f1` | `#FFF0F1F2` | `#FF000000` | 页面背景，显式选 light/dark 资源 |
| `coui_color_background_elevated` / `0x7f0603e6` | `#FFFFFFFF` | `#FF1E1E1E` | 对话框等抬升表面 |
| `coui_color_card` / `0x7f0603fd` | `#FFFFFFFF` | `#1AFFFFFF` | 卡片；深色为透明白叠加，不等同 elevated |
| `coui_color_card_pressed` / `0x7f060403` | `#FFE6E6E6` | `#33FFFFFF` | 卡片按压 token |
| `coui_color_primary_blue` / `0x7f0604e7` | `#FF0080FF` | `#FF1A8CFF` | 主强调色，替代旧项目 aqua |
| `coui_color_secondary_blue` / `0x7f06052c` | `#260080FF` | `#401A8CFF` | 着色按钮/项目自有图标容器 |
| `coui_color_label_on_color` / `0x7f0604ab` | `#FFFFFFFF` | `_dark` 也是白 | 强调背景上的内容 |
| `coui_color_label_primary` / `0x7f0604ae` | `#E6000000` | `#E6FFFFFF` | 主文字 |
| `coui_color_label_secondary` / `0x7f0604b7` | `#8A000000` | `#8AFFFFFF` | 次文字 |
| `coui_color_label_tertiary` / `0x7f0604bd` | `#42000000` | `#4DFFFFFF` | 三级/禁用文字 |
| `coui_color_divider` / `0x7f06046f` | `#1F000000` | `#33FFFFFF` | 可选内部分割线，不代表每个卡片必须描边 |
| `coui_color_segment_button_background` / `0x7f060541` | `#0F000000` | `#1FFFFFFF` | 分段轨道 |
| `coui_color_segment_button_indicator` / `0x7f060542` | `#B2FFFFFF` | `#33FFFFFF` | 分段选中滑块 |
| `coui_button_main_disable` / `0x7f060378` | `#4D0080FF` | `#4D1A8CFF` | 主按钮禁用填充 |
| `coui_button_main_disable_text` / `0x7f060379` | `#8AFFFFFF` | `#29FFFFFF` | 主按钮禁用文字 |

**重要反例**：通用 `coui_color_background_with_card`（`0x7f0603f0`）的默认值是 `#FFF0F1F2`，但其 `night` 条目实际为 `#FFFFFFFF`；与此同时 `_dark` 是 `#FF000000`。不能写“通用资源 night 自动为黑”的注释。真实页面还可能通过主题 attr、RRO 或代码选取不同入口。本项目显式选择 `_light/_dark` 语义，是有说明的映射。

来源区段：[背景/卡片](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L21472-L21549>)、[文字](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L21936-L21986>)、[蓝色](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L22074-L22094>)、[分段](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L22284-L22289>)。

## 3. 圆角、卡片、间距、列表

| 资源 / ID | 验证值 | 使用说明 |
|---|---|---|
| `coui_preference_card_radius` / `0x7f070680` | 12dp | 本项目卡片基线 |
| `coui_preference_card_margin_horizontal` / `0x7f07067e` | 16dp | 常规卡片外边距；tiny 为 12dp |
| `coui_preference_category_margintop_large/small` | 16dp / 12dp | 分组节奏候选 |
| `coui_preference_fragment_item_padding_side` | 16dp | preference fragment 侧边 padding |
| `coui_preference_group_padding` | 4dp | 分组内 padding token |
| `coui_preference_icon_margin_right` | 16dp | 图标和内容间距 token |
| `coui_preference_divider_default_horizontal_padding` | 32dp | 分割线水平内缩 token |
| `support_preference_min_height` / `0x7f071422` | 48dp | 已在 assignment 布局中验证被引用 |
| `support_preference_title_padding_start/end` / `0x7f071432`、`0x7f07142f` | 32dp / 32dp | assignment 根布局引用；不要不加判断叠到外层 16dp 卡边距上 |
| `support_preference_text_content_padding_top/bottom` | 10dp / 10dp | 文本容器 spacing token |
| `support_preference_margin_between_line` | 2dp | 行间距 token |
| `settingslib_preference_corner_radius` / `0x7f071201` | **v36: 20dp** | 不应自动解释成 COUI 外层分组的尺寸 |

[assignment 布局证据](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#coui_preference_assignment_in_right-xmltree.txt#L3-L12>)：根为 `COUICustomListSelectedLinearLayout`，宽 match-parent、高 wrap-content、minHeight 48dp、start/end padding 32dp。右侧 assignment 是 `TextView`，gravity END，`maxLines=2`，style `TextAppearance.COUI.List.Assignment.End`，见[同一布局 85–95 行](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#coui_preference_assignment_in_right-xmltree.txt#L85-L95>)。

Compose 实现：`InfoRow` 标题和值分别分配 `1f : 1.3f` 的有界宽度，值最多两行省略，避免长指纹先按固有宽度测量而把标题挤没；保留完整语义文本，复制/查看完整值由调用侧交互负责。行内部采用 16dp，与页面 16dp 外边距组成对齐关系，这一组合是项目适配，不声称等同 OEM 原父布局。

本项目独立卡片不自动插入分割线；`RowDivider` 仅供明确需要的分组场景调用。没有静态证据支持所有卡片包围一圈 0.5dp 轮廓，因此移除这一旧行为。

### 平滑圆角限制

样本同时包含 `coui_round_corner_m=12dp`、`m_radius=9dp`、`m_weight=0.99`，及 `_radius_normal_16_1=15dp`、`_radius_large_16_1=17dp`；L、XL、XXL 也有各自 radius/weight 家族。不能把所有 radius token 混为同一个圆角值，或假定数值越大版本越新。Compose `RoundedCornerShape` 仅作圆弧近似，不复刻 COUI 平滑路径/曲率权重。

来源：[卡片/圆角](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L31016-L31191>)、[SettingsLib v36](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L36759-L36762>)。

## 4. 字体：尺寸必须沿继承链核对

| 真实链 / 资源 | 验证值 | Compose |
|---|---|---|
| `COUIPreferenceTitleStyle → TextAppearance.COUI.List.Title → couiTextAppearanceHeadline6` | 16sp；`sans-serif-medium` | `titleMedium` 16sp / Medium |
| `TextAppearance.COUI.List.Assignment.End → TextAppearance.COUI.Preference.Summary → couiTextAppearanceArticleBody` | **13sp** | 信息值/摘要 `bodyMedium` 13sp |
| `couiTextAppearanceHeadline1` | 32sp；`textFontWeight=600` | 展开标题 32sp / SemiBold |
| `couiTextAppearanceHeadline3` | 24sp；`textFontWeight=600` | 收起标题 24sp / SemiBold |
| `couiTextAppearanceButtonL` | 16sp；`sans-serif-medium` | 主按钮 16sp / Medium |
| `couiTextAppearanceButton` | 14sp；`sans-serif-medium` | 小按钮 14sp / Medium |
| `coui_dialog_title_text_size` | 16sp | 对话框标题基线；SemiBold 为项目层级选择 |
| `support_preference_category_title_size` | 12sp | 本项目分类标题选择；另有 `category_layout_tv_title=14sp`，不能说所有 OEM 分类标题都为 12sp |

[样式继承](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L496290-L496329>)及[字体实际值](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L503089-L503162>)。

`coui_appbar_title_expanded/collapsed_text_size` 另外存在 32dp/24dp 的实现尺寸，不应直接写成“资源就是 sp”。这里也找到了上述 32sp/24sp 字体样式，Compose 保留 sp 字体缩放。

`coui_spacing_multiplier_body_s` 默认 1.1、v35 为 1.1739；`headline_xs` 默认 1.05、v35 为 1.158。TextView 的行间距倍数作用于字体度量，**不等于字号乘倍数后的 Compose lineHeight**。项目的 40/30/22/18sp 等 lineHeight 是可读性适配，未当成资源原值。

不复制 OEM 字体。系统 SansSerif 在非 Oplus 设备上并非 Oplus Sans，字体回退、中文形状、字重插值和基线仍需真机对照。

## 5. 按钮、分段选择器、底栏、对话框

| 组件 / 资源 | 原始值 | 本项目映射与边界 |
|---|---|---|
| `coui_btn_large_height_min / width_min` | 44dp / 132dp | 主按钮至少 48dp 触摸高度，内容可增高 |
| `coui_btn_drawable_radius_large` | 21.5dp | 主按钮圆弧近似 |
| `coui_btn_small_height_min / width_min` | 28dp / 52dp | 小按钮触摸高度扩到 48dp，不声称 OEM 原本就是 48dp |
| `coui_btn_drawable_radius_small` | 13dp | 小按钮形状基线 |
| `coui_btn_padding_horizontal / vertical` | 12dp / 8dp | token 证据；项目加大触摸空间时不机械锁死此 padding |
| `coui_button_press_black_alpha` | 默认 0.12；night 0.20 | 按压色遮罩；按压缩放动画曲线为项目自定义 |
| `style/SegmentButton` | **height 40dp**；padding 引用 4dp | 常规视觉轨道与 48dp 触摸带分离 |
| `style/SegmentButton.Tiny` | height 32dp；padding 2dp | 非默认采用 |
| `coui_segment_min_height / min_width` | 28dp / 52dp | 子控件最小 token，不能称分段总高 28dp |
| `coui_segment_btn_padding_horizontal` | 12dp | 标签横向内边距 |
| `coui_segment_shadow_offset / radius` | 2dp / 1dp | 仅静态 token，未复刻其渲染算法 |
| `coui_dialog_background_corner_radius` | 24dp | 对话框圆角；旋转变体 12dp 未自动推断为必须采用 |
| `coui_dialog_max_width / max_height` | 392dp / 632dp | 内容上界，受可用窗口约束 |
| `coui_dialog_layout_margin_horizontal` | 16dp | 屏幕边缘 margin |
| `coui_dialog_content_max_width` | 360dp | 另一个内容宽度 token，不和 392dp 容器强行混同 |
| `coui_dialog_button_height` | 50dp | 原 token；本项目按钮采用可增长 minHeight48 |
| `coui_bottom_sheet_bg_top_corner_radius` | 引用 `coui_round_corner_xxxl=28dp` | 供后续 BottomSheet 映射 |
| bottom sheet bottom corner | 默认 28dp；w600dp/land 0dp；small 16dp | 有配置差异，不把顶底四角永久写成同一数值 |
| `coui_bottom_floating_navigation_bar_height` | 56dp | 底部浮动导航候选，不等同普通 BottomAppBar |
| `coui_bottom_floating_navigation_bar_item_height` | 48dp | 条目高度 |
| `coui_bottom_floating_navigation_bar_padding_horizontal` | 16dp | 水平 spacing |
| `coui_bottom_floating_navigation_bar_menu_to_screen_navi_distance` | 8dp | 与系统导航距离 token；运行时仍需 inset 处理 |

来源：[按钮](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L29339-L29391>)、[分段尺寸](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L31444-L31465>)、[分段样式](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L492492-L492506>)、[对话框](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L29883-L29960>)、[底栏/底弹层](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#resources.txt#L29261-L29336>)。

交互实现：按钮提供 `Role.Button`，分段容器提供 `selectableGroup`、每项 `Role.Tab`/selected/enabled；禁用时不触发回调。对话框正文可滚动、通常固定动作区，兼容窄屏/大字和 IME；可用高度低于 280dp 时改为整体滚动，避免动作区本身挤出窗口。固定高度改为最小高度，但极端字体缩放与实际窗口仍应运行截图测试，不宣称已真机验证。

## 6. 选择器证明与截图资源

[标题颜色选择器](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#coui_preference_title_color-xmltree.txt>)：disabled 引用 `couiColorLabelTertiary`，默认引用 `couiColorLabelPrimary`。

[次文字选择器](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#coui_preference_secondary_text_color-xmltree.txt>)：disabled 为 `couiColorLabelTertiary`；pressed、selected、默认均引用 `couiColorSecondNeutral`。这也证明不能只靠截图颜色猜状态语义。

选出的图片只保留在分析目录，不拷入项目：

| 图片 | APK 内路径 | 解码尺寸 |
|---|---|---|
| [17 版本 OTA 卡片背景](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#reference-images/device_ota_card_bg_17.webp>) | `res/drawable-xxhdpi-v4/device_ota_card_bg_17.webp` | 656×656 |
| [无障碍按钮预览](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#reference-images/accessibility_button_preview_base_oplus.png>) | `res/drawable/accessibility_button_preview_base_oplus.png` | 984×792 |
| [关怀模式预览](<https://github.com/kexi1412/PayloadDumper-Flux/releases/download/v3.0.0/COUI17-evidence.zip#reference-images/care_mode_welcome_preview.webp>) | `res/drawable/care_mode_welcome_preview.webp` | 568×485 |

尺寸由 Pillow 解码验证。当前分析模型的图像输入不可用，**没有视觉检查这些图片**；文件名只说明候选用途，不将它们当作当前设置主页或系统更新页面实机截图。ZIP 内没有系统更新独立 APK，但设置样本确有 `_17` OTA 背景资源。

## 7. 本轮实现范围与集成接口

以下主题和组件采用资源映射；固件解析、状态管理和使用方法另见项目 README。

- `ui/theme/{Color,Shape,Type,Theme}.kt`：真实蓝、卡片/分段/禁用状态 palette，清除不实来源注释，补齐 Material 语义映射以免默认紫色漏出。
- `ui/components/{FluxLayout,FluxComponents,Dialogs}.kt`：信息行测量约束、分段选择语义、按钮与对话框适配。
- `InfoRow(label, value, valueColor=..., modifier=Modifier, onClick:(()->Unit)?=null, onClickLabel:String?=null)`：原参数顺序保留，新增参数均可选。
- `SegmentedToggle(..., modifier=Modifier, enabled:Boolean=true)`：新增参数位于末尾；旧调用兼容。
- About 引用 `about_whats_new_title`、`about_whats_new_body`、`credits_updater_title`，新增真实上游链接 `https://github.com/YuKongA/Updater-KMP`。
- `FluxColors` 增加 `segmentTrack`、`segmentIndicator`、`buttonDisabled`、`buttonOnDisabled`、`pressMask`。没有复制 OEM 图标、字体、完整二进制或 drawable 文件到项目。

源码检查、Release/Debug 编译、48 个 JVM 单元测试及 lint 已通过；设备界面测试 APK 已编译，未连接设备执行。

## 8. 复核方法与清理

Python 使用用户指定运行时，aapt2 从已有 apktool jar 的 `prebuilt/windows/aapt2_64.exe` 提取，版本输出 `2.19-eng.000000`。核心命令：

```powershell
# 先用 zipfile 列 ZIP，只取 设置_17.0.0.apk；不要全解压四个 APK。
aapt2 dump badging settings.apk
aapt2 dump resources settings.apk
aapt2 dump xmltree settings.apk --file res/layout/coui_preference_assignment_in_right.xml
aapt2 dump xmltree settings.apk --file res/color/coui_preference_title_color.xml
```

PowerShell 重定向在本环境产生 UTF-16，已转换为 UTF-8 证据文本。aapt2 对个别负 dimension 输出 `16777215dp` 等值存在有符号解码问题；本文没有采用这些异常值，也没有把它们当作正常尺寸。

分析工作目录：本次独立分析目录。完成后清理抽出的设置 APK、aapt2 执行文件和重复 UTF-16 转储；UTF-8 证据、JSON 清单、取证脚本及三张参考图片导出为 COUI17-evidence.zip，原临时目录清理。原 ZIP 保持不变。

未做/复刻限制：未解完整 dex、未模拟 COUI 主题动态覆盖、未还原 OEM 平滑圆角和弹性/模糊 shader、未确认所有 library token 都由当前页面使用。项目采用自有资产与 Compose 近似实现，不引入 OEM 运行库。
