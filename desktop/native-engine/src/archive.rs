use std::io::{Seek, SeekFrom};
use std::sync::Arc;
use sha2::{Digest, Sha256};
use crate::model::{ArchiveInfo, Row};
use crate::payload::{self, Payload};
use crate::source::{self, CachedSource, Input, SliceSource};
use crate::util::{add, bounds, hex, le32, Cancel, Result};
use crate::{paths, zip::{self, Entry, Range}};

#[derive(Clone)]
pub(crate) struct RawItem {
    pub key: String, pub name: String, pub subtitle: String, pub group: Option<String>,
    pub input: Input, pub range: Range, pub top: bool,
}
#[derive(Clone)]
pub(crate) struct MetadataEntry { pub input: Input, pub entry: Entry }
#[derive(Clone)]
pub(crate) enum Kind { Payload(Payload), Raw(Vec<RawItem>) }
#[derive(Clone)]
pub(crate) struct Archive {
    pub info: ArchiveInfo, pub kind: Kind, pub identity: String,
    pub metadata: Vec<MetadataEntry>, pub primary: Input,
    container_identity: String, is_zip: bool,
}
impl Archive {
    pub fn rows(&self) -> Result<Vec<Row>> {
        match &self.kind {
            Kind::Payload(payload) => payload.manifest.partitions.iter().map(|p| {
                let compressed = p.operations.iter().try_fold(0u64,|sum,o| add(sum,o.data_length.unwrap_or_default(),"partition compressed bytes"))?;
                let info = p.new_partition_info.as_ref();
                let size = info.and_then(|i| i.size).unwrap_or_default();
                let hash = info.and_then(|i| i.hash.as_deref()).map(hex).unwrap_or_default();
                Ok(Row::new(p.partition_name.clone(),p.partition_name.clone(),String::new(),size,compressed,hash))
            }).collect(),
            Kind::Raw(items) => Ok(items.iter().map(|i| Row::new(i.key.clone(),i.name.clone(),i.subtitle.clone(),i.range.size,i.range.compressed,String::new())).collect()),
        }
    }
    pub fn folder_name(&self) -> String { format!("firmware_{}",&self.identity[..16]) }
    pub fn validate_outputs(&self) -> Result<()> {
        match &self.kind {
            Kind::Payload(payload) => {
                for part in &payload.manifest.partitions { paths::partition(&part.partition_name)?; }
                paths::unique(payload.manifest.partitions.iter().map(|p| p.partition_name.as_str()))
            }
            Kind::Raw(items) => {
                let mut targets = Vec::new();
                for item in items {
                    paths::leaf(&item.name)?;
                    if let Some(group) = &item.group { paths::leaf(group)?; }
                    targets.push(format!("{}/{}",item.group.as_deref().unwrap_or(""),item.name));
                }
                paths::unique(targets.iter().map(String::as_str))?;
                paths::unique(items.iter().map(|i| i.key.as_str()))
            }
        }
    }
    // Check a reopened source BEFORE journal access or output mutations. Payload identity
    // is the complete raw manifest only; URL, file name and container offsets never bind it.
    pub fn reopened(&self,input: Input,cancel: &Cancel) -> Result<Archive> {
        match &self.kind {
            Kind::Payload(_) => {
                let mut next = parse(input,cancel,&|_|{})?;
                if next.identity != self.identity { return Err("固件内容或完整 manifest 已改变，请重新解析；不会复用旧进度".into()); }
                next.info = self.info.clone();
                Ok(next)
            }
            Kind::Raw(_) if self.is_zip => {
                let entries = zip::scan(&input,cancel)?;
                if zip_identity(&entries)? != self.container_identity { return Err("ZIP 内容或条目布局已改变，请重新解析".into()); }
                let mut next = self.clone();
                if let Kind::Raw(items) = &mut next.kind {
                    for item in items.iter_mut().filter(|i| i.top) { item.input = input.clone(); }
                }
                next.primary = input; Ok(next)
            }
            Kind::Raw(_) => {
                let mut next = parse(input,cancel,&|_|{})?;
                if next.identity != self.identity { return Err("UPDATE.APP 内容或布局已改变，请重新解析".into()); }
                next.info = self.info.clone(); Ok(next)
            }
        }
    }
}
fn zip_identity(entries: &[Entry]) -> Result<String> {
    Ok(hex(&crate::util::sha(&serde_json::to_vec(entries).map_err(|e| e.to_string())?)))
}
fn metadata_entries(input: &Input,entries: &[Entry]) -> Vec<MetadataEntry> {
    const NAMES: &[&str] = &["META-INF/com/android/metadata.pb","META-INF/com/android/metadata","system/system/build.prop","system/build.prop","SYSTEM/build.prop","build.prop"];
    entries.iter().filter(|e| NAMES.contains(&e.name.as_str())).map(|e| MetadataEntry { input: input.clone(),entry: e.clone() }).collect()
}
pub(crate) fn parse(input: Input,cancel: &Cancel,progress: &dyn Fn(&str)) -> Result<Archive> {
    if input.size() < 4 { return Err("固件文件过短".into()); }
    let prefix = input.read_exact(0,4,cancel)?;
    if &prefix == b"CrAU" {
        let payload = payload::parse(input.clone(),cancel)?;
        let identity = payload::identity(&payload);
        let mut info = ArchiveInfo::new(input.name(),input.size(),payload.manifest.partitions.len(),payload.block_size);
        info.security_patch_level = crate::metadata::clean_patch(payload.manifest.security_patch_level.as_deref().unwrap_or_default());
        return Ok(Archive { info,kind: Kind::Payload(payload),identity: identity.clone(),metadata: Vec::new(),primary: input,
            container_identity: identity,is_zip: false });
    }
    if le32(&prefix,0) == 0x04034b50 || le32(&prefix,0) == 0x06054b50 {
        let entries = zip::scan(&input,cancel)?;
        let metadata = metadata_entries(&input,&entries);
        let container_identity = zip_identity(&entries)?;
        let named: Vec<&Entry> = entries.iter().filter(|e| e.name.rsplit('/').next().is_some_and(|n| n.eq_ignore_ascii_case("payload.bin"))).collect();
        if named.len() > 1 { return Err("ZIP 含多个 payload.bin，拒绝选择猜测".into()); }
        let candidate = if let Some(entry) = named.first() { Some(*entry) }
            else if entries.len() == 1 && entries[0].method == 0 && entries[0].size >= 24 { Some(&entries[0]) } else { None };
        if let Some(entry) = candidate {
            if entry.method != 0 { return Err("OTA payload 必须为 STORED ZIP 条目，不能把大包整读内存".into()); }
            let range = zip::entry_range(&input,entry,cancel)?;
            let is_payload = range.size >= 24 && input.read_exact(range.offset,4,cancel)? == b"CrAU";
            if is_payload {
                let slice = SliceSource::make(input.clone(),range.offset,range.size,entry.name.clone())?;
                let payload = payload::parse(slice,cancel)?;
                let identity = payload::identity(&payload);
                let mut info = ArchiveInfo::new(input.name(),input.size(),payload.manifest.partitions.len(),payload.block_size);
                info.security_patch_level = crate::metadata::clean_patch(payload.manifest.security_patch_level.as_deref().unwrap_or_default());
                return Ok(Archive { info,kind: Kind::Payload(payload),identity,metadata,primary: input,container_identity,is_zip: true });
            } else if !named.is_empty() { return Err("payload.bin 条目缺少 CrAU 魔数".into()); }
        }
        let nested: Vec<&Entry> = entries.iter().filter(|e| e.name.to_ascii_lowercase().ends_with(".zip")
            && ["update_sd","main_ota"].iter().any(|s| e.name.to_ascii_lowercase().contains(s))).collect();
        let apps: Vec<&Entry> = entries.iter().filter(|e| e.name.rsplit('/').next().is_some_and(|n| n.eq_ignore_ascii_case("UPDATE.APP"))).collect();
        let mut items = Vec::new();
        for entry in entries.iter().filter(|e| is_image(&e.name)) { items.push(outer_item(&input,entry,cancel,None)?); }
        for entry in apps {
            progress(&format!("正在解析 {}",entry.name));
            let range = zip::entry_range(&input,entry,cancel)?;
            let group = Some(paths::archive_folder(&entry.name)?);
            if range.method == 0 {
                let source = SliceSource::make(input.clone(),range.offset,range.size,entry.name.clone())?;
                let (mut app_items,_) = update_app(source,cancel,group)?;
                for item in &mut app_items { item.input = input.clone(); item.range.offset += range.offset; item.top = true; }
                items.extend(app_items);
            } else {
                let cached = spool(&input,&range,cancel,progress,&entry.name)?;
                items.extend(update_app(cached,cancel,group)?.0);
            }
        }
        for entry in nested {
            progress(&format!("正在流式读取 {}",entry.name));
            let range = zip::entry_range(&input,entry,cancel)?;
            let group = Some(paths::archive_folder(&entry.name)?);
            let nested_items: Result<Vec<RawItem>> = (|| {
                let stored = range.method == 0;
                let nested_input = if stored { SliceSource::make(input.clone(),range.offset,range.size,entry.name.clone())? }
                    else { spool(&input,&range,cancel,progress,&entry.name)? };
                let inner = zip::scan(&nested_input,cancel)?;
                let images: Vec<&Entry> = inner.iter().filter(|i| !i.name.ends_with('/') && is_image(&i.name)).collect();
                let usable = if images.is_empty() { inner.iter().filter(|i| !i.name.ends_with('/') && i.size > 0).collect() } else { images };
                let mut result = Vec::new();
                for inner in usable {
                    let mut item = outer_item(&nested_input,inner,cancel,group.clone())?;
                    let label = entry.name.rsplit('/').next().unwrap_or(&entry.name);
                    item.key = format!("{label} :: {}",inner.name); item.subtitle = inner.name.clone();
                    if stored { item.input = input.clone(); item.range.offset += range.offset; item.top = true; }
                    else { item.top = false; }
                    result.push(item);
                }
                Ok(result)
            })();
            match nested_items {
                Ok(inner) if !inner.is_empty() => items.extend(inner),
                Ok(_) => items.push(outer_item(&input,entry,cancel,None)?),
                Err(error) => {
                    cancel.check()?;
                    if error.contains("路径") || error.contains("Windows") || error.contains("大小写") || error.contains("符号链接") { return Err(error); }
                    progress(&format!("{} 未能扫描，保留原 ZIP 条目：{error}",entry.name));
                    items.push(outer_item(&input,entry,cancel,None)?);
                }
            }
        }
        if items.is_empty() {
            for entry in entries.iter().filter(|e| !e.name.ends_with('/') && !metadata.iter().any(|m| m.entry.name == e.name)) {
                items.push(outer_item(&input,entry,cancel,None)?);
            }
        }
        if items.is_empty() { return Err("ZIP 没有可提取固件条目".into()); }
        let info = ArchiveInfo::new(input.name(),input.size(),items.len(),4096);
        let archive = Archive { info,kind: Kind::Raw(items),identity: container_identity.clone(),metadata,primary: input,container_identity,is_zip: true };
        archive.validate_outputs()?; return Ok(archive);
    }
    if is_app_magic(le32(&prefix,0)) {
        let (items,identity) = update_app(input.clone(),cancel,None)?;
        if items.is_empty() { return Err("UPDATE.APP 没有有效分区条目".into()); }
        let info = ArchiveInfo::new(input.name(),input.size(),items.len(),4096);
        let archive = Archive { info,kind: Kind::Raw(items),identity: identity.clone(),metadata: Vec::new(),primary: input,
            container_identity: identity,is_zip: false };
        archive.validate_outputs()?; return Ok(archive);
    }
    Err("无法识别固件魔数：支持 CrAU、OTA ZIP/ZIP64、普通 ZIP、UPDATE.APP 与 SD 嵌套 ZIP".into())
}
fn is_image(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    [".img",".bin",".elf",".mbn",".melf",".new.dat",".new.dat.br",".transfer.list"].iter().any(|e| lower.ends_with(e))
}
fn outer_item(input: &Input,entry: &Entry,cancel: &Cancel,group: Option<String>) -> Result<RawItem> {
    let name = paths::archive_path(&entry.name)?;
    let range = zip::entry_range(input,entry,cancel)?;
    Ok(RawItem { key: entry.name.clone(),name,subtitle: entry.name.clone(),group,input: input.clone(),range,top: true })
}
fn spool(input: &Input,range: &Range,cancel: &Cancel,progress: &dyn Fn(&str),name: &str) -> Result<Input> {
    let mut spool = source::spool_file(range.size)?;
    let last = std::sync::atomic::AtomicUsize::new(101);
    zip::copy_range(input,range,&mut spool.file,cancel,&|f| {
        let percent = (f*100.0).min(100.0) as usize;
        if last.swap(percent,std::sync::atomic::Ordering::AcqRel) != percent { progress(&format!("流式缓存 {name} — {percent}%")); }
    })?;
    if spool.file.metadata().map_err(|e| e.to_string())?.len() != range.size { return Err("嵌套 ZIP 缓存实际长度不一致".into()); }
    spool.file.sync_all().map_err(|e| format!("同步嵌套缓存失败：{e}"))?;
    let source::Spool { file,guard } = spool;
    drop(file); CachedSource::make(guard)
}
fn is_app_magic(magic: u32) -> bool { magic == 0x55aa55aa || magic == 0xaa55aa55 }
fn update_app(input: Input,cancel: &Cancel,group: Option<String>) -> Result<(Vec<RawItem>,String)> {
    let mut items = Vec::new(); let mut position = 0u64; let mut index = 0usize; let mut digest = Sha256::new();
    while position + 32 <= input.size() {
        cancel.check()?;
        let prefix = input.read_exact(position,32,cancel)?;
        if !is_app_magic(le32(&prefix,0)) {
            let max = (input.size()-position).min(1024*1024); let mut skipped = 0u64; let mut found = None;
            while skipped + 4 <= max {
                let count = (max-skipped).min(64*1024) as usize;
                let chunk = input.read_exact(position+skipped,count,cancel)?;
                if let Some(at) = (0..chunk.len().saturating_sub(3)).find(|&p| is_app_magic(le32(&chunk,p))) { found = Some(position+skipped+at as u64); break; }
                if count < 4 { break; } skipped += count as u64-3;
            }
            if let Some(next) = found { position = next; continue; } else { break; }
        }
        let header_size = le32(&prefix,4) as u64;
        if !(96..=65536).contains(&header_size) { return Err("UPDATE.APP header size 无效".into()); }
        bounds(position,header_size,input.size())?;
        let header = input.read_exact(position,header_size as usize,cancel)?;
        let low = le32(&header,24) as u64; let high = le32(&header,28) as u64;
        // The traditional 98-byte format has date text at 28, not a high size word.
        // Extended desktop-compatible headers use a binary high word. Only accept it
        // when the combined 64-bit range is actually within this bounded container.
        let combined = high.checked_shl(32).and_then(|h| h.checked_add(low));
        let data = position+header_size;
        let size = if header_size >= 512 && high < 0x10000 && combined.is_some_and(|n| n <= input.size()-data) { combined.unwrap() } else { low };
        bounds(data,size,input.size())?;
        let standard_name = if header_size < 512 { printable_name(&header,60,16) } else { None };
        let name = standard_name.or_else(|| printable_name(&header,96,(header.len()-96).min(64))).unwrap_or_else(|| format!("entry_{index}"));
        let leaf = paths::archive_path(&name)?;
        digest.update(position.to_be_bytes()); digest.update(&header); digest.update(size.to_be_bytes());
        if size > 0 {
            let key = group.as_ref().map(|g| format!("{g} :: {name}")).unwrap_or_else(|| name.clone());
            items.push(RawItem { key,name: leaf,subtitle: name,group: group.clone(),input: input.clone(),
                range: Range { offset: data,compressed: size,size,method: 0,crc: None },top: group.is_none() });
        }
        position = bounds(data,size,input.size())?;
        position = position.checked_add(3).map(|v| v & !3).ok_or_else(|| "UPDATE.APP alignment 溢出".to_string())?;
        index += 1;
        if index > 100_000 { return Err("UPDATE.APP 条目数超出预算".into()); }
    }
    Ok((items,hex(&digest.finalize())))
}
fn printable_name(header: &[u8],start: usize,length: usize) -> Option<String> {
    let bytes = header.get(start..start+length)?;
    let bytes = &bytes[..bytes.iter().position(|b| *b == 0).unwrap_or(bytes.len())];
    let name = std::str::from_utf8(bytes).ok()?.trim();
    if name.len() < 2 || name.len() > 64 || !name.bytes().all(|c| c.is_ascii_alphanumeric() || b"_./-".contains(&c)) { None } else { Some(name.into()) }
}
