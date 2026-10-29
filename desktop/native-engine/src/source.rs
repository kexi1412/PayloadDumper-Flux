use std::fs::{File, Metadata};
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::{atomic::{AtomicU64, Ordering}, Arc, Mutex};
use std::time::{Duration, Instant, UNIX_EPOCH};
use reqwest::blocking::Client;
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT_ENCODING, CONTENT_DISPOSITION, CONTENT_ENCODING, CONTENT_LENGTH, CONTENT_RANGE, ETAG, IF_RANGE, LAST_MODIFIED, RANGE, USER_AGENT};
use crate::model::{Options, SourceSpec};
use crate::paths::display_name;
use crate::util::{bounds, Cancel, Result, CHUNK};

pub(crate) trait Source: Send + Sync {
    fn size(&self) -> u64;
    fn name(&self) -> String;
    fn read_exact(&self, offset: u64, length: usize, cancel: &Cancel) -> Result<Vec<u8>>;
    fn remote(&self) -> bool { false }
}
pub(crate) type Input = Arc<dyn Source>;

pub(crate) fn open(spec: &SourceSpec, options: &Options, cancel: &Cancel) -> Result<Input> {
    cancel.check()?;
    if spec.kind == "file" { Ok(Arc::new(FileSource::open(Path::new(&spec.value))?)) }
    else { Ok(Arc::new(HttpSource::open(&spec.value, &options.user_agent, cancel)?)) }
}
pub(crate) fn file_stamp(path: &Path) -> Result<String> {
    let metadata = std::fs::metadata(path).map_err(|e| format!("无法检查本地固件：{e}"))?;
    Ok(stamp(&metadata))
}
fn stamp(metadata: &Metadata) -> String {
    let modified = metadata.modified().ok().and_then(|m| m.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_nanos()).unwrap_or_default();
    format!("{}:{modified}", metadata.len())
}

pub(crate) struct FileSource { file: Mutex<File>, size: u64, name: String }
impl FileSource {
    pub fn open(path: &Path) -> Result<Self> {
        let file = File::open(path).map_err(|e| format!("无法打开固件：{e}"))?;
        let metadata = file.metadata().map_err(|e| format!("无法读取固件大小：{e}"))?;
        if !metadata.is_file() || metadata.len() > i64::MAX as u64 { return Err("固件来源不是有效文件".into()); }
        Ok(Self { file: Mutex::new(file), size: metadata.len(),
            name: display_name(&path.file_name().unwrap_or_default().to_string_lossy()) })
    }
}
impl Source for FileSource {
    fn size(&self) -> u64 { self.size }
    fn name(&self) -> String { self.name.clone() }
    fn read_exact(&self, offset: u64, length: usize, cancel: &Cancel) -> Result<Vec<u8>> {
        bounds(offset, length as u64, self.size)?;
        cancel.check()?;
        let mut file = self.file.lock().map_err(|_| "文件读取锁失效".to_string())?;
        file.seek(SeekFrom::Start(offset)).map_err(|e| format!("文件定位失败：{e}"))?;
        let mut bytes = vec![0; length];
        for chunk in bytes.chunks_mut(CHUNK) {
            cancel.check()?;
            file.read_exact(chunk).map_err(|e| format!("文件读取不完整：{e}"))?;
        }
        cancel.check()?;
        Ok(bytes)
    }
}

pub(crate) struct SliceSource { input: Input, start: u64, size: u64, name: String }
impl SliceSource {
    pub fn make(input: Input, start: u64, size: u64, name: String) -> Result<Input> {
        bounds(start, size, input.size())?;
        Ok(Arc::new(Self { input, start, size, name }))
    }
}
impl Source for SliceSource {
    fn size(&self) -> u64 { self.size }
    fn name(&self) -> String { self.name.clone() }
    fn remote(&self) -> bool { self.input.remote() }
    fn read_exact(&self, offset: u64, length: usize, cancel: &Cancel) -> Result<Vec<u8>> {
        bounds(offset, length as u64, self.size)?;
        self.input.read_exact(self.start + offset, length, cancel)
    }
}

struct Failure { text: String, retry: bool }
struct Fetched { data: Vec<u8>, total: u64, headers: HeaderMap }
pub(crate) struct HttpSource {
    client: Client, entry_url: reqwest::Url, size: u64, name: String,
    validator: Option<String>,
}
impl HttpSource {
    pub fn open(url: &str, agent: &str, cancel: &Cancel) -> Result<Self> {
        let entry_url = reqwest::Url::parse(url).map_err(|_| "HTTP(S) 下载直链无效".to_string())?;
        if !matches!(entry_url.scheme(), "http" | "https") || entry_url.host_str().is_none() {
            return Err("只支持 HTTP(S) 下载直链".into());
        }
        let ua = if agent.trim().is_empty() { "PayloadDumperFlux/3.2.0-pc.1 (Desktop)" } else { agent.trim() };
        let mut headers = HeaderMap::new();
        headers.insert(USER_AGENT, HeaderValue::from_str(ua).map_err(|_| "User-Agent 无效".to_string())?);
        headers.insert(ACCEPT_ENCODING, HeaderValue::from_static("identity"));
        let client = Client::builder().default_headers(headers)
            .connect_timeout(Duration::from_secs(3)).timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::limited(8))
            .build().map_err(|_| "无法初始化系统 TLS HTTP 客户端".to_string())?;
        let first = Self::fetch(&client, &entry_url, 0, 0, None, None, cancel)?;
        let name = first.headers.get(CONTENT_DISPOSITION).and_then(|v| v.to_str().ok())
            .and_then(disposition_name).unwrap_or_else(|| {
                let component = entry_url.path_segments().and_then(|mut p| p.next_back()).unwrap_or("firmware.bin");
                display_name(&percent_decode(component).unwrap_or_else(|| "firmware.bin".into()))
            });
        let validator = first.headers.get(ETAG).and_then(|v| v.to_str().ok())
            .filter(|v| !v.starts_with("W/")).or_else(|| first.headers.get(LAST_MODIFIED).and_then(|v| v.to_str().ok()))
            .map(str::to_string);
        Ok(Self { client, entry_url, size: first.total, name, validator })
    }
    fn fetch(client: &Client, url: &reqwest::Url, start: u64, end: u64,
        total: Option<u64>, validator: Option<&str>, cancel: &Cancel) -> Result<Fetched> {
        for attempt in 0..3 {
            cancel.check()?;
            // Deliberately use the ENTRY URL for every request. Never pin a signed redirect URL.
            let mut request = client.get(url.clone()).timeout(cancel.request_timeout())
                .header(RANGE, format!("bytes={start}-{end}"));
            if let Some(value) = validator { request = request.header(IF_RANGE, value); }
            let fetched = (|| -> std::result::Result<Fetched, Failure> {
                let mut response = request.send().map_err(|e| Failure {
                    text: if e.is_timeout() { "HTTP Range 请求超时" } else { "HTTP Range 连接失败" }.into(),
                    retry: e.is_timeout() || e.is_connect() || e.is_body() || e.is_request(),
                })?;
                let status = response.status().as_u16();
                if status != 206 {
                    return Err(Failure { text: format!("HTTP {status}：服务器必须返回严格的 206 Range 响应"),
                        retry: matches!(status, 408 | 425 | 429 | 500 | 502 | 503 | 504) });
                }
                let headers = response.headers().clone();
                let range = headers.get(CONTENT_RANGE).and_then(|v| v.to_str().ok())
                    .and_then(content_range).ok_or_else(|| Failure { text: "HTTP Content-Range 无效或缺失".into(), retry: false })?;
                if range.0 != start || range.1 != end || total.is_some_and(|n| n != range.2) {
                    return Err(Failure { text: "HTTP Content-Range 与请求或原始文件大小不符".into(), retry: false });
                }
                if headers.get(CONTENT_ENCODING).and_then(|v| v.to_str().ok()).is_some_and(|v| !v.eq_ignore_ascii_case("identity")) {
                    return Err(Failure { text: "HTTP Range 不允许透明内容编码".into(), retry: false });
                }
                let expected = end - start + 1;
                if let Some(value) = headers.get(CONTENT_LENGTH) {
                    if value.to_str().ok().and_then(|v| v.parse::<u64>().ok()) != Some(expected) {
                        return Err(Failure { text: "HTTP Content-Length 与 Range 不符".into(), retry: false });
                    }
                }
                let mut data = Vec::with_capacity(expected as usize);
                let mut buffer = [0u8; 16 * 1024];
                loop {
                    cancel.check().map_err(|text| Failure { text, retry: false })?;
                    let want = (expected + 1 - data.len() as u64).min(buffer.len() as u64) as usize;
                    if want == 0 { return Err(Failure { text: "HTTP Range 实际响应过长".into(), retry: false }); }
                    let n = response.read(&mut buffer[..want]).map_err(|_| Failure { text: "HTTP Range 响应读取失败".into(), retry: true })?;
                    if n == 0 { break; }
                    data.extend_from_slice(&buffer[..n]);
                    if data.len() as u64 > expected { return Err(Failure { text: "HTTP Range 实际响应过长".into(), retry: false }); }
                }
                if data.len() as u64 != expected { return Err(Failure { text: "HTTP Range 实际响应被截断".into(), retry: true }); }
                Ok(Fetched { data, total: range.2, headers })
            })();
            cancel.check()?;
            match fetched {
                Ok(value) => return Ok(value),
                Err(error) if error.retry && attempt < 2 => {
                    cancel.wait(Duration::from_millis(100 * (attempt + 1)))?;
                }
                Err(error) => return Err(error.text),
            }
        }
        Err("HTTP Range 重试耗尽".into())
    }
}
impl Source for HttpSource {
    fn size(&self) -> u64 { self.size }
    fn name(&self) -> String { self.name.clone() }
    fn remote(&self) -> bool { true }
    fn read_exact(&self, offset: u64, length: usize, cancel: &Cancel) -> Result<Vec<u8>> {
        bounds(offset, length as u64, self.size)?;
        let mut out = Vec::with_capacity(length);
        while out.len() < length {
            cancel.check()?;
            let count = (length - out.len()).min(CHUNK);
            let start = offset + out.len() as u64;
            let got = Self::fetch(&self.client, &self.entry_url, start, start + count as u64 - 1,
                Some(self.size), self.validator.as_deref(), cancel)?;
            out.extend_from_slice(&got.data);
        }
        cancel.check()?;
        Ok(out)
    }
}
fn content_range(text: &str) -> Option<(u64, u64, u64)> {
    let body = text.trim().strip_prefix("bytes ")?;
    let (range, total) = body.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    let start = start.parse::<u64>().ok()?; let end = end.parse::<u64>().ok()?; let total = total.parse::<u64>().ok()?;
    if start > end || end >= total || total > i64::MAX as u64 { None } else { Some((start, end, total)) }
}
fn percent_decode(text: &str) -> Option<String> {
    let bytes = text.as_bytes(); let mut out = Vec::with_capacity(bytes.len()); let mut p = 0;
    while p < bytes.len() {
        if bytes[p] == b'%' {
            if p + 2 >= bytes.len() { return None; }
            let h = (bytes[p+1] as char).to_digit(16)?; let l = (bytes[p+2] as char).to_digit(16)?;
            out.push((h*16 + l) as u8); p += 3;
        } else { out.push(bytes[p]); p += 1; }
    }
    String::from_utf8(out).ok()
}
fn disposition_name(text: &str) -> Option<String> {
    let mut fallback = None;
    for part in text.split(';').skip(1) {
        let (key, value) = part.trim().split_once('=')?;
        let value = value.trim().trim_matches('"');
        if key.eq_ignore_ascii_case("filename*") {
            if let Some(raw) = value.strip_prefix("UTF-8''").or_else(|| value.strip_prefix("utf-8''")) {
                if let Some(decoded) = percent_decode(raw) { return Some(display_name(&decoded)); }
            }
        } else if key.eq_ignore_ascii_case("filename") { fallback = Some(display_name(value)); }
    }
    fallback
}

pub(crate) struct RangeReader { input: Input, offset: u64, remaining: u64, cancel: Cancel }
impl RangeReader {
    pub fn new(input: Input, offset: u64, length: u64, cancel: &Cancel) -> Result<Self> {
        bounds(offset, length, input.size())?;
        Ok(Self { input, offset, remaining: length, cancel: cancel.clone() })
    }
}
impl Read for RangeReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.cancel.check().map_err(io::Error::other)?;
        if self.remaining == 0 || buffer.is_empty() { return Ok(0); }
        let count = (buffer.len().min(CHUNK) as u64).min(self.remaining) as usize;
        let bytes = self.input.read_exact(self.offset, count, &self.cancel).map_err(io::Error::other)?;
        buffer[..count].copy_from_slice(&bytes); self.offset += count as u64; self.remaining -= count as u64;
        Ok(count)
    }
}

pub(crate) struct BudgetSource { input: Input, remaining: AtomicU64, deadline: Instant }
impl BudgetSource {
    pub fn make(input: Input, bytes: u64, duration: Duration) -> Input {
        Arc::new(Self { input, remaining: AtomicU64::new(bytes), deadline: Instant::now() + duration })
    }
}
impl Source for BudgetSource {
    fn size(&self) -> u64 { self.input.size() }
    fn name(&self) -> String { self.input.name() }
    fn remote(&self) -> bool { self.input.remote() }
    fn read_exact(&self, offset: u64, length: usize, cancel: &Cancel) -> Result<Vec<u8>> {
        self.remaining.fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_sub(length as u64))
            .map_err(|_| "元数据读取字节预算耗尽".to_string())?;
        if Instant::now() >= self.deadline { return Err("元数据读取时间预算耗尽".into()); }
        let limited = cancel.with_deadline(self.deadline.saturating_duration_since(Instant::now()));
        self.input.read_exact(offset, length, &limited)
    }
}

// A cache guard is owned by the source, so files disappear after reset/shutdown and never
// while an extraction still holds an Arc. File handle drops before its guard on Windows.
pub(crate) struct CacheGuard { pub path: PathBuf, pub parent: PathBuf, reservation: u64 }
static CACHE_USED: AtomicU64 = AtomicU64::new(0);
static CACHE_SEQUENCE: AtomicU64 = AtomicU64::new(0);
const MAX_CACHE_BYTES: u64 = 48 * 1024 * 1024 * 1024;
pub(crate) struct Spool { pub file: File, pub guard: CacheGuard }
pub(crate) fn spool_file(reservation: u64) -> Result<Spool> {
    if reservation > 32 * 1024 * 1024 * 1024 { return Err("单个磁盘缓存超过 32 GiB 上限".into()); }
    CACHE_USED.fetch_update(Ordering::AcqRel,Ordering::Acquire,|n| n.checked_add(reservation).filter(|v| *v <= MAX_CACHE_BYTES))
        .map_err(|_| "流式磁盘缓存超过 48 GiB 总上限".to_string())?;
    let opened: Result<Spool> = (|| {
        let root = std::env::var_os("FLUX_CACHE_DIR").map(PathBuf::from)
            .unwrap_or_else(|| std::env::temp_dir().join("flux-native-cache"));
        std::fs::create_dir_all(&root).map_err(|e| format!("无法创建缓存目录：{e}"))?;
        let parent = root.canonicalize().map_err(|e| format!("无法解析缓存目录：{e}"))?;
        let sequence = CACHE_SEQUENCE.fetch_add(1,Ordering::AcqRel);
        let stamp = std::time::SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
        let path = parent.join(format!("flux-spool-{}-{stamp}-{sequence}.tmp",std::process::id()));
        let file = std::fs::OpenOptions::new().read(true).write(true).create_new(true).open(&path)
            .map_err(|e| format!("无法创建有界磁盘缓存：{e}"))?;
        Ok(Spool { file,guard: CacheGuard { path,parent,reservation } })
    })();
    if opened.is_err() { CACHE_USED.fetch_sub(reservation,Ordering::AcqRel); }
    opened
}
impl Drop for CacheGuard {
    fn drop(&mut self) {
        CACHE_USED.fetch_sub(self.reservation,Ordering::AcqRel);
        if self.path.parent() == Some(self.parent.as_path()) && self.path.file_name().is_some_and(|n| n.to_string_lossy().starts_with("flux-spool-")) {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
pub(crate) struct CachedSource { inner: FileSource, _guard: CacheGuard }
impl CachedSource {
    pub fn make(guard: CacheGuard) -> Result<Input> {
        let inner = FileSource::open(&guard.path)?;
        Ok(Arc::new(Self { inner, _guard: guard }))
    }
}
impl Source for CachedSource {
    fn size(&self) -> u64 { self.inner.size() }
    fn name(&self) -> String { self.inner.name() }
    fn read_exact(&self, offset: u64, length: usize, cancel: &Cancel) -> Result<Vec<u8>> { self.inner.read_exact(offset, length, cancel) }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn strict_content_range() {
        assert_eq!(content_range("bytes 4294967296-4294967300/8589934592"), Some((4294967296,4294967300,8589934592)));
        for bad in ["bytes 0-1/*", "bytes 2-1/4", "bytes 0-4/4", "items 0-1/2", "bytes 0-1/2/3"] { assert_eq!(content_range(bad), None); }
    }
}
