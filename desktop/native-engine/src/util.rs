use std::sync::{atomic::{AtomicBool, Ordering}, Arc};
use std::time::{Duration, Instant};
use sha2::{Digest, Sha256};

pub(crate) type Result<T> = std::result::Result<T, String>;
pub(crate) const CHUNK: usize = 256 * 1024;
pub(crate) const CANCELLED: &str = "操作已取消";

#[derive(Clone, Default)]
pub(crate) struct Cancel { flag: Arc<AtomicBool>, deadline: Option<Instant> }
impl Cancel {
    pub fn cancel(&self) { self.flag.store(true, Ordering::Release); }
    pub fn is_cancelled(&self) -> bool { self.flag.load(Ordering::Acquire) }
    pub fn with_deadline(&self, duration: Duration) -> Self {
        Self { flag: self.flag.clone(), deadline: Some(Instant::now() + duration) }
    }
    pub fn request_timeout(&self) -> Duration {
        self.deadline.map(|d| d.saturating_duration_since(Instant::now()))
            .unwrap_or(Duration::from_secs(10)).min(Duration::from_secs(10))
            .max(Duration::from_millis(1))
    }
    pub fn check(&self) -> Result<()> {
        if self.is_cancelled() { Err(CANCELLED.into()) }
        else if self.deadline.is_some_and(|d| Instant::now() >= d) { Err("元数据读取预算耗尽".into()) }
        else { Ok(()) }
    }
    pub fn wait(&self, duration: Duration) -> Result<()> {
        let stop = Instant::now() + duration;
        while Instant::now() < stop {
            self.check()?;
            std::thread::sleep(Duration::from_millis(10).min(stop.saturating_duration_since(Instant::now())));
        }
        self.check()
    }
}
pub(crate) fn add(a: u64, b: u64, label: &str) -> Result<u64> {
    a.checked_add(b).filter(|v| *v <= i64::MAX as u64)
        .ok_or_else(|| format!("无效或溢出的 {label}"))
}
pub(crate) fn mul(a: u64, b: u64, label: &str) -> Result<u64> {
    a.checked_mul(b).filter(|v| *v <= i64::MAX as u64)
        .ok_or_else(|| format!("无效或溢出的 {label}"))
}
pub(crate) fn bounds(start: u64, length: u64, limit: u64) -> Result<u64> {
    if start > limit || length > limit - start { return Err("读取或输出范围超出边界".into()); }
    add(start, length, "range end")
}
pub(crate) fn hex(bytes: &[u8]) -> String {
    const H: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes { out.push(H[(b >> 4) as usize] as char); out.push(H[(b & 15) as usize] as char); }
    out
}
pub(crate) fn sha(bytes: &[u8]) -> Vec<u8> { Sha256::digest(bytes).to_vec() }
pub(crate) fn le16(bytes: &[u8], at: usize) -> u16 { u16::from_le_bytes([bytes[at], bytes[at+1]]) }
pub(crate) fn le32(bytes: &[u8], at: usize) -> u32 { u32::from_le_bytes(bytes[at..at+4].try_into().unwrap()) }
pub(crate) fn le64(bytes: &[u8], at: usize) -> u64 { u64::from_le_bytes(bytes[at..at+8].try_into().unwrap()) }
pub(crate) fn sanitize_message(value: &str) -> String {
    let mut out = String::new();
    for part in value.split_whitespace() {
        if !out.is_empty() { out.push(' '); }
        if part.contains("http://") || part.contains("https://") { out.push_str("[下载链接]"); }
        else { out.extend(part.chars().filter(|c| !c.is_control()).take(2048usize.saturating_sub(out.chars().count()))); }
        if out.len() >= 4096 { break; }
    }
    out.chars().take(2048).collect()
}
