use serde::Serialize;
use serde_json::Value;
use std::{fs, path::{Component, Path, PathBuf}};

pub fn allowed_method(method: &str) -> bool {
    matches!(method, "get-app-info" | "get-state" | "pick-file" | "pick-output-dir" | "file-from-drop"
        | "parse" | "extract" | "cancel" | "reset" | "open-path" | "copy-text"
        | "load-preferences" | "save-preferences" | "get-history" | "clear-history")
}

pub fn requires_idle(method: &str) -> bool {
    matches!(method, "pick-file" | "pick-output-dir" | "file-from-drop" | "parse" | "extract" | "reset" | "open-path" | "clear-history")
}

pub fn is_busy(snapshot: &Value) -> bool {
    matches!(snapshot["phase"].as_str(), Some("parsing" | "extracting" | "cancelling"))
}

pub fn require_idle(snapshot: &Value, dialog_open: bool, shutting_down: bool) -> Result<(), String> {
    if shutting_down { return Err("应用正在退出".into()); }
    if is_busy(snapshot) { return Err("请先等待任务结束或暂停当前任务".into()); }
    if dialog_open { return Err("请先关闭当前文件选择对话框".into()); }
    Ok(())
}

pub fn text(value: &str, label: &str, max: usize) -> Result<String, String> {
    if value.trim().is_empty() || value.len() > max || value.contains('\0') {
        return Err(format!("{label}无效"));
    }
    Ok(value.trim().to_owned())
}

/// Reject device paths, ADS, ambiguous Windows names, and traversal before stat.
pub fn absolute(value: &str, label: &str) -> Result<PathBuf, String> {
    #[cfg(windows)] {
        if value.ends_with(' ') { return Err(format!("{label}包含无效的尾空格")); }
    }
    let value = text(value, label, 32768)?;
    let path = Path::new(&value);
    if !path.is_absolute() { return Err(format!("{label}必须是绝对路径")); }
    #[cfg(windows)] {
        let slash = value.replace('/', "\\");
        if slash.starts_with("\\\\?\\") || slash.starts_with("\\\\.\\") {
            return Err(format!("{label}不能使用设备路径"));
        }
    }
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => (),
            Component::ParentDir => {
                if !matches!(result.components().next_back(), Some(Component::Normal(_))) {
                    return Err(format!("{label}越过了路径根目录"));
                }
                result.pop();
            }
            Component::Normal(name) => {
                #[cfg(windows)] {
                    let name = name.to_str().ok_or_else(|| format!("{label}编码无效"))?;
                    if name.chars().any(|ch| ch.is_control() || "<>:\"|?*".contains(ch))
                        || name.ends_with('.') || name.ends_with(' ') {
                        return Err(format!("{label}包含无效的文件名"));
                    }
                    let stem = name.split('.').next().unwrap_or("").to_ascii_uppercase();
                    let device = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
                        || (stem.len() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT"))
                            && matches!(stem.as_bytes()[3], b'1'..=b'9'));
                    if device { return Err(format!("{label}包含保留设备名")); }
                }
                result.push(name);
            }
            _ => result.push(component.as_os_str()),
        }
    }
    Ok(result)
}

/// No symlink/junction/reparse component may redirect an output authority.
pub fn no_links(path: &Path) -> Result<(), String> {
    let mut current = PathBuf::new();
    for component in path.components() {
        current.push(component.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                #[cfg(windows)] {
                    use std::os::windows::fs::MetadataExt;
                    if metadata.file_attributes() & 0x400 != 0 {
                        return Err("路径包含符号链接、目录联接或重解析点，请选择真实目录/文件".into());
                    }
                }
                if metadata.file_type().is_symlink() {
                    return Err("路径包含符号链接，请选择真实目录/文件".into());
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
            Err(error) => return Err(format!("无法检查路径：{error}")),
        }
    }
    Ok(())
}

pub fn canonical(path: &Path) -> Result<PathBuf, String> {
    no_links(path)?;
    dunce::canonicalize(path).map_err(|error| format!("无法读取路径：{error}"))
}

fn same_path(left: &Path, right: &Path) -> bool {
    #[cfg(windows)] { left.to_string_lossy().eq_ignore_ascii_case(&right.to_string_lossy()) }
    #[cfg(not(windows))] { left == right }
}

fn within(candidate: &Path, parent: &Path) -> bool {
    let mut candidate = candidate.components();
    parent.components().all(|part| candidate.next()
        .is_some_and(|other| same_path(Path::new(other.as_os_str()), Path::new(part.as_os_str()))))
}

#[derive(Clone)]
pub struct TrustedRoot {
    original: PathBuf,
    resolved: PathBuf,
}

impl TrustedRoot {
    pub fn new(path: &Path) -> Result<Self, String> {
        let original = absolute(&path.to_string_lossy(), "输出目录")?;
        let resolved = canonical(&original)?;
        if !fs::metadata(&resolved).map_err(|error| error.to_string())?.is_dir() {
            return Err("所选项目不是文件夹".into());
        }
        Ok(Self { original, resolved })
    }

    pub fn path(&self) -> &Path { &self.resolved }

    pub fn accepts(&self, candidate: &Path) -> Result<bool, String> {
        // Recanonicalize the authorized root on every operation, not only selection.
        if !same_path(&canonical(&self.original)?, &self.resolved) {
            return Err("授权输出目录已经被重定向，请重新选择目录".into());
        }
        no_links(candidate)?;
        let mut existing = candidate.to_path_buf();
        while !existing.try_exists().map_err(|error| error.to_string())? {
            if !existing.pop() { return Err("输出目录不存在".into()); }
        }
        let existing_real = canonical(&existing)?;
        let suffix = candidate.strip_prefix(&existing).map_err(|error| error.to_string())?;
        Ok(within(&existing_real.join(suffix), &self.resolved))
    }
}

pub fn output_path(value: &str, roots: &[TrustedRoot], create: bool) -> Result<PathBuf, String> {
    let candidate = absolute(value, "输出目录")?;
    let allowed = roots.iter().any(|root| root.accepts(&candidate).unwrap_or(false));
    if !allowed { return Err("只能使用通过原生选择器授权的当前或历史输出目录".into()); }
    if create { fs::create_dir_all(&candidate).map_err(|error| format!("无法创建输出目录：{error}"))?; }
    let resolved = canonical(&candidate)?;
    if !fs::metadata(&resolved).map_err(|error| error.to_string())?.is_dir() {
        return Err("目标不是输出文件夹".into());
    }
    // Recheck after creation/stat to reject changed ancestors and junction escapes.
    if !roots.iter().any(|root| root.accepts(&resolved).unwrap_or(false)) {
        return Err("输出目录授权已失效，请重新选择目录".into());
    }
    Ok(resolved)
}

#[derive(Clone, Serialize)]
pub struct LocalFile {
    pub path: String,
    pub name: String,
    pub size: u64,
}

pub fn local_file(path: &Path) -> Result<LocalFile, String> {
    let path = absolute(&path.to_string_lossy(), "本地文件")?;
    let path = canonical(&path)?;
    let metadata = fs::metadata(&path).map_err(|error| format!("无法读取固件文件：{error}"))?;
    if !metadata.is_file() || metadata.len() == 0 {
        return Err("固件文件为空或不是普通文件".into());
    }
    let name = path.file_name().and_then(|value| value.to_str())
        .ok_or("固件文件名编码无效")?.to_owned();
    let path = path.to_str().ok_or("固件路径编码无效")?.to_owned();
    Ok(LocalFile { path, name, size: metadata.len() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn temporary() -> tempfile::TempDir {
        tempfile::Builder::new().prefix("flux-shell-guards-")
            .tempdir_in(dunce::canonicalize(std::env::temp_dir()).unwrap()).unwrap()
    }

    #[test]
    fn text_rejects_empty_nul_and_oversized_input() {
        assert_eq!(text("  payload  ", "来源", 32).unwrap(), "payload");
        for value in ["", "   ", "file\0name"] { assert!(text(value, "来源", 32).is_err()); }
        assert!(text(&"x".repeat(33), "来源", 32).is_err());
        assert!(absolute("relative/payload.bin", "文件").is_err());
    }

    #[test]
    fn absolute_normalizes_in_root_but_rejects_root_traversal() {
        let directory = temporary();
        let candidate = directory.path().join("a").join("..").join("payload.bin");
        assert_eq!(absolute(&candidate.to_string_lossy(), "文件").unwrap(), directory.path().join("payload.bin"));
        #[cfg(windows)] { assert!(absolute(r"C:\..\outside", "输出目录").is_err()); }
        #[cfg(not(windows))] { assert!(absolute("/../outside", "输出目录").is_err()); }
    }

    #[cfg(windows)]
    #[test]
    fn windows_device_ads_and_ambiguous_names_are_rejected() {
        for candidate in [r"C:\output\CON", r"C:\output\con.txt", r"C:\output\PRN.bin", r"C:\output\AUX", r"C:\output\NUL",
            r"C:\output\COM1.bin", r"C:\output\LPT9", r"C:\output\system.img:secret", r"C:\output\trailing.", r"C:\output\trailing ",
            r"\\?\C:\output", r"\\.\C:\output", r"C:\output\bad?name", r"C:\output\bad|name"] {
            assert!(absolute(candidate, "输出目录").is_err(), "accepted {candidate}");
        }
        for candidate in [r"C:\output\CONSOLE.bin", r"C:\output\COM10.bin", r"C:\固件输出\system.img"] {
            assert!(absolute(candidate, "输出目录").is_ok(), "rejected {candidate}");
        }
        assert!(absolute(r"\Windows", "输出目录").is_err());
    }

    #[test]
    fn authorization_checks_component_boundaries_and_creation() {
        let directory = temporary();
        let authorized = directory.path().join("authorized");
        let outside = directory.path().join("authorized-escape");
        fs::create_dir(&authorized).unwrap();
        fs::create_dir(&outside).unwrap();
        let roots = vec![TrustedRoot::new(&authorized).unwrap()];
        let inside = authorized.join("first").join("nested");
        let result = output_path(&inside.to_string_lossy(), &roots, true).unwrap();
        assert!(result.is_dir());
        assert_eq!(result, canonical(&inside).unwrap());
        assert!(output_path(&outside.to_string_lossy(), &roots, false).is_err());
        assert!(output_path(&authorized.join("..").join("authorized-escape").to_string_lossy(), &roots, true).is_err());
        assert!(output_path(&inside.join("not-created").to_string_lossy(), &roots, false).is_err());
        let image = authorized.join("system.img");
        fs::write(&image, b"image").unwrap();
        assert!(output_path(&image.to_string_lossy(), &roots, false).is_err());
    }

    #[test]
    fn stat_returns_a_real_regular_nonempty_file() {
        let directory = temporary();
        let file = directory.path().join("固件 payload.bin");
        fs::write(&file, b"CrAU-native-test").unwrap();
        let descriptor = local_file(&file).unwrap();
        assert_eq!(descriptor.path, canonical(&file).unwrap().to_string_lossy().into_owned());
        assert_eq!(descriptor.name, "固件 payload.bin");
        assert_eq!(descriptor.size, 16);
        assert!(local_file(directory.path()).is_err());
        let empty = directory.path().join("empty.bin");
        fs::write(&empty, b"").unwrap();
        assert!(local_file(&empty).is_err());
        assert!(local_file(&directory.path().join("missing.bin")).is_err());
    }

    #[test]
    fn ipc_whitelist_and_busy_mutation_policy_are_exact() {
        let mutations = ["pick-file", "pick-output-dir", "file-from-drop", "parse", "extract", "reset", "open-path", "clear-history"];
        for method in mutations { assert!(allowed_method(method)); assert!(requires_idle(method)); }
        for method in ["get-app-info", "get-state", "load-preferences", "save-preferences", "get-history", "copy-text", "cancel"] {
            assert!(allowed_method(method)); assert!(!requires_idle(method));
        }
        for method in ["shutdown", "status", "shell", "exec", "read-file", "open-url", "parse;exec", "__proto__", "PARSE", ""] {
            assert!(!allowed_method(method), "allowed {method}");
        }
        for phase in ["parsing", "extracting", "cancelling"] {
            let snapshot = json!({"phase": phase});
            assert!(is_busy(&snapshot));
            assert!(require_idle(&snapshot, false, false).is_err());
        }
        for phase in ["idle", "ready", "error"] {
            let snapshot = json!({"phase": phase});
            assert!(!is_busy(&snapshot));
            assert!(require_idle(&snapshot, false, false).is_ok());
            assert!(require_idle(&snapshot, true, false).is_err());
            assert!(require_idle(&snapshot, false, true).is_err());
        }
    }

    #[cfg(windows)]
    fn junction(link: &Path, target: &Path) {
        let status = std::process::Command::new("cmd.exe").args(["/d", "/c", "mklink", "/J"])
            .arg(link).arg(target).status().unwrap();
        assert!(status.success(), "unable to create test junction");
    }

    #[cfg(windows)]
    #[test]
    fn junction_escape_and_retargeted_chosen_root_are_rejected() {
        let directory = temporary();
        let authorized = directory.path().join("authorized");
        let outside = directory.path().join("outside");
        fs::create_dir(&authorized).unwrap();
        fs::create_dir(&outside).unwrap();
        let root = TrustedRoot::new(&authorized).unwrap();
        let escape = authorized.join("escape");
        junction(&escape, &outside);
        assert!(no_links(&escape).is_err());
        assert!(TrustedRoot::new(&escape).is_err());
        assert!(output_path(&escape.to_string_lossy(), &[root.clone()], false).is_err());
        fs::remove_dir(&escape).unwrap();
        fs::remove_dir(&authorized).unwrap();
        junction(&authorized, &outside);
        assert!(root.accepts(&authorized).is_err());
        fs::remove_dir(&authorized).unwrap();
        assert!(outside.is_dir());
    }
}
