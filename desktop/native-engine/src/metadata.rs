//! Best-effort target metadata only. Unknown fields stay empty; no host OS values,
//! guessed patch days, operation-order shortcuts, or ramdisk banner scanning.
use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{Read, Seek, SeekFrom};
use std::time::Duration;
use prost::Message;
use crate::archive::{Archive, Kind};
use crate::model::ArchiveInfo;
use crate::payload::{self, Payload, Plan};
use crate::source::{BudgetSource, Input};
use crate::util::{bounds, le32, Cancel, Result};
use crate::zip;

const TEXT_LIMIT: usize = 1024*1024;
const KERNEL_LIMIT: usize = 64*1024*1024;
const METADATA_OP_LIMIT: u64 = 16*1024*1024;
#[derive(Clone, Default)]
struct Fields { android: String,patch: String,kernel: String,fingerprint: String }
impl Fields {
    fn fallback(&mut self,other: Self) {
        if self.android.is_empty() { self.android = other.android; }
        if self.patch.is_empty() { self.patch = other.patch; }
        if self.kernel.is_empty() { self.kernel = other.kernel; }
        if self.fingerprint.is_empty() { self.fingerprint = other.fingerprint; }
    }
    fn from_info(info: &ArchiveInfo) -> Self { Self { android: info.android_version.clone(),patch: info.security_patch_level.clone(),kernel: info.kernel_version.clone(),fingerprint: info.fingerprint.clone() } }
    fn apply(self,base: &ArchiveInfo) -> ArchiveInfo {
        let mut info = base.clone(); info.android_version = self.android; info.security_patch_level = self.patch;
        info.kernel_version = self.kernel; info.fingerprint = self.fingerprint; info
    }
}
#[derive(Clone, PartialEq, Message)]
struct OtaMetadata { #[prost(message,optional,tag="6")] postcondition: Option<DeviceState> }
#[derive(Clone, PartialEq, Message)]
struct DeviceState {
    #[prost(string,repeated,tag="2")] build: Vec<String>,
    #[prost(string,tag="5")] sdk_level: String,
    #[prost(string,tag="6")] security_patch_level: String,
}

pub(crate) fn basic(archive: &Archive,cancel: &Cancel) -> ArchiveInfo {
    let remote = archive.primary.remote();
    let duration = if remote { Duration::from_secs(2) } else { Duration::from_secs(8) };
    let limited = cancel.with_deadline(duration);
    let input = BudgetSource::make(archive.primary.clone(),4*1024*1024,duration);
    let mut fields = Fields::default();
    // archive.metadata is already sorted protobuf, text, build.prop by discovery order
    // below, not arbitrary ZIP entry order. Source preconditions are never consulted.
    let mut candidates = archive.metadata.clone();
    candidates.sort_by_key(|m| if m.entry.name.ends_with("metadata.pb") { 0 } else if m.entry.name.ends_with("/metadata") { 1 } else { 2 });
    for candidate in candidates {
        if limited.check().is_err() { break; }
        let result: Result<Fields> = (|| {
            let range = zip::entry_range(&input,&candidate.entry,&limited)?;
            let bytes = zip::read_small(&input,&range,TEXT_LIMIT,&limited)?;
            if candidate.entry.name.ends_with("metadata.pb") { from_proto(&bytes) }
            else {
                let text = std::str::from_utf8(&bytes).map_err(|_| "元数据文本不是 UTF-8".to_string())?;
                Ok(from_text(text,candidate.entry.name.ends_with("build.prop")))
            }
        })();
        if let Ok(value) = result { fields.fallback(value); }
    }
    fields.fallback(Fields::from_info(&archive.info));
    fields.apply(&archive.info)
}
fn from_proto(bytes: &[u8]) -> Result<Fields> {
    let metadata = OtaMetadata::decode(bytes).map_err(|_| "OTA metadata protobuf 无效".to_string())?;
    Ok(metadata.postcondition.map(|p| target(&p.build.join("|"),&p.sdk_level,&p.security_patch_level)).unwrap_or_default())
}
fn properties(text: &str) -> BTreeMap<String,String> {
    let mut out = BTreeMap::new(); let mut conflicts = HashSet::new();
    for line in text.trim_start_matches('\u{feff}').lines() {
        let line = line.trim(); if line.is_empty() || line.starts_with('#') { continue; }
        let Some((key,value)) = line.split_once('=') else { continue; };
        let key = key.trim().to_string(); let value = clean(value);
        if out.get(&key).is_some_and(|old| old != &value) { conflicts.insert(key.clone()); }
        out.insert(key,value);
    }
    for key in conflicts { out.remove(&key); } out
}
fn from_text(text: &str,build_props: bool) -> Fields {
    let values = properties(text);
    let get = |key: &str| values.get(key).map(String::as_str).unwrap_or_default();
    if build_props {
        let fingerprint = get("ro.build.fingerprint");
        let fingerprint = if fingerprint.is_empty() { get("ro.system.build.fingerprint") } else { fingerprint };
        let sdk = get("ro.build.version.sdk"); let sdk = if sdk.is_empty() { get("ro.system.build.version.sdk") } else { sdk };
        let patch = get("ro.build.version.security_patch"); let patch = if patch.is_empty() { get("ro.system.build.version.security_patch") } else { patch };
        let mut result = target(fingerprint,sdk,patch);
        let release = clean(get("ro.build.version.release"));
        let release = if release.is_empty() { clean(get("ro.system.build.version.release")) } else { release };
        if !release.is_empty() { result.android = release; } result
    } else { target(get("post-build"),get("post-sdk-level"),get("post-security-patch-level")) }
}
fn clean(value: &str) -> String {
    let value = value.trim();
    if value.len() > 4096 || value.chars().any(|c| c.is_control()) || matches!(value.to_ascii_lowercase().as_str(),"unknown"|"null"|"—") {
        String::new()
    } else { value.into() }
}
fn target(build: &str,sdk: &str,patch: &str) -> Fields {
    let mut fingerprints = Vec::new(); let mut releases = HashSet::new();
    let cleaned = clean(build);
    for fp in cleaned.split('|').map(str::trim).filter(|s| !s.is_empty()) {
        if let Some(release) = fingerprint_release(fp) {
            if !fingerprints.contains(&fp) { fingerprints.push(fp); }
            releases.insert(release);
        } else { fingerprints.clear(); releases.clear(); break; }
    }
    let fingerprint = fingerprints.join("|");
    let android = if releases.len() == 1 { releases.into_iter().next().unwrap_or_default().to_string() }
        else if fingerprint.is_empty() { sdk_release(sdk).into() } else { String::new() };
    Fields { android,patch: clean_patch(patch),fingerprint,kernel: String::new() }
}
fn fingerprint_release(fp: &str) -> Option<&str> {
    if fp.chars().any(|c| c.is_whitespace()) { return None; }
    let parts: Vec<&str> = fp.split(':').collect();
    if parts.len() != 3 { return None; }
    let device: Vec<&str> = parts[0].split('/').collect(); let build: Vec<&str> = parts[1].split('/').collect(); let kind: Vec<&str> = parts[2].split('/').collect();
    if device.len() != 3 || build.len() != 3 || kind.len() != 2 || device.iter().chain(build.iter()).chain(kind.iter()).any(|s| s.is_empty()) { return None; }
    Some(build[0])
}
fn sdk_release(value: &str) -> &'static str {
    match value.trim().parse::<u32>() {
        Ok(19|20)=>"4.4",Ok(21)=>"5.0",Ok(22)=>"5.1",Ok(23)=>"6.0",Ok(24)=>"7.0",Ok(25)=>"7.1",Ok(26)=>"8.0",Ok(27)=>"8.1",Ok(28)=>"9",
        Ok(29)=>"10",Ok(30)=>"11",Ok(31)=>"12",Ok(32)=>"12L",Ok(33)=>"13",Ok(34)=>"14",Ok(35)=>"15",Ok(36)=>"16",_=>"",
    }
}
pub(crate) fn clean_patch(value: &str) -> String {
    let value = value.trim(); let bytes = value.as_bytes();
    if bytes.len() != 10 || bytes[4] != b'-' || bytes[7] != b'-' || bytes.iter().enumerate().any(|(i,b)| i != 4 && i != 7 && !b.is_ascii_digit()) { return String::new(); }
    let year = value[..4].parse::<u32>().unwrap_or_default(); let month = value[5..7].parse::<u32>().unwrap_or_default(); let day = value[8..].parse::<u32>().unwrap_or_default();
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days = match month { 1|3|5|7|8|10|12=>31,4|6|9|11=>30,2 if leap=>29,2=>28,_=>0 };
    if year == 0 || day == 0 || day > days { String::new() } else { value.into() }
}

pub(crate) fn kernel(archive: &Archive,cancel: &Cancel) -> Result<ArchiveInfo> {
    let duration = Duration::from_secs(10); let limited = cancel.with_deadline(duration);
    let mut fields = Fields::from_info(&archive.info);
    let boot = match &archive.kind {
        Kind::Payload(payload) => {
            let indexes: Vec<usize> = payload.manifest.partitions.iter().enumerate().filter(|(_,p)| p.partition_name == "boot").map(|(i,_)| i).collect();
            if indexes.len() != 1 { return Ok(archive.info.clone()); }
            let plan = payload::validate_plan(payload,indexes[0],&limited)?;
            let input = BudgetSource::make(payload.source.clone(),64*1024*1024,duration);
            let mut cache = HashMap::new();
            let prefix = reconstruct(payload,&plan,&input,0,plan.target_size.min(4096) as usize,&limited,&mut cache)?;
            let Some((offset,size,header_fields)) = boot_header(&prefix) else { return Ok(archive.info.clone()); };
            fields.fallback(header_fields);
            bounds(offset as u64,size as u64,plan.target_size)?;
            let kernel = reconstruct(payload,&plan,&input,offset as u64,size,&limited,&mut cache)?;
            Some(kernel)
        }
        Kind::Raw(items) => {
            let boots: Vec<_> = items.iter().filter(|i| i.name.eq_ignore_ascii_case("boot.img")).collect();
            if boots.len() != 1 { return Ok(archive.info.clone()); }
            let item = boots[0]; let input = BudgetSource::make(item.input.clone(),64*1024*1024,duration);
            let image = zip::read_small(&input,&item.range,KERNEL_LIMIT,&limited)?;
            let Some((offset,size,header_fields)) = boot_header(&image) else { return Ok(archive.info.clone()); };
            fields.fallback(header_fields);
            bounds(offset as u64,size as u64,image.len() as u64)?;
            Some(image[offset..offset+size].to_vec())
        }
    };
    if let Some(mut bytes) = boot {
        if bytes.starts_with(&[0x1f,0x8b]) {
            let mut decoder = flate2::read::GzDecoder::new(bytes.as_slice()); let mut output = Vec::new(); let mut buffer = [0u8;65536];
            loop {
                limited.check()?;
                let count = decoder.read(&mut buffer).map_err(|_| "gzip kernel 解码失败".to_string())?;
                if count == 0 { break; }
                if count > KERNEL_LIMIT.saturating_sub(output.len()) { return Err("kernel 解码超出 64 MiB 预算".into()); }
                output.extend_from_slice(&buffer[..count]);
            }
            bytes = output;
        } else if bytes.starts_with(&[0x04,0x22,0x4d,0x18]) || bytes.starts_with(&[0x02,0x21,0x4c,0x18]) {
            return Ok(fields.apply(&archive.info)); // LZ4 kernel metadata remains unknown; extraction is unaffected.
        }
        let release = kernel_banner(&bytes,&limited)?;
        if fields.kernel.is_empty() { fields.kernel = release; }
    }
    Ok(fields.apply(&archive.info))
}
fn reconstruct(_payload: &Payload,plan: &Plan,input: &Input,start: u64,length: usize,cancel: &Cancel,cache: &mut HashMap<usize,Vec<u8>>) -> Result<Vec<u8>> {
    if length == 0 || length > KERNEL_LIMIT { return Err("boot 元数据请求长度无效".into()); }
    let end = bounds(start,length as u64,plan.target_size)?;
    let mut output = vec![0u8;length]; let mut coverage = Vec::new();
    for (index,op) in plan.operations.iter().enumerate() {
        cancel.check()?;
        if !op.ranges.iter().any(|r| r.offset < end && r.offset+r.length > start) { continue; }
        if op.decoded_size > METADATA_OP_LIMIT || op.blob_length > METADATA_OP_LIMIT || op.operation.r#type == 7 { return Err("boot 操作超出元数据预算或 DISCARD 内容未知".into()); }
        let decoded = if let Some(bytes) = cache.get(&index) { bytes.clone() }
            else if op.operation.r#type == 6 { vec![0u8;op.decoded_size as usize] }
            else {
                let (mut spool,_) = payload::decode_operation(input,op,cancel)?;
                spool.file.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
                let mut bytes = vec![0u8;op.decoded_size as usize]; spool.file.read_exact(&mut bytes).map_err(|e| e.to_string())?;
                if cache.values().map(Vec::len).sum::<usize>()+bytes.len() <= METADATA_OP_LIMIT as usize { cache.insert(index,bytes.clone()); }
                bytes
            };
        let mut cursor = 0usize;
        for range in &op.ranges {
            let from = start.max(range.offset); let to = end.min(range.offset+range.length);
            if from < to {
                let destination = (from-start) as usize; let count = (to-from) as usize; let source = cursor+(from-range.offset) as usize;
                output[destination..destination+count].copy_from_slice(&decoded[source..source+count]);
                coverage.push((destination,destination+count));
            }
            cursor += range.length as usize;
        }
    }
    coverage.sort_unstable(); let mut end = 0usize;
    for (from,to) in coverage { if from != end { return Err("boot 元数据范围有缺口或 overlap".into()); } end = to; }
    if end != length { return Err("boot 元数据范围未完全覆盖".into()); }
    Ok(output)
}
fn boot_header(bytes: &[u8]) -> Option<(usize,usize,Fields)> {
    if bytes.len() < 48 || &bytes[..8] != b"ANDROID!" { return None; }
    let version = le32(bytes,40); if version > 4 { return None; }
    let size = le32(bytes,8) as usize; let page = if version >= 3 { 4096 } else { le32(bytes,36) as usize };
    if size == 0 || !(512..=65536).contains(&page) || !page.is_power_of_two() || page.checked_add(size)? > KERNEL_LIMIT { return None; }
    let packed = le32(bytes,if version >= 3 { 16 } else { 44 });
    let android = if packed >> 11 == 0 { String::new() } else { format!("{}.{}.{}",(packed>>25)&127,(packed>>18)&127,(packed>>11)&127) };
    let month = packed&15; let year = 2000+((packed>>4)&127);
    let patch = if (1..=12).contains(&month) { format!("{year:04}-{month:02}") } else { String::new() };
    Some((page,size,Fields { android,patch,..Fields::default() }))
}
fn kernel_banner(bytes: &[u8],cancel: &Cancel) -> Result<String> {
    let marker = b"Linux version "; let mut releases = HashSet::new();
    for (index,window) in bytes.windows(marker.len()).enumerate() {
        if index & 65535 == 0 { cancel.check()?; }
        if window != marker { continue; }
        let from = index+marker.len(); let tail = &bytes[from..bytes.len().min(from+192)];
        let Some(space) = tail.iter().position(|b| *b == b' ') else { continue; };
        if tail.get(space+1) != Some(&b'(') { continue; }
        let release = &tail[..space];
        if !release.iter().all(|b| b.is_ascii_alphanumeric() || b"._+-".contains(b)) { continue; }
        let text = std::str::from_utf8(release).unwrap_or_default(); let segments: Vec<&str> = text.split('.').collect();
        if segments.len() < 2 || segments[0].is_empty() || !segments[0].bytes().all(|b| b.is_ascii_digit()) || segments[1].is_empty() || !segments[1].bytes().all(|b| b.is_ascii_digit()) { continue; }
        releases.insert(text.to_string());
        if releases.len() > 1 { return Ok(String::new()); }
    }
    Ok(releases.into_iter().next().unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn target_only_and_conflicts() {
        let fields = from_text("post-build=Flux/Test/fixture:16/TEST/1:user/release-keys\npre-build=Old/Test/old:12/OLD/1:user/release-keys\npost-security-patch-level=2026-10-01\n",false);
        assert_eq!(fields.android,"16"); assert_eq!(fields.patch,"2026-10-01");
        assert!(from_text("post-build=a\npost-build=b\n",false).fingerprint.is_empty());
        assert!(clean_patch("2026-02-30").is_empty()); assert_eq!(clean_patch("2024-02-29"),"2024-02-29");
        assert_eq!(sdk_release("999"),"");
    }
    #[test] fn conflicting_kernel_banners_unknown() {
        assert_eq!(kernel_banner(b"Linux version 5.15.180-Gold_bug (builder)",&Cancel::default()).unwrap(),"5.15.180-Gold_bug");
        assert_eq!(kernel_banner(b"Linux version 5.15.180 (a)\0Linux version 6.6.10 (b)",&Cancel::default()).unwrap(),"");
    }
}
