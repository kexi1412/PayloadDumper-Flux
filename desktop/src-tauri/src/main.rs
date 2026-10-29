#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod guards;
mod platform;
mod profile;

use flux_native_engine::{Controller, EventSink};
use profile::{CurrentTask, EngineOptions, Profile};
use serde_json::{json, Value};
use std::{collections::HashSet, path::PathBuf, sync::{Arc, Mutex, MutexGuard, atomic::{AtomicBool, Ordering}}, time::{Duration, Instant}};
use tauri::{AppHandle, Emitter, Manager, State, WebviewWindow};
use uuid::Uuid;

struct Desktop {
    engine: Arc<Controller>,
    profile: Arc<Mutex<Profile>>,
    // Never acquire this mutex in the EventSink; Controller can emit synchronously.
    requests: Mutex<()>,
    selected_files: Mutex<HashSet<String>>,
    native_drop: Mutex<Option<(Vec<PathBuf>, Instant)>>,
    dialog_open: AtomicBool,
    close_prompt: AtomicBool,
    shutting_down: AtomicBool,
}

fn lock<T>(mutex: &Mutex<T>) -> Result<MutexGuard<'_, T>, String> {
    mutex.lock().map_err(|_| "桌面服务内部锁异常，请重新启动应用".into())
}

fn busy(snapshot: &Value) -> bool { guards::is_busy(snapshot) }

fn local_page(url: &tauri::Url) -> bool {
    let root_page = matches!(url.path(), "" | "/" | "/index.html");
    if !root_page || !url.username().is_empty() || url.password().is_some() { return false; }
    let bundled = ((matches!(url.scheme(), "http" | "https") && url.host_str() == Some("tauri.localhost") && url.port().is_none())
        || (url.scheme() == "tauri" && url.host_str() == Some("localhost")));
    let development = cfg!(debug_assertions) && url.scheme() == "http"
        && url.host_str() == Some("127.0.0.1") && url.port() == Some(5173);
    bundled || development
}

fn guard_window(window: &WebviewWindow) -> Result<(), String> {
    if window.label() != "main" || !local_page(&window.url().map_err(|error| error.to_string())?) {
        return Err("拒绝非本地主工作台请求".into());
    }
    Ok(())
}

fn options(params: &Value) -> Result<Value, String> {
    let value: EngineOptions = serde_json::from_value(params["options"].clone())
        .map_err(|_| "提取选项无效，必须包含并行线程、校验和 User-Agent 设置".to_owned())?;
    serde_json::to_value(value.validated()?).map_err(|error| error.to_string())
}

struct DialogLease<'a>(&'a AtomicBool);
impl Drop for DialogLease<'_> { fn drop(&mut self) { self.0.store(false, Ordering::Release); } }

impl Desktop {
    fn idle(&self) -> Result<(), String> {
        guards::require_idle(&self.engine.snapshot(), self.dialog_open.load(Ordering::Acquire), self.shutting_down.load(Ordering::Acquire))
    }

    fn dialog(&self) -> Result<DialogLease<'_>, String> {
        if self.dialog_open.swap(true, Ordering::AcqRel) { return Err("已有原生选择对话框打开".into()); }
        Ok(DialogLease(&self.dialog_open))
    }

    fn output(&self, value: &str, create: bool) -> Result<PathBuf, String> {
        let roots = lock(&self.profile)?.roots.clone();
        guards::output_path(value, &roots, create)
    }

    fn record_native_drop(&self, paths: &[PathBuf]) {
        if let Ok(mut pending) = self.native_drop.lock() {
            // Do not queue a locked-source drop for later acceptance after a task ends.
            *pending = if busy(&self.engine.snapshot()) || self.dialog_open.load(Ordering::Acquire)
                || self.shutting_down.load(Ordering::Acquire) { None }
                else { Some((paths.to_vec(), Instant::now())) };
        }
    }

    fn dispatch(&self, window: &WebviewWindow, method: &str, params: Value) -> Result<Value, String> {
        if !guards::allowed_method(method) { return Err("拒绝未知 IPC 方法".into()); }
        if self.shutting_down.load(Ordering::Acquire) { return Err("应用正在退出".into()); }
        if guards::requires_idle(method) { self.idle()?; }
        // This is the complete IPC whitelist. In particular no generic shell, FS,
        // worker-method passthrough, browser URL, script, or Node API is exposed.
        match method {
            "get-app-info" => {
                let default_output = lock(&self.profile)?.defaults.output_dir.clone();
                Ok(json!({ "version": env!("CARGO_PKG_VERSION"), "platform": if cfg!(windows) { "win32" } else { std::env::consts::OS },
                    "defaultOutputDir": default_output, "engineReady": true }))
            }
            "get-state" => Ok(self.engine.snapshot()),
            "load-preferences" => serde_json::to_value(&lock(&self.profile)?.preferences).map_err(|error| error.to_string()),
            "get-history" => serde_json::to_value(&lock(&self.profile)?.history).map_err(|error| error.to_string()),
            "copy-text" => {
                let value = guards::text(params.as_str().ok_or("复制内容无效")?, "复制内容", 2 * 1024 * 1024)?;
                platform::copy_text(window, &value)?;
                Ok(Value::Null)
            }
            "cancel" => self.engine.request("cancel", json!({})),
            "pick-file" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                let _dialog = self.dialog()?;
                let Some(path) = platform::pick_file(window)? else { return Ok(Value::Null); };
                let file = guards::local_file(&path)?;
                lock(&self.selected_files)?.insert(file.path.clone());
                serde_json::to_value(file).map_err(|error| error.to_string())
            }
            "pick-output-dir" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                let _dialog = self.dialog()?;
                let current = lock(&self.profile)?.preferences.output_dir.clone();
                let Some(path) = platform::pick_directory(window, &PathBuf::from(current))? else { return Ok(Value::Null); };
                let root = guards::TrustedRoot::new(&path)?;
                let path = root.path().to_string_lossy().into_owned();
                lock(&self.profile)?.roots.push(root);
                Ok(Value::String(path))
            }
            "file-from-drop" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                let paths = params["paths"].as_array().ok_or("原生拖放路径无效")?;
                if paths.len() != 1 { return Err("请一次拖入一个固件文件".into()); }
                let path = guards::absolute(paths[0].as_str().ok_or("原生拖放路径无效")?, "本地文件")?;
                let (dropped, when) = lock(&self.native_drop)?.take().ok_or("没有可用的原生文件拖放事件，请通过“选择文件”打开固件")?;
                if when.elapsed() > Duration::from_secs(60) || dropped.len() != 1
                    || guards::absolute(&dropped[0].to_string_lossy(), "本地文件")? != path {
                    return Err("拖放来源已失效，请重新拖入固件".into());
                }
                let file = guards::local_file(&path)?;
                lock(&self.selected_files)?.insert(file.path.clone());
                serde_json::to_value(file).map_err(|error| error.to_string())
            }
            "parse" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                let kind = params["source"]["kind"].as_str().ok_or("请选择固件来源")?;
                let source = guards::text(params["source"]["value"].as_str().ok_or("固件来源无效")?, "固件来源", 32768)?;
                let source = match kind {
                    "file" => {
                        let file = guards::local_file(&guards::absolute(&source, "本地文件")?)?;
                        if !lock(&self.selected_files)?.contains(&file.path) { return Err("请先通过原生文件选择器或拖放选择此固件".into()); }
                        file.path
                    }
                    "url" => {
                        let url = tauri::Url::parse(&source).map_err(|_| "请输入有效的 HTTP(S) 下载直链")?;
                        if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() { return Err("只支持 HTTP(S) 下载直链".into()); }
                        url.to_string()
                    }
                    _ => return Err("请选择本地文件或在线链接来源".into()),
                };
                let output = self.output(params["outputDir"].as_str().ok_or("输出目录无效")?, true)?;
                let options = options(&params)?;
                // All profile/file locks above have been released before this call.
                self.engine.request("parse", json!({ "source": { "kind": kind, "value": source }, "outputDir": output, "options": options }))
            }
            "extract" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                let snapshot = self.engine.snapshot();
                if snapshot["phase"].as_str() != Some("ready") || snapshot["archive"].is_null() { return Err("请先解析当前固件".into()); }
                let requested = params["partitions"].as_array().ok_or("请先选择分区")?;
                if requested.is_empty() || requested.len() > 10000 { return Err("请先选择有效分区".into()); }
                let rows = snapshot["partitions"].as_array().ok_or("当前固件分区状态无效")?;
                let known: HashSet<&str> = rows.iter().filter_map(|row| row["partitionName"].as_str()).collect();
                let mut seen = HashSet::new();
                let mut partitions = Vec::new();
                for value in requested {
                    let name = value.as_str().ok_or("分区选择无效")?;
                    if !known.contains(name) { return Err("选择中包含当前固件不存在的分区".into()); }
                    if seen.insert(name) { partitions.push(name.to_owned()); }
                }
                let output = self.output(params["outputDir"].as_str().ok_or("输出目录无效")?, true)?;
                let options = options(&params)?;
                let task_id = Uuid::new_v4().to_string();
                {
                    let mut profile = lock(&self.profile)?;
                    profile.current_task = Some(CurrentTask {
                        id: task_id.clone(), started_at: profile::now_ms(), started: false,
                        partitions: partitions.clone(), archive_name: snapshot["archive"]["fileName"].as_str().unwrap_or("").to_owned(),
                        output_dir: output.to_string_lossy().into_owned(),
                    });
                }
                let result = self.engine.request("extract", json!({ "partitions": partitions, "outputDir": output, "options": options }));
                if result.is_err() {
                    let mut profile = lock(&self.profile)?;
                    if profile.current_task.as_ref().is_some_and(|task| task.id == task_id) { profile.current_task = None; }
                }
                result
            }
            "reset" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                self.engine.request("reset", json!({}))
            }
            "open-path" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                let value = params.as_str().ok_or("输出目录无效")?;
                let (mut roots, history) = {
                    let profile = lock(&self.profile)?;
                    (profile.roots.clone(), profile.history.clone())
                };
                for entry in history {
                    if let Ok(root) = guards::TrustedRoot::new(&guards::absolute(&entry.output_dir, "历史输出目录")?) { roots.push(root); }
                }
                let directory = guards::output_path(value, &roots, false)?;
                self.idle()?;
                platform::open_directory(window, &directory)?;
                Ok(Value::Null)
            }
            "save-preferences" => {
                let _request = lock(&self.requests)?;
                let (defaults, previous) = {
                    let profile = lock(&self.profile)?;
                    (profile.defaults.clone(), profile.preferences.clone())
                };
                let next: profile::Preferences = serde_json::from_value(params).map_err(|_| "设置无效")?;
                let mut next = next.validated(&defaults)?;
                next.allow_update_from(&previous, busy(&self.engine.snapshot()), self.dialog_open.load(Ordering::Acquire))?;
                next.output_dir = self.output(&next.output_dir, false)?.to_string_lossy().into_owned();
                lock(&self.profile)?.save_preferences(next.clone())?;
                serde_json::to_value(next).map_err(|error| error.to_string())
            }
            "clear-history" => {
                let _request = lock(&self.requests)?;
                self.idle()?;
                lock(&self.profile)?.clear_history()?;
                window.emit("flux:history", Vec::<profile::HistoryEntry>::new()).map_err(|error| error.to_string())?;
                Ok(Value::Null)
            }
            _ => Err("拒绝未知 IPC 方法".into()),
        }
    }
}

#[tauri::command]
async fn flux_invoke(window: WebviewWindow, state: State<'_, Arc<Desktop>>, method: String, params: Option<Value>) -> Result<Value, String> {
    guard_window(&window)?;
    if method.len() > 64 { return Err("IPC 方法名无效".into()); }
    let desktop = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || desktop.dispatch(&window, &method, params.unwrap_or(Value::Null)))
        .await.map_err(|error| format!("桌面请求执行失败：{error}"))?
}

fn begin_shutdown(app: AppHandle, desktop: Arc<Desktop>) {
    if desktop.shutting_down.swap(true, Ordering::AcqRel) { return; }
    tauri::async_runtime::spawn_blocking(move || {
        let _ = desktop.engine.request("cancel", json!({}));
        desktop.engine.shutdown();
        app.exit(0);
    });
}

fn run_app() -> Result<(), Box<dyn std::error::Error>> {
    let mut context = tauri::generate_context!();
    let isolated = std::env::var_os("FLUX_USER_DATA").map(PathBuf::from);
    if let Some(path) = &isolated {
        let path = if path.is_absolute() { path.clone() } else { std::env::current_dir()?.join(path) };
        // Also isolate Tauri/WebView storage, not just the preferences/history JSON.
        context.config_mut().app.app_directories_override = Some(tauri::utils::config::AppDirectoriesOverride::Root(path));
    }
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .invoke_handler(tauri::generate_handler![flux_invoke])
        .setup(move |app| {
            let directory = app.path().app_data_dir()?;
            let downloads = app.path().download_dir()?;
            let profile = Arc::new(Mutex::new(Profile::load(directory, downloads, isolated.is_none()).map_err(std::io::Error::other)?));
            let history_profile = profile.clone();
            let handle = app.handle().clone();
            let sink: EventSink = Arc::new(move |event| {
                let Some(data) = event.get("data") else { return; };
                match event["event"].as_str() {
                    Some("state") => {
                        let update = history_profile.lock().ok().and_then(|mut profile| profile.finish_history(data));
                        if let Some(update) = update {
                            if let Some(message) = update.warning {
                                let _ = handle.emit_to("main", "flux:log", json!({ "time": profile::now_ms(), "level": "warn", "message": message }));
                            }
                            let _ = handle.emit_to("main", "flux:history", update.entries);
                        }
                        let _ = handle.emit_to("main", "flux:state", data.clone());
                    }
                    Some("log") => { let _ = handle.emit_to("main", "flux:log", data.clone()); }
                    _ => (),
                }
            });
            let desktop = Arc::new(Desktop {
                engine: Controller::new(sink), profile: profile.clone(), requests: Mutex::new(()),
                selected_files: Mutex::new(HashSet::new()), native_drop: Mutex::new(None),
                dialog_open: AtomicBool::new(false), close_prompt: AtomicBool::new(false), shutting_down: AtomicBool::new(false),
            });
            app.manage(desktop);
            let configuration = app.config().app.windows.iter().find(|window| window.label == "main").ok_or("缺少主窗口配置")?.clone();
            let webview_directory = lock(&profile).map_err(std::io::Error::other)?.directory.join("webview2");
            let window = tauri::WebviewWindowBuilder::from_config(app, &configuration)?
                .data_directory(webview_directory)
                .visible(std::env::var("FLUX_TEST_HIDDEN").as_deref() != Ok("1"))
                .on_navigation(local_page)
                .on_new_window(|url, _| {
                    let _ = platform::open_github(&url);
                    tauri::webview::NewWindowResponse::Deny
                })
                .on_download(|_, _| false)
                .on_permission_request(|_, _| tauri::webview::PermissionResponse::Deny)
                .build()?;
            #[cfg(debug_assertions)] {
                if std::env::args_os().any(|argument| argument == "--devtools") { window.open_devtools(); }
            }
            let _ = window;
            Ok(())
        })
        .on_webview_event(|webview, event| {
            if webview.label() != "main" { return; }
            if let tauri::WebviewEvent::DragDrop(tauri::DragDropEvent::Drop { paths, .. }) = event {
                if let Some(desktop) = webview.try_state::<Arc<Desktop>>() { desktop.record_native_drop(paths); }
            }
        })
        .on_window_event(|window, event| {
            if window.label() != "main" { return; }
            let Some(desktop) = window.try_state::<Arc<Desktop>>() else { return; };
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if desktop.shutting_down.load(Ordering::Acquire) || !busy(&desktop.engine.snapshot()) { return; }
                api.prevent_close();
                if desktop.close_prompt.swap(true, Ordering::AcqRel) { return; }
                let desktop = desktop.inner().clone();
                let handle = window.app_handle().clone();
                let Some(webview) = handle.get_webview_window("main") else { desktop.close_prompt.store(false, Ordering::Release); return; };
                tauri::async_runtime::spawn_blocking(move || {
                    if platform::confirm_close(&webview) { begin_shutdown(handle, desktop); }
                    else { desktop.close_prompt.store(false, Ordering::Release); }
                });
            }
        })
        .build(context)?;
    app.run(|handle, event| {
        match event {
            tauri::RunEvent::ExitRequested { api, .. } => {
                if let Some(desktop) = handle.try_state::<Arc<Desktop>>() {
                    if !desktop.shutting_down.load(Ordering::Acquire) {
                        api.prevent_exit();
                        begin_shutdown(handle.clone(), desktop.inner().clone());
                    }
                }
            }
            tauri::RunEvent::Exit => {
                if let Some(desktop) = handle.try_state::<Arc<Desktop>>() { desktop.engine.shutdown(); }
            }
            _ => (),
        }
    });
    Ok(())
}

fn main() {
    // The same release EXE verifies the actual engine over JSONL stdio. No WebView,
    // profile migration, dialog, JVM, sidecar, or separate worker starts in this mode.
    if std::env::args_os().skip(1).any(|argument| argument == "--engine") {
        platform::engine_stdio();
        std::process::exit(flux_native_engine::run_stdio());
    }
    #[cfg(windows)] {
        if let Err(error) = tauri::webview_version() {
            platform::startup_error(&format!("缺少或无法加载 Microsoft Edge WebView2 Runtime。\n\n本安装包使用系统已安装的 WebView2，不包含也不会下载运行时。请安装 WebView2 Runtime 后再打开应用。\n\n系统错误：{error}"));
            std::process::exit(1);
        }
    }
    if let Err(error) = run_app() {
        platform::startup_error(&format!("Payload Dumper Flux 无法启动：{error}"));
        std::process::exit(1);
    }
}
