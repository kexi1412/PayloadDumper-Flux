# Payload Dumper · Flux — PC 原生轻量版

仓库只保留 **`main`**。Windows 10 / 11 x64 桌面版使用 **Tauri + 系统 WebView2 + 原生 Rust 提取引擎**，保留原来的 React / new-api 风格工作台。Android 版继续使用已验证的 Kotlin 实现，PC 原生版通过相同固件、输出哈希及断点续传回归与其核对。

## 为什么安装包能小下来

旧包的主要体积不是界面，而是内置 Chromium 和 Java。原生版不捆绑 Chromium、Node.js、JRE，也不通过小型下载器再下载这些运行环境；提取引擎直接编进应用。

界面使用 Windows 已安装的 **Microsoft WebView2 Runtime**，和安卓应用使用系统图形/运行环境类似。发行安装器不会静默下载 WebView2；缺少该系统组件时需要先安装 [Microsoft WebView2](<https://developer.microsoft.com/microsoft-edge/webview2/>)。源码构建和最终交付都以真实安装包、便携 ZIP **小于 10,000,000 字节**为门槛，实际字节数和 SHA-256 写入随包记录。

## 界面与功能

- 固定侧栏、顶栏、卡片、分区表格、亮暗主题；不为了体积删除原工作台。
- 系统选择 / 原生拖放本地固件，或 HTTP(S) OTA 直链；本地与网络来源独立保存。
- 分区搜索、单项 / 批量提取、实时进度、错误日志、历史和输出目录选择。
- 目标固件的 Android 版本、安全补丁、构建指纹及可验证的内核信息；缺少依据时保留未知。
- 1–16 个操作线程，操作 SHA-256、可选最终分区 SHA-256 校验；失败不会显示完成。
- payload 暂停 / 继续。记录绑定完整原始 manifest、块大小、分区及磁盘输出内容，不以下载 URL 或文件名判断是同一个包。
- 新原生版使用自己的进度记录，不会把旧 JVM 版的日志直接当成有效进度。普通 ZIP / UPDATE.APP 条目重新开始，不虚报断点续传。
- 最多 100 条历史和设置保存在用户数据目录，不保存带签名参数的下载 URL。

### 格式边界

| 格式 | 行为 |
|---|---|
| 原始 payload / 内含 STORED payload 的 OTA ZIP、ZIP64 | 按实际操作提取 REPLACE、BZ、XZ、ZERO、DISCARD；minor 非零不自动拒绝 |
| 真实 SOURCE_COPY、MOVE / DIFF 或 source 引用 | 输出前指出需要旧镜像的操作类型与索引，不产生伪完整镜像 |
| 直接镜像 ZIP | 按 STORED / DEFLATE 条目提取，核对输出长度与 CRC32 |
| 华为 UPDATE.APP | 按有边界的分区条目提取 |
| 华为 / 荣耀嵌套 SD ZIP | 分组处理、隔离内层目录；必要时流式缓存到磁盘，不把大包整体装入内存 |
| 块式 OTA | 提取原始条目，不自动把 `.new.dat` 转成文件系统镜像 |

默认输出到下载目录的 `PayloadDumper-Flux/firmware_<身份摘要>/`，嵌套 ZIP 使用独立子目录。Windows 保留文件名、路径穿越、大小写冲突及越界输出会被拒绝。

内核元数据当前只识别 raw/gzip 中唯一的 Linux banner；LZ4 内核和 ARM64 UTS 专项定位尚未移植，遇到这类信息留空，不影响镜像字节提取。ZIP 名称需为 UTF-8/ASCII；加密、多卷及其它压缩方式明确拒绝。流式磁盘缓存单项上限 32GiB、总预算 48GiB。

## 使用发行包

1. 运行 `PayloadDumper-Flux-PC-<版本>-x64-setup.exe` 进行当前用户安装；或完整解压便携 ZIP，运行其中的原生主程序。
2. 选择本地固件或粘贴直链，点击解析。
3. 勾选分区、选择输出目录，开始提取；结束后打开输出目录。

运行时不需要安装 Java、Node.js、Rust 或 Android SDK。系统 WebView2 是共享系统组件，不计入应用文件。

快捷键：`Ctrl+O` 选择固件，`Ctrl+Enter` 解析。任务运行时锁定来源及提取设置。

## 从源码构建

Windows 构建需要 Rust stable（MSVC 工具链与 Windows SDK）、Node.js 24、pnpm 10.28.2、系统 WebView2。在仓库根目录执行：

```powershell
pnpm --dir desktop install --frozen-lockfile
pnpm --dir desktop test
pnpm --dir desktop engine:test
pnpm --dir desktop engine:build
pnpm --dir desktop test:engine
pnpm --dir desktop build
pnpm --dir desktop package:win
pnpm --dir desktop verify:package
```

安装器的原始构建结果在 `desktop/src-tauri/target/.../release/bundle/nsis/`；通过大小检查的安装器、便携 ZIP、校验和与包体记录统一放在 `desktop/release/`。

JVM 对照回归只在开发验证时需要 JDK 21，不会把 Java 放进发行包：

```powershell
pnpm --dir desktop engine:jvm
pnpm --dir desktop test:engine:jvm
```

只打开编译后的网页没有本地文件访问和提取能力；未连接原生桥时界面会明确显示未连接，而不是给出模拟结果。

## 结构与自动验证

```text
desktop/
  src/                保留的 React 工作台与 Tauri 适配桥
  src-tauri/          原生窗口、系统对话框、设置/历史与限定 IPC
  native-engine/      Rust 固件解析、提取、校验与进度日志
  engine/             Kotlin/JVM 参考实现，仅用于对照验证
  electron/           历史实现与 JSONL 测试辅助，不进入原生发行包
  tests/              真实 OTA/ZIP、HTTP 服务与安装后 WebView2 UI 回归
  scripts/            构建验证、包体阈值、校验和及便携包生成
```

[Build PC 工作流](<../.github/workflows/build-pc.yml>) 在 `main` 上执行原生单测、真实提取与 HTTP 续传、JVM 同夹具对照、安装器大小检查，以及**安装之后**的真实系统 WebView2 界面操作；交付的主程序还要以 `--engine` 模式再次通过提取回归。

测试固件仅用于生成独立、可核对哈希的回归输入，不是应用预置数据。系统 WebView2 的调试端口只由测试进程显式设置，正常运行不会对外开放测试服务。
