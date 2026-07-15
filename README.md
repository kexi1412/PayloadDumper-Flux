# Payload Dumper · Flux

从 Android A/B（`payload.bin`）**全量 OTA** 中并行提取分区镜像的 Android 应用。界面参考 **ColorOS 16「流体 / Flux」** 设计语言，提取引擎从零重写，深度参考 [`payload-dumper-c`](https://github.com/) 的线程池模型。

<p align="center">
  <em>Jetpack Compose · Material 3 · Kotlin · 云端 GitHub Actions 构建</em>
</p>

## 功能

- **两种来源**：本地 `payload.bin` / OTA 压缩包，或直接填 OTA 下载**直链**（HTTP Range 流式解析，无需先下整包）。
- **真·并行提取**：每个 install operation 是独立任务，各自按绝对偏移读取、按绝对位置写入，最多 `CPU 核数` 个并发。摆脱了旧实现「全局锁 + 单游标」导致的伪并行。
- **提取后 SHA-256 校验**：对每个分区镜像重新计算哈希并与 manifest 比对，界面给出 ✓ / ✗ 徽章。
- **断点续传（网络提取）**：大分区下载慢、OTA 直链有时效，下到一半链接失效时**不丢进度**——每个已完成的 operation 记录在 `<分区>.img.progress` sidecar（可跨 App 重启），重来时只补没下完的部分。
- **失效强制换链 + 二次校验**：网络中断即弹出强制对话框要新链接；提交后**先比对该分区 manifest 的 SHA-256**确认是同一个 ROM 才续传，防止不同链接混包污染半成品镜像；下完仍做最终整镜像 SHA-256 校验。
- **多分区一键提取**、分区搜索过滤、进度条与状态。
- **自定义 User-Agent**（可选）。
- 亮 / 暗双主题，自适应图标，中文 / 英文本地化。

## 支持的 operation 类型

标准全量 OTA 使用的四类：`REPLACE`、`REPLACE_BZ`(bzip2)、`REPLACE_XZ`(xz/lzma)、`ZERO`/`DISCARD`。
解压数据会按分区的**所有 `dst_extents` 分散写入**（不是只写第一个 extent），与 `payload-dumper-c` 行为一致。

对于**未识别**的 operation 类型，引擎会嗅探数据块魔数（zstd `28 B5 2F FD` / xz / bzip2）自动选择解码器——这样即便个别 OEM 用了非标准的 zstd 压缩块也能兼容，且无需臆测枚举号。

> 不支持增量（delta）OTA（`SOURCE_COPY`/`SOURCE_BSDIFF`/`BROTLI_BSDIFF` 等），遇到会给出明确提示。请使用全量包。

## 架构

```
core/
  PayloadSource.kt   无状态定位读接口 + FileSource(FileChannel 定位读) + HttpSource(每读一次独立 Range 请求)
  ZipLocator.kt      在 OTA zip 中央目录里定位 payload.bin 偏移（含 ZIP64），文件/URL 同一套路径
  PayloadParser.kt   解析 CrAU 头（大端）+ protobuf manifest
  Decompressor.kt    xz / bzip2 / zstd（+ 魔数嗅探兜底）
  PayloadExtractor.kt 并行提取引擎：信号量限流、定位写、分散 extents、SHA-256 校验
  Net.kt             OkHttp 客户端（注入自定义 UA / Header）
model/    UI 与引擎共享的数据模型
viewmodel/ DumperViewModel：解析 / 提取 / 状态编排（StateFlow）
ui/       ColorOS 16 Flux 主题 + 组件 + 主界面
```

**关键设计**：`PayloadSource` 的读取全部是**无状态定位读**（`readAt(offset, len)`），对应 `payload-dumper-c` 的 `source_interface.h`（clone / read-at-offset / destroy）。正是它让「多 worker 并发提取」在文件与网络两种来源上都成立。

## 构建（GitHub Actions，推荐）

本仓库自带 `.github/workflows/build.yml`：在 `ubuntu-latest`(x86_64) 上装 JDK 21 + Android SDK，`assembleRelease` 后在工作流内生成临时 keystore 签名，产出可直装 APK。

- 推送到 `main` 或手动触发（Actions → Build APK → Run workflow）即可。
- 产物：Actions run 的 **Artifact**（`PayloadDumper-Flux-apk`），同时自动发一个 **Release**。
- 零 secrets。注意：每次构建生成的是**新签名密钥**，覆盖安装旧版需先卸载。

## 本地构建

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
# 自行 zipalign + apksigner 签名后安装
```

需求：JDK 21、Android SDK Platform 35 / Build-Tools 35、AGP 8.8、Kotlin 2.1。

## 权限

需要「所有文件访问权限」(`MANAGE_EXTERNAL_STORAGE`)：读取任意路径的本地包、把 `.img` 写入下载目录。首次执行相关操作时会引导授权。

## 致谢

- 引擎参考：`payload-dumper-c`（线程池 / 无状态源 / extents 分散写）
- 前身参考：Payload-Dumper-Compose（UI 布局与流程）
- 依赖：Jetpack Compose、OkHttp、Apache Commons Compress、tukaani-xz、aircompressor、protobuf

## License

Apache-2.0，见 [LICENSE](LICENSE)。`app/src/main/proto/update_metadata.proto` 来自 AOSP（Apache-2.0）。
