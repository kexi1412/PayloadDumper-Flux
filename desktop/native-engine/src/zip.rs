use std::io::Write;
use serde::Serialize;
use flate2::{Decompress, FlushDecompress, Status};
use crate::paths;
use crate::source::Input;
use crate::util::{bounds, le16, le32, le64, Cancel, Result, CHUNK};

#[derive(Clone, Debug, Serialize)]
pub(crate) struct Entry {
    pub name: String, pub flags: u16, pub method: u16, pub crc: u32,
    pub compressed: u64, pub size: u64, pub local_offset: u64,
}
#[derive(Clone, Debug, Serialize)]
pub(crate) struct Range {
    pub offset: u64, pub compressed: u64, pub size: u64, pub method: u16, pub crc: Option<u32>,
}
const MAX_CD: u64 = 64 * 1024 * 1024;
const MAX_ENTRIES: u64 = 100_000;

pub(crate) fn scan(input: &Input, cancel: &Cancel) -> Result<Vec<Entry>> {
    if input.size() < 22 { return Err("ZIP EOCD 截断".into()); }
    let count = input.size().min(65577) as usize;
    let start = input.size() - count as u64;
    let tail = input.read_exact(start, count, cancel)?;
    let eocd = (0..=tail.len()-22).rev().find(|&i| le32(&tail, i) == 0x06054b50
        && i + 22 + le16(&tail, i+20) as usize == tail.len()).ok_or_else(|| "ZIP EOCD 未找到".to_string())?;
    if le16(&tail,eocd+4) != 0 || le16(&tail,eocd+6) != 0 { return Err("不支持多卷 ZIP".into()); }
    let mut entries = le16(&tail,eocd+10) as u64;
    let mut cd_size = le32(&tail,eocd+12) as u64;
    let mut cd_offset = le32(&tail,eocd+16) as u64;
    let mut directory_end = start + eocd as u64;
    if entries == 0xffff || le16(&tail,eocd+8) == 0xffff || cd_size == 0xffff_ffff || cd_offset == 0xffff_ffff {
        if eocd < 20 || le32(&tail,eocd-20) != 0x07064b50 || le32(&tail,eocd-16) != 0 || le32(&tail,eocd-4) != 1 {
            return Err("ZIP64 locator 无效或多卷 ZIP".into());
        }
        let offset = le64(&tail,eocd-12);
        bounds(offset, 56, input.size())?;
        let record = input.read_exact(offset, 56, cancel)?;
        if le32(&record,0) != 0x06064b50 || le64(&record,4) < 44 || le32(&record,16) != 0 || le32(&record,20) != 0
            || le64(&record,24) != le64(&record,32) {
            return Err("ZIP64 EOCD 无效".into());
        }
        bounds(offset, 12 + le64(&record,4), start + eocd as u64 - 20)?;
        entries = le64(&record,32); cd_size = le64(&record,40); cd_offset = le64(&record,48);
        directory_end = offset;
    } else if le16(&tail,eocd+8) as u64 != entries { return Err("ZIP entry count 不一致".into()); }
    if entries > MAX_ENTRIES || cd_size > MAX_CD { return Err("ZIP 中央目录超出有界预算".into()); }
    bounds(cd_offset, cd_size, directory_end)?;
    let cd = input.read_exact(cd_offset, cd_size as usize, cancel)?;
    let mut out = Vec::with_capacity(entries as usize); let mut p = 0;
    for _ in 0..entries {
        cancel.check()?;
        if p + 46 > cd.len() || le32(&cd,p) != 0x02014b50 { return Err("ZIP 中央目录截断或签名无效".into()); }
        let flags = le16(&cd,p+8); let method = le16(&cd,p+10);
        let name_len = le16(&cd,p+28) as usize; let extra_len = le16(&cd,p+30) as usize; let comment_len = le16(&cd,p+32) as usize;
        let next = p + 46 + name_len + extra_len + comment_len;
        if next > cd.len() || name_len == 0 { return Err("ZIP 目录条目长度无效".into()); }
        let name = std::str::from_utf8(&cd[p+46..p+46+name_len]).map_err(|_| "ZIP 文件名不是有效 UTF-8".to_string())?.to_string();
        paths::archive_path(&name)?;
        let unix_type = (le32(&cd,p+38) >> 16) & 0xf000;
        if unix_type == 0xa000 { return Err("ZIP 符号链接条目已拒绝".into()); }
        let mut size = le32(&cd,p+24) as u64; let mut compressed = le32(&cd,p+20) as u64; let mut local_offset = le32(&cd,p+42) as u64;
        let mut disk = le16(&cd,p+34) as u32;
        if size == 0xffff_ffff || compressed == 0xffff_ffff || local_offset == 0xffff_ffff || disk == 0xffff {
            let extra = &cd[p+46+name_len..p+46+name_len+extra_len];
            let z = extra_field(extra,1)?.ok_or_else(|| "缺少 ZIP64 extended info".to_string())?;
            let mut zpos = 0;
            if size == 0xffff_ffff { size = take64(z,&mut zpos)?; }
            if compressed == 0xffff_ffff { compressed = take64(z,&mut zpos)?; }
            if local_offset == 0xffff_ffff { local_offset = take64(z,&mut zpos)?; }
            if disk == 0xffff {
                if zpos + 4 > z.len() { return Err("ZIP64 disk 字段截断".into()); } disk = le32(z,zpos);
            }
        }
        if disk != 0 { return Err("不支持多卷 ZIP".into()); }
        if size > i64::MAX as u64 || compressed > i64::MAX as u64 { return Err("ZIP64 大小溢出".into()); }
        bounds(local_offset,30,cd_offset)?;
        out.push(Entry { name, flags, method, crc: le32(&cd,p+16), compressed, size, local_offset });
        p = next;
    }
    if p != cd.len() {
        if p + 6 > cd.len() || le32(&cd,p) != 0x05054b50 || p + 6 + le16(&cd,p+4) as usize != cd.len() {
            return Err("ZIP 目录存在未解释字节或计数不匹配".into());
        }
    }
    paths::unique(out.iter().map(|e| e.name.as_str()))?;
    Ok(out)
}
fn extra_field(extra: &[u8], wanted: u16) -> Result<Option<&[u8]>> {
    let mut p = 0; let mut found = None;
    while p < extra.len() {
        if p + 4 > extra.len() { return Err("ZIP extra header 截断".into()); }
        let id = le16(extra,p); let len = le16(extra,p+2) as usize; p += 4;
        if p + len > extra.len() { return Err("ZIP extra 字段超出边界".into()); }
        if id == wanted {
            if found.is_some() { return Err("ZIP extra 字段重复".into()); } found = Some(&extra[p..p+len]);
        }
        p += len;
    }
    Ok(found)
}
fn take64(bytes: &[u8], pos: &mut usize) -> Result<u64> {
    if *pos + 8 > bytes.len() { return Err("ZIP64 extra 截断".into()); }
    let value = le64(bytes,*pos); *pos += 8; Ok(value)
}
pub(crate) fn entry_range(input: &Input, entry: &Entry, cancel: &Cancel) -> Result<Range> {
    if entry.flags & 0x2041 != 0 { return Err("不支持加密 ZIP 条目".into()); }
    if !matches!(entry.method,0|8) { return Err(format!("不支持 ZIP 压缩方法 {}",entry.method)); }
    bounds(entry.local_offset,30,input.size())?;
    let h = input.read_exact(entry.local_offset,30,cancel)?;
    if le32(&h,0) != 0x04034b50 || le16(&h,6) != entry.flags || le16(&h,8) != entry.method { return Err("ZIP local/central header 不一致".into()); }
    let name_len = le16(&h,26) as usize; let extra_len = le16(&h,28) as usize;
    let offset = bounds(entry.local_offset,30+name_len as u64+extra_len as u64,input.size())?;
    let variable = input.read_exact(entry.local_offset+30,name_len+extra_len,cancel)?;
    if &variable[..name_len] != entry.name.as_bytes() { return Err("ZIP local/central 文件名不一致".into()); }
    if entry.flags & 8 == 0 {
        let mut compressed = le32(&h,18) as u64; let mut size = le32(&h,22) as u64;
        if size == 0xffff_ffff || compressed == 0xffff_ffff {
            let z = extra_field(&variable[name_len..],1)?.ok_or_else(|| "ZIP64 local extra 缺失".to_string())?;
            let mut p = 0;
            if size == 0xffff_ffff { size = take64(z,&mut p)?; }
            if compressed == 0xffff_ffff { compressed = take64(z,&mut p)?; }
        }
        if le32(&h,14) != entry.crc || compressed != entry.compressed || size != entry.size { return Err("ZIP local/central 长度或 CRC 不一致".into()); }
    }
    bounds(offset,entry.compressed,input.size())?;
    if entry.method == 0 && entry.compressed != entry.size { return Err("ZIP STORED 长度不一致".into()); }
    Ok(Range { offset, compressed: entry.compressed, size: entry.size, method: entry.method, crc: Some(entry.crc) })
}

pub(crate) fn copy_range(input: &Input, range: &Range, output: &mut dyn Write, cancel: &Cancel, progress: &dyn Fn(f64)) -> Result<()> {
    bounds(range.offset,range.compressed,input.size())?;
    if !matches!(range.method,0|8) || range.method == 0 && range.compressed != range.size { return Err("固件条目压缩方法或长度无效".into()); }
    let mut crc = crc32fast::Hasher::new(); let mut produced = 0u64;
    if range.method == 0 {
        let mut consumed = 0u64;
        while consumed < range.compressed {
            cancel.check()?;
            let count = (range.compressed-consumed).min(CHUNK as u64) as usize;
            let bytes = input.read_exact(range.offset+consumed,count,cancel)?;
            output.write_all(&bytes).map_err(|e| format!("输出写入失败：{e}"))?;
            crc.update(&bytes); consumed += count as u64; produced += count as u64;
            progress(if range.size == 0 { 1.0 } else { produced as f64 / range.size as f64 });
        }
    } else {
        let mut decoder = Decompress::new(false); let mut incoming = Vec::new(); let mut cursor = 0usize;
        let mut loaded = 0u64; let mut outgoing = vec![0u8; CHUNK];
        loop {
            cancel.check()?;
            if cursor == incoming.len() && loaded < range.compressed {
                let count = (range.compressed-loaded).min(CHUNK as u64) as usize;
                incoming = input.read_exact(range.offset+loaded,count,cancel)?; cursor = 0; loaded += count as u64;
            }
            let before_in = decoder.total_in(); let before_out = decoder.total_out();
            let flush = if loaded == range.compressed { FlushDecompress::Finish } else { FlushDecompress::None };
            let status = decoder.decompress(&incoming[cursor..],&mut outgoing,flush).map_err(|_| "无效 ZIP DEFLATE 数据".to_string())?;
            let used = (decoder.total_in()-before_in) as usize; let count = (decoder.total_out()-before_out) as usize;
            cursor += used;
            if count as u64 > range.size.saturating_sub(produced) { return Err("ZIP 解码超出声明长度".into()); }
            output.write_all(&outgoing[..count]).map_err(|e| format!("输出写入失败：{e}"))?;
            crc.update(&outgoing[..count]); produced += count as u64;
            progress(if range.size == 0 { 1.0 } else { produced as f64 / range.size as f64 });
            if status == Status::StreamEnd {
                if decoder.total_in() != range.compressed { return Err("ZIP DEFLATE 压缩长度不精确或存在尾随数据".into()); }
                break;
            }
            if used == 0 && count == 0 { return Err("ZIP DEFLATE 截断或解码没有进展".into()); }
        }
    }
    cancel.check()?;
    if produced != range.size { return Err("固件条目实际输出长度不匹配".into()); }
    if range.crc.is_some_and(|expected| crc.finalize() != expected) { return Err("ZIP CRC32 校验失败".into()); }
    Ok(())
}
pub(crate) fn read_small(input: &Input, range: &Range, limit: usize, cancel: &Cancel) -> Result<Vec<u8>> {
    if range.size > limit as u64 || range.compressed > limit as u64 { return Err("元数据条目超出字节预算".into()); }
    let mut out = Vec::with_capacity(range.size as usize);
    copy_range(input,range,&mut out,cancel,&|_|{})?;
    Ok(out)
}
