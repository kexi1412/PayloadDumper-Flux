<div align="center">

# Payload Dumper · Flux

[English](<README.md>) · **简体中文**

在 Android 本机提取固件镜像，支持**系统选择本地文件**和 HTTP OTA 下载直链。

![Version](https://img.shields.io/badge/Version-3.0.0-0080FF)
![Platform](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![UI](https://img.shields.io/badge/UI-COUI17%20%2F%20Compose-0080FF)
![Build](https://github.com/kexi1412/PayloadDumper-Flux/actions/workflows/build.yml/badge.svg)

</div>

## 3.0.0 更新

- **本地入口不再是输入框**：整块文件卡点击后打开系统文件选择器；没有路径编辑、键盘或粘贴入口。选择后显示文件名和大小，取消选择保留原文件。
- **来源独立记忆**：本地文档与网络 URL 分开保存，切换模式不串用；任务进行时锁定来源。
- **固件信息**：Android 版本、安全补丁、内核版本、构建指纹、文件名、整包大小、分区数及块大小。点击信息卡查看、选择并复制完整内容。
- **COUI17 资源化重构**：亮暗主题、原生蓝色、独立圆角卡片、右侧 assignment 信息、32→24sp 折叠标题、分段选择器、可滚动关于/设置对话框与可访问操作区。
- **修复**：HTTP Range 忽略/错位响应、编码文件名、长指纹挤占标题、超长分区列表、并发进度丢更新、嵌套同名镜像覆盖、换链取消竞态和同名固件误用续传记录；服务器无视 `Accept-Encoding: identity` 对每段 Range 单独 gzip/deflate 压缩时，也会按段解压并严格核对字节数；把 `Content-Encoding` 误写成 `utf-8` 等非压缩值时按原始字节处理。
- **统一版本**：`versionName=3.0.0`、`versionCode=30`，关于页与默认 User-Agent 跟随版本号。

完整记录见 [CHANGELOG](<CHANGELOG.md>)；设计证据见 [COUI17 资源参考](<docs/COUI17_REFERENCE.md>)。

## 信息读取依据

显示的是**所选固件**的信息，不是运行软件的手机版本。缺失、暂不支持或超出读取预算的字段明确显示未知。

| 信息 | 读取来源 |
|---|---|
| 文件名、整包大小 | SAF 文档/文件描述符，或最终 HTTP URL、Content-Disposition 和 Content-Range 总长度 |
| Android 版本 | OTA 目标指纹、protobuf postcondition、目标 build.prop、已知目标 SDK 映射；可选 boot header 兜底 |
| 安全补丁 | 目标 OTA 元数据 → 目标 build.prop → payload manifest → boot header 月份精度 |
| 构建指纹 | OTA 目标 metadata 或目标 build.prop；不使用 pre-build 或分区哈希 |
| 内核版本 | 目标 boot.img 的 kernel 区段中 `Linux version` 字符串，支持相应 gzip/LZ4 编码 |

支持 ZIP 内 `META-INF/com/android/metadata.pb` 和文本 metadata 的 STORED/DEFLATE 条目，校验边界、长度及 CRC。只读取明确路径的 build.prop 小条目，不下载或挂载完整 system/vendor 镜像寻找属性。

boot 信息读取按全部 `dst_extents` 正确映射全量操作，不照搬上游固定百分比采样。元数据每条目限 1MiB；boot 检查限 64MiB、单操作 16MiB、读取预算 64MiB，XZ 另有内存限制。缺少元数据、差分 boot、超预算或不支持的编码保留未知，不阻断正常提取。裸 payload 通常不能提供 Android 版本或指纹；boot header 只有年月时不编造补丁日期。

## 支持与使用

1. 本地模式点击文件卡，选择 `payload.bin`、OTA ZIP、UPDATE.APP 或支持的固件包；网络模式填写支持 HTTP Range 的直接下载链接。
2. 点击解析，查看固件信息和分区列表；可搜索镜像。
3. 点击单项提取或提取全部。首次写入下载目录时按应用提示授予文件访问权限。

**支持范围：**
- 全量 A/B OTA：裸 payload.bin，或包含 STORED payload.bin 的 OTA ZIP。
- 固件 ZIP 镜像、华为 UPDATE.APP、荣耀/华为嵌套 SD 升级包；同一内层 ZIP 多镜像可单次流式提取。块式 OTA 容器显示数据与列表文件，不自动转换为完整文件系统镜像。
- 并行处理 `REPLACE`、`REPLACE_BZ`、`REPLACE_XZ`、`ZERO`、`DISCARD`；需要旧镜像的增量操作不支持。
- 存在时校验操作 SHA-256；可选最终分区 SHA-256 校验，错误范围或解压长度不记为完成。
- 续传记录绑定完整 manifest、分区和块大小。旧无身份记录、不同固件、输出缺失或截断不能跳过操作；新链接须具有完全相同的 manifest/操作布局才复用进度。
- 中文/英文、自定义 User-Agent、各分区进度与状态。

默认输出 `Download/PayloadDumper/<固件>/`，内层 ZIP 使用各自子目录，避免不同来源的 `preload.img` 相互覆盖；输出叶子名经过净化和父目录检查。

## COUI17 复刻依据

根据用户提供的**设置 17.0.0** 样本核对色彩、尺寸及样式继承：强调色 `#0080FF`/`#1A8CFF`、COUI preference 卡片 12dp、列表标题 16sp、右侧信息 13sp。SettingsLib 的 20dp 是独立 v36 资源，不误称 COUI 外层卡片值。

项目使用自有图标和 Compose，不打包 OEM 字体、图片或运行库。圆弧圆角、行高、按压动画及至少 48dp 触摸区是明确记录的适配；静态资源核对不冒称实机模糊/弹簧动画像素级一致。详见 [资源 ID、样式链、哈希与限制](<docs/COUI17_REFERENCE.md>)。

## 构建与测试

JDK 21、Android SDK Platform 35 / Build-Tools 35、Gradle 8.10.2、AGP 8.8、Kotlin 2.1。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
# 未签名 release：app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew connectedDebugAndroidTest  # 需要 Android 设备或模拟器
```

Windows 使用 gradlew.bat，建议 ASCII 工程/SDK 路径（可用目录联接）。PowerShell 中带点的 Gradle 参数需整体加引号：

```powershell
& .\gradlew.bat '-Pandroid.overridePathCheck=true' testDebugUnitTest assembleRelease
```

[GitHub Actions](<.github/workflows/build.yml>) 执行单元测试、lint、编译和签名，产出 Artifact 与 Release。不同签名证书不能覆盖安装旧版；稳定签名配置见工作流。

回归测试覆盖目标/旧版本区分、protobuf-only 元数据、未知字段、CRC/截断/长度错误、boot/gzip/LZ4、多 extent 重建、来源隔离、HTTP 头与 Range、续传身份和提取边界。Compose 设备测试验证本地模式**没有任何文本编辑语义**，只有网络模式提供编辑器。

## 权限

通过 SAF 选择和读取本地文档只需要该 URI 的读授权，不需要所有文件访问权限。写入下载/自选文件系统目录仍需 `MANAGE_EXTERNAL_STORAGE`。软件不需要 GitHub 令牌、小米账号或 updater API 凭据。

## 致谢

- **[YuKongA（yukong）/ Updater-KMP](<https://github.com/YuKongA/Updater-KMP>)**：感谢 OTA 元数据、内核检查思路及固件信息展示参考。核对提交 `73db06dc98d04fe33deebbc6e34b260ed8269aea`；本项目独立实现，不引入小米专用 API、ODM 优先策略或固定百分比采样。
- **xyiguanle**：payload 分析源码。
- **[rcmiku/Payload-Dumper-Compose](<https://github.com/rcmiku/Payload-Dumper-Compose>)**：前身与功能流程参考。
- **payload-dumper-c**：无状态定位读、并行与分散写设计参考。
- Jetpack Compose、OkHttp、Apache Commons Compress、tukaani-xz、aircompressor、protobuf。

## 许可

Apache-2.0，见 [LICENSE](<LICENSE>)。update-metadata protobuf 来自 AOSP。Updater-KMP 同样为 Apache-2.0；本轮没有复制上游源码或 OEM 二进制资产。
