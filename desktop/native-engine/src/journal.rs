//! Atomic, segmented resume journal. Each completed operation records a hash of the
//! actual decoded/scattered bytes. Disk content is re-hashed before a record is reused.
use std::collections::BTreeMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use crate::{paths, util::{hex, sha, Cancel, Result}};

const SEGMENT_OPS: usize = 128;
const MAX_SEGMENT_BYTES: u64 = 32 * 1024;
static NONCE: AtomicU64 = AtomicU64::new(0);
#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Commit { pub sha256: String, pub length: u64 }
#[derive(Clone, Serialize, Deserialize)]
struct Header { version: u32, binding: String, count: usize, size: u64, nonce: String }
#[derive(Serialize, Deserialize)]
struct Segment { binding: String, nonce: String, number: usize, records: BTreeMap<usize, Commit> }
#[derive(Serialize, Deserialize)]
struct Envelope<T> { data: T, checksum: String }

pub(crate) struct Journal {
    header_path: PathBuf, directory: PathBuf, header: Header,
    pub completed: BTreeMap<usize, Commit>,
}
impl Journal {
    pub fn load(parent: &Path, partition: &str, binding: String, count: usize, size: u64, image: &Path, cancel: &Cancel) -> Result<Self> {
        cancel.check()?;
        let header_path = paths::target(parent,&format!("{partition}.img.flux-progress.json"))?;
        let directory = paths::directory(parent,&format!("{partition}.img.flux-progress.d"))?;
        let old: Option<Header> = read_envelope(&header_path,4096).ok();
        let valid = image.metadata().is_ok_and(|m| m.is_file() && m.len() == size);
        let matching = old.filter(|h| valid && h.version == 3 && h.binding == binding && h.count == count && h.size == size && !h.nonce.is_empty());
        let has_header = matching.is_some();
        let header = matching.unwrap_or_else(|| Header { version: 3,binding,count,size,nonce: nonce() });
        let mut journal = Self { header_path,directory,header,completed: BTreeMap::new() };
        if has_header {
            for number in 0..count.div_ceil(SEGMENT_OPS) {
                cancel.check()?;
                let path = journal.segment_path(number)?;
                let Ok(segment) = read_envelope::<Segment>(&path,MAX_SEGMENT_BYTES) else { continue; };
                if segment.binding != journal.header.binding || segment.nonce != journal.header.nonce || segment.number != number
                    || segment.records.len() > SEGMENT_OPS { continue; }
                if segment.records.iter().any(|(index,record)| *index >= count || *index / SEGMENT_OPS != number
                    || record.sha256.len() != 64 || !record.sha256.bytes().all(|c| c.is_ascii_hexdigit())) { continue; }
                journal.completed.extend(segment.records);
            }
        } else { journal.save_header()?; }
        Ok(journal)
    }
    fn segment_path(&self,number: usize) -> Result<PathBuf> { paths::target(&self.directory,&format!("segment-{number:06}.json")) }
    fn save_header(&self) -> Result<()> { write_envelope(&self.header_path,&self.header) }
    fn records(&self,number: usize) -> BTreeMap<usize,Commit> {
        self.completed.range(number*SEGMENT_OPS..(number+1)*SEGMENT_OPS).map(|(i,c)| (*i,c.clone())).collect()
    }
    // Caller has already synced the image fd. Only publish the index after its atomic
    // journal replacement has completed. At most 128 records are rewritten per commit.
    pub fn mark(&mut self,index: usize,hash: String,length: u64) -> Result<()> {
        if index >= self.header.count { return Err("日志操作序号超出计划".into()); }
        let number = index / SEGMENT_OPS; let mut records = self.records(number);
        let record = Commit { sha256: hash,length }; records.insert(index,record.clone());
        let segment = Segment { binding: self.header.binding.clone(),nonce: self.header.nonce.clone(),number,records };
        write_envelope(&self.segment_path(number)?,&segment)?;
        self.completed.insert(index,record);
        Ok(())
    }
    pub fn discard(&mut self,index: usize) -> Result<()> {
        let number = index / SEGMENT_OPS; let mut records = self.records(number); records.remove(&index);
        let segment = Segment { binding: self.header.binding.clone(),nonce: self.header.nonce.clone(),number,records };
        write_envelope(&self.segment_path(number)?,&segment)?;
        self.completed.remove(&index); Ok(())
    }
    pub fn clear(&mut self) -> Result<()> {
        self.header.nonce = nonce();
        self.save_header()?;
        self.completed.clear();
        Ok(())
    }
}
fn nonce() -> String {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos();
    format!("{}-{nanos}-{}",std::process::id(),NONCE.fetch_add(1,Ordering::AcqRel))
}
fn read_envelope<T: Serialize + DeserializeOwned>(path: &Path,limit: u64) -> Result<T> {
    let mut file = File::open(path).map_err(|e| format!("无法读取进度日志：{e}"))?;
    if file.metadata().map_err(|e| e.to_string())?.len() > limit { return Err("进度日志超出预算".into()); }
    let mut bytes = Vec::new(); Read::by_ref(&mut file).take(limit+1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    if bytes.len() as u64 > limit { return Err("进度日志超出预算".into()); }
    let envelope: Envelope<T> = serde_json::from_slice(&bytes).map_err(|_| "进度日志格式无效".to_string())?;
    let canonical = serde_json::to_vec(&envelope.data).map_err(|e| e.to_string())?;
    if hex(&sha(&canonical)) != envelope.checksum { return Err("进度日志完整性校验失败".into()); }
    Ok(envelope.data)
}
fn write_envelope<T: Serialize>(path: &Path,data: &T) -> Result<()> {
    let canonical = serde_json::to_vec(data).map_err(|e| e.to_string())?;
    let envelope = serde_json::json!({"data": data,"checksum":hex(&sha(&canonical))});
    let bytes = serde_json::to_vec(&envelope).map_err(|e| e.to_string())?;
    let parent = path.parent().ok_or_else(|| "进度日志路径无效".to_string())?;
    let leaf = path.file_name().ok_or_else(|| "进度日志文件名无效".to_string())?.to_string_lossy();
    let temp = paths::target(parent,&format!("{leaf}.{}.tmp",NONCE.fetch_add(1,Ordering::AcqRel)))?;
    let result: Result<()> = (|| {
        let mut file = OpenOptions::new().write(true).create_new(true).open(&temp).map_err(|e| format!("创建原子进度日志失败：{e}"))?;
        file.write_all(&bytes).map_err(|e| format!("写入进度日志失败：{e}"))?;
        file.sync_all().map_err(|e| format!("同步进度日志失败：{e}"))?;
        drop(file);
        atomic_replace(&temp,path)?;
        #[cfg(unix)] { File::open(parent).and_then(|f| f.sync_all()).map_err(|e| format!("同步日志目录失败：{e}"))?; }
        Ok(())
    })();
    if result.is_err() && temp.parent() == Some(parent) { let _ = std::fs::remove_file(&temp); }
    result
}
#[cfg(windows)]
fn atomic_replace(from: &Path,to: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name="kernel32")]
    extern "system" { fn MoveFileExW(from: *const u16,to: *const u16,flags: u32) -> i32; }
    let from: Vec<u16> = from.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = to.as_os_str().encode_wide().chain(Some(0)).collect();
    // Windows rename() cannot replace an existing file. This is one atomic filesystem
    // replacement, not delete-then-rename, and WRITE_THROUGH preserves commit ordering.
    if unsafe { MoveFileExW(from.as_ptr(),to.as_ptr(),1|8) } == 0 {
        Err(format!("原子替换进度日志失败：{}",std::io::Error::last_os_error()))
    } else { Ok(()) }
}
#[cfg(not(windows))]
fn atomic_replace(from: &Path,to: &Path) -> Result<()> { std::fs::rename(from,to).map_err(|e| format!("原子替换进度日志失败：{e}")) }
