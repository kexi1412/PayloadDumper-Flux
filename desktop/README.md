# Payload Dumper · Flux — PC

`pc` 是桌面版独立分支，基于 Android `feat/meizu` 的提取引擎。当前交付目标是 **Windows 10 / 11 x64**，使用 Electron + React 界面与独立 JVM worker；发行包自带 Java 运行时，不需要 Android SDK、Java 安装或 GitHub 密钥。

## 界面与功能

- 参考 [new-api](https://github.com/QuantumNous/new-api) 的桌面控制台结构：固定侧栏、顶栏、浅色卡片、细边线、蓝色选中态、数据表格、亮暗主题。界面为本项目独立实现。
- 本地选择 / 拖放固件；HTTP(S) OTA 下载直链。两种来源独立，不会把本地路径当 URL。
- 固件文件信息、Android 版本、安全补丁、构建指纹、内核版本；没有依据的字段显示未知。网络内核检查在后台进行，不阻塞开始提取。
- 分区搜索、勾选、可见项全选、单项 / 批量提取、实时进度、错误日志、历史记录与自选输出目录。
- 操作级并行线程 1–16。分区按队列处理，单个 payload 分区内部并行；同一个嵌套 ZIP 的选中条目合并为一次流式提取。
- payload 操作 SHA-256 校验、可选最终分区 SHA-256 校验。哈希不匹配记为失败，不显示完成。
- 暂停 / 继续。**payload** 的操作进度绑定完整 manifest、块大小、分区和输出文件；普通 ZIP / UPDATE.APP 条目重新开始，不虚报 ZIP 断点续传。
- 设置与最多 100 条历史保存在桌面应用的用户数据目录中，不写入仓库。历史只记录文件名、输出路径及结果，不保存带签名参数的下载 URL。

### 固件支持边界

| 格式 | 行为 |
| --- | --- |
| 全量 `payload.bin` / 内含 STORED payload 的 OTA ZIP | 提取 REPLACE、REPLACE_BZ、REPLACE_XZ、ZERO、DISCARD 等现有引擎支持的操作 |
| 直接镜像 ZIP、华为 UPDATE.APP | 按条目提取，校验声明长度 |
| 华为 / 荣耀嵌套 SD 升级 ZIP | 流式批量提取，隔离内层归档目录，核对长度 / 可用 CRC32 |
| 块式 OTA | 展示并提取原始条目，不自动将 `.new.dat` 转换为文件系统镜像 |
| 需要旧镜像的增量 OTA | 明确报错，不生成伪完整镜像 |

默认输出到系统下载目录的 `PayloadDumper-Flux/<固件名>_<身份摘要>/`。目录按固件隔离；Windows 保留名称、越界路径、同名输出冲突会被拒绝。内层 ZIP 再分独立子目录。

## 运行发行版

1. 运行 `PayloadDumper-Flux-PC-<版本>-x64.exe`，或完整解压 ZIP 后运行其中的主程序。
2. 在「固件提取」选择本地文件，或切换「在线链接」粘贴下载直链。
3. 点击解析；选择分区及输出根目录，然后开始提取。
4. 提取完成后打开输出目录；错误详情在任务状态和日志中可见。

快捷键：`Ctrl+O` 选择固件，`Ctrl+Enter` 解析。任务运行时来源和提取设置锁定，先暂停才能切换。

## 从源码构建

前提：**JDK 21**（设置 `JAVA_HOME`）、**Node.js 24**、**pnpm 10.28.2**。Windows 下在仓库根目录执行：

```powershell
pnpm --dir desktop install --frozen-lockfile
pnpm --dir desktop runtime:install
pnpm --dir desktop test
pnpm --dir desktop engine:build
pnpm --dir desktop runtime:build
pnpm --dir desktop test:engine
pnpm --dir desktop build
pnpm --dir desktop start
```

生成自带运行环境的 Windows 包：

```powershell
pnpm --dir desktop package:win
# 产物位于 desktop/release/
```

`runtime:install` 是 Electron 45 的显式下载步骤。下载器使用锁定版本附带的校验值；`package:win` 复用已安装的 Electron，不再次下载。

引擎可单独构建，不配置或检查 Android SDK。推荐使用 `pnpm --dir desktop engine:build`：若 Windows 仓库路径包含中文，脚本会创建临时 ASCII 目录联接，所有源码、产物及 JVM 临时文件仍在原工程中，结束后仅移除联接；不修改系统代码页或文件权限。可通过 `FLUX_BUILD_TEMP` 指定其他已有的 ASCII 联接父目录。

在纯 ASCII 仓库路径中也可以直接执行：

```powershell
.\gradlew.bat -p desktop/engine test fatJar --no-daemon
```

只打开编译后的网页并不能访问本地引擎；请通过 Electron 启动。没有桌面桥接时页面会明确说明，而不是显示虚构数据。

## 结构

```text
desktop/
  src/                 React 工作台、历史、设置与严格类型 IPC 合同
  electron/            隔离的主进程、preload、JSONL worker 客户端、输入验证
  engine/              独立 JVM Gradle 项目与桌面任务控制器
  tests/               IPC 单测和生成真实 OTA/ZIP 的端到端回归
  scripts/             引擎、jlink 运行时、Windows 打包命令
  assets/              原创应用图标
```

共享引擎直接编译 Android 工程中的核心源码与 AOSP protobuf，不重写 payload 算法。平台调整仅将 `UriSource` 机械拆分到 Android 专用文件，并用桌面缓存适配器替换依赖 Android Context 的缓存位置。Android 原有入口和构建流程保留。

跨进程通信是标准输入 / 输出 JSONL，无本地 HTTP 服务或对外监听端口。渲染器启用 sandbox、contextIsolation，关闭 Node 集成；只暴露固定的文件选择、任务、设置和日志 API。

## 自动构建与验证

[Build PC 工作流](../.github/workflows/build-pc.yml) 只处理 `pc` 分支，构建 Windows portable EXE / ZIP 并上传 Actions Artifact，不触发 Android 主分支 Release。

- `pnpm test`：输入检查、输出目录边界、JSONL 生命周期、UTF-8 分片、错误及超时。
- JVM `test`：直接复用原引擎的纯 JVM 核心回归。
- `pnpm test:engine`：生成确定性真实 OTA / 普通 ZIP / 嵌套 ZIP，调用实际 worker 提取并比较每个输出 SHA-256；覆盖错误哈希、增量操作拒绝、HTTP Range、自定义 UA、暂停与续传。

自动生成的测试固件只用于测试，不是应用预置数据。界面验证截图与本次验证结果见仓库 `docs/` 下的 PC 记录。
