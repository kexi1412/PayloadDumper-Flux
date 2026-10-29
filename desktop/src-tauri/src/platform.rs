use std::path::{Path, PathBuf};
use tauri::WebviewWindow;

#[cfg(windows)]
mod win {
    use std::{ffi::c_void, ptr};
    type Handle = *mut c_void;
    #[link(name = "user32")]
    extern "system" {
        fn MessageBoxW(window: Handle, text: *const u16, title: *const u16, flags: u32) -> i32;
        fn OpenClipboard(window: Handle) -> i32;
        fn CloseClipboard() -> i32;
        fn EmptyClipboard() -> i32;
        fn SetClipboardData(format: u32, memory: Handle) -> Handle;
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn GlobalAlloc(flags: u32, bytes: usize) -> Handle;
        fn GlobalLock(memory: Handle) -> Handle;
        fn GlobalUnlock(memory: Handle) -> i32;
        fn GlobalFree(memory: Handle) -> Handle;
        fn GetStdHandle(kind: u32) -> Handle;
        fn AttachConsole(process: u32) -> i32;
    }
    #[link(name = "shell32")]
    extern "system" {
        fn ShellExecuteW(window: Handle, verb: *const u16, file: *const u16,
            parameters: *const u16, directory: *const u16, show: i32) -> Handle;
    }
    pub fn wide(value: &str) -> Vec<u16> { value.encode_utf16().chain(Some(0)).collect() }
    pub fn hwnd(window: &tauri::WebviewWindow) -> Result<Handle, String> {
        window.hwnd().map(|value| value.0 as Handle).map_err(|error| error.to_string())
    }
    pub fn error(message: &str) {
        let title = wide("无法启动 Payload Dumper Flux");
        let message = wide(message);
        unsafe { MessageBoxW(ptr::null_mut(), message.as_ptr(), title.as_ptr(), 0x10); }
    }
    pub fn confirm_close(window: &tauri::WebviewWindow) -> bool {
        let title = wide("任务仍在进行");
        let message = wide("退出并暂停当前任务？\n\n已完成的 payload 操作会保留，重新打开同一固件后可继续。\n选择“否”继续提取。");
        // YES/NO, question icon, default NO: pressing Enter must not stop a task.
        unsafe { MessageBoxW(hwnd(window).unwrap_or(ptr::null_mut()), message.as_ptr(), title.as_ptr(), 0x4 | 0x20 | 0x100) == 6 }
    }
    pub fn open(window: Option<&tauri::WebviewWindow>, value: &str) -> Result<(), String> {
        let value = wide(value);
        let verb = wide("open");
        let owner = window.map(hwnd).transpose()?.unwrap_or(ptr::null_mut());
        let result = unsafe { ShellExecuteW(owner, verb.as_ptr(), value.as_ptr(), ptr::null(), ptr::null(), 1) } as isize;
        if result <= 32 { return Err(format!("Windows 无法打开目标（系统错误 {result}）")); }
        Ok(())
    }
    pub fn copy(window: &tauri::WebviewWindow, value: &str) -> Result<(), String> {
        let value = wide(value);
        unsafe {
            if OpenClipboard(hwnd(window)?) == 0 { return Err("剪贴板被占用，请稍后重试".into()); }
        }
        struct Clipboard;
        impl Drop for Clipboard { fn drop(&mut self) { unsafe { CloseClipboard(); } } }
        let _clipboard = Clipboard;
        unsafe {
            let memory = GlobalAlloc(0x2, value.len() * std::mem::size_of::<u16>());
            if memory.is_null() { return Err("无法分配剪贴板内存".into()); }
            let target = GlobalLock(memory).cast::<u16>();
            if target.is_null() { GlobalFree(memory); return Err("无法锁定剪贴板内存".into()); }
            ptr::copy_nonoverlapping(value.as_ptr(), target, value.len());
            GlobalUnlock(memory);
            if EmptyClipboard() == 0 || SetClipboardData(13, memory).is_null() {
                GlobalFree(memory);
                return Err("Windows 写入剪贴板失败".into());
            }
            // On success Windows owns the movable CF_UNICODETEXT memory.
        }
        Ok(())
    }
    pub fn stdio() {
        // A GUI-subsystem release EXE inherits explicit pipe handles from CreateProcess.
        // Never replace them: attach only when a CLI launch supplied no stdout handle.
        unsafe {
            let output = GetStdHandle((-11i32) as u32);
            if output.is_null() || output as isize == -1 { AttachConsole(u32::MAX); }
        }
    }
}

pub fn pick_file(window: &WebviewWindow) -> Result<Option<PathBuf>, String> {
    #[cfg(windows)] { Ok(rfd::FileDialog::new().set_parent(window).set_title("选择固件文件")
        .add_filter("Android 固件", &["bin", "zip", "app", "img"])
        .add_filter("所有文件", &["*"]).pick_file()) }
    #[cfg(not(windows))] { let _ = window; Err("此发行版仅支持 Windows 原生文件选择器".into()) }
}

pub fn pick_directory(window: &WebviewWindow, current: &Path) -> Result<Option<PathBuf>, String> {
    #[cfg(windows)] { Ok(rfd::FileDialog::new().set_parent(window).set_title("选择镜像输出目录")
        .set_directory(current).pick_folder()) }
    #[cfg(not(windows))] { let _ = (window, current); Err("此发行版仅支持 Windows 原生目录选择器".into()) }
}

pub fn confirm_close(window: &WebviewWindow) -> bool {
    #[cfg(windows)] { win::confirm_close(window) }
    #[cfg(not(windows))] { let _ = window; false }
}

pub fn startup_error(message: &str) {
    eprintln!("{message}");
    #[cfg(windows)] { win::error(message); }
}

pub fn open_directory(window: &WebviewWindow, directory: &Path) -> Result<(), String> {
    let value = directory.to_str().ok_or("输出目录编码无效")?;
    #[cfg(windows)] { win::open(Some(window), value) }
    #[cfg(not(windows))] { let _ = (window, value); Err("此发行版仅支持 Windows 原生目录打开".into()) }
}

pub fn open_github(url: &tauri::Url) -> Result<(), String> {
    if url.scheme() != "https" || url.host_str() != Some("github.com") || !url.username().is_empty()
        || url.password().is_some() || url.port().is_some_and(|port| port != 443) {
        return Err("只允许打开 HTTPS GitHub 链接".into());
    }
    #[cfg(windows)] { win::open(None, url.as_str()) }
    #[cfg(not(windows))] { Err("此发行版仅支持 Windows 外部链接打开".into()) }
}

pub fn copy_text(window: &WebviewWindow, value: &str) -> Result<(), String> {
    #[cfg(windows)] { win::copy(window, value) }
    #[cfg(not(windows))] { let _ = (window, value); Err("此发行版仅支持 Windows 原生剪贴板".into()) }
}

pub fn engine_stdio() {
    #[cfg(windows)] { win::stdio(); }
}
