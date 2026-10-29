use std::collections::HashSet;
use std::path::{Component, Path, PathBuf};
use crate::util::Result;

fn reserved(name: &str) -> bool {
    let upper = name.split('.').next().unwrap_or("").to_uppercase();
    matches!(upper.as_str(), "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$" | "CLOCK$")
        || (upper.starts_with("COM") || upper.starts_with("LPT"))
            && upper.chars().count() == 4
            && upper.chars().last().is_some_and(|c| matches!(c, '1'..='9' | '¹' | '²' | '³'))
}
pub(crate) fn leaf(name: &str) -> Result<String> {
    if name.is_empty() || name == "." || name == ".." || name.len() > 240
        || name.ends_with([' ', '.']) || name.chars().any(|c| c.is_control() || "<>:\"/\\|?*".contains(c))
        || reserved(name) { return Err(format!("无法安全映射到 Windows 的文件名：{name}")); }
    Ok(name.into())
}
pub(crate) fn archive_path(name: &str) -> Result<String> {
    if name.is_empty() || name.starts_with(['/', '\\']) || name.contains('\\') {
        return Err("ZIP 路径穿越或绝对路径已拒绝".into());
    }
    let path = name.strip_suffix('/').unwrap_or(name);
    for component in path.split('/') { leaf(component)?; }
    Ok(path.rsplit('/').next().unwrap_or(path).to_string())
}
pub(crate) fn partition(name: &str) -> Result<()> {
    if name.is_empty() || name.len() > 120 || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err(format!("分区名称无效：{name}"));
    }
    leaf(&format!("{name}.img"))?;
    Ok(())
}
pub(crate) fn display_name(value: &str) -> String {
    let value = value.replace('\\', "/");
    let component = value.rsplit('/').next().unwrap_or("firmware.bin");
    let cleaned: String = component.chars().filter(|c| !c.is_control()).map(|c| if "<>:\"|?*".contains(c) { '_' } else { c }).take(160).collect();
    let cleaned = cleaned.trim().trim_matches('.');
    if cleaned.is_empty() { "firmware.bin".into() }
    else if reserved(cleaned) { format!("firmware_{cleaned}") } else { cleaned.into() }
}
pub(crate) fn archive_folder(value: &str) -> Result<String> {
    let name = archive_path(value)?;
    let stem = name.rsplit_once('.').filter(|(_, e)| matches!(e.to_ascii_lowercase().as_str(), "zip" | "bin" | "app"))
        .map(|(s, _)| s).unwrap_or(&name);
    leaf(stem)
}
pub(crate) fn root(value: &str) -> Result<PathBuf> {
    if value.trim().is_empty() || value.contains('\0') { return Err("输出目录无效".into()); }
    let path = PathBuf::from(value);
    if !path.is_absolute() || path.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err("输出目录必须是没有父目录跳转的绝对路径".into());
    }
    if path.exists() && !path.is_dir() { return Err("输出目录不是文件夹".into()); }
    Ok(path)
}
fn is_link(metadata: &std::fs::Metadata) -> bool {
    if metadata.file_type().is_symlink() { return true; }
    #[cfg(windows)] {
        use std::os::windows::fs::MetadataExt;
        if metadata.file_attributes() & 0x400 != 0 { return true; }
    }
    false
}
pub(crate) fn directory(parent: &Path, name: &str) -> Result<PathBuf> {
    leaf(name)?;
    std::fs::create_dir_all(parent).map_err(|e| format!("无法创建输出目录：{e}"))?;
    let base = parent.canonicalize().map_err(|e| format!("无法解析输出目录：{e}"))?;
    let child = base.join(name);
    if let Ok(metadata) = std::fs::symlink_metadata(&child) {
        if is_link(&metadata) || !metadata.is_dir() { return Err("输出目录包含链接或非目录目标".into()); }
    } else { std::fs::create_dir(&child).map_err(|e| format!("无法创建分组目录：{e}"))?; }
    let real = child.canonicalize().map_err(|e| format!("无法解析分组目录：{e}"))?;
    if real.parent() != Some(base.as_path()) { return Err("输出目录逃逸已拒绝".into()); }
    Ok(real)
}
pub(crate) fn target(parent: &Path, name: &str) -> Result<PathBuf> {
    leaf(name)?;
    let base = parent.canonicalize().map_err(|e| format!("无法解析输出目录：{e}"))?;
    let child = base.join(name);
    if let Ok(metadata) = std::fs::symlink_metadata(&child) {
        if is_link(&metadata) || !metadata.is_file() { return Err("输出文件包含链接或非普通文件目标".into()); }
        let canonical = child.canonicalize().map_err(|e| format!("无法解析输出目标：{e}"))?;
        if canonical.parent() != Some(base.as_path()) { return Err("输出文件路径逃逸已拒绝".into()); }
    }
    Ok(child)
}
pub(crate) fn unique<'a>(names: impl Iterator<Item = &'a str>) -> Result<()> {
    let mut seen = HashSet::new();
    for name in names { if !seen.insert(name.to_uppercase()) { return Err(format!("固件包含重复或大小写冲突：{name}")); } }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn rejects_windows_and_traversal() {
        for bad in ["../boot.img", "/boot.img", "C:/boot.img", "a/../boot.img", "a\\boot.img", "CON.img", "images/AUX.img", "images/boot.img.", "images/boot.img "] { assert!(archive_path(bad).is_err(), "{bad}"); }
        assert_eq!(archive_path("images/boot.img").unwrap(), "boot.img");
        assert!(unique(["boot.img", "BOOT.img"].into_iter()).is_err());
    }
}
