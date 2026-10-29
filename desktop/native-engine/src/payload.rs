use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::sync::{atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering}, Condvar, Mutex};
use std::time::Duration;
use prost::Message;
use sha2::{Digest, Sha256};
use crate::journal::Journal;
use crate::proto::{DeltaArchiveManifest, InstallOperation, PartitionUpdate};
use crate::source::{self, Input, Spool};
use crate::{paths, util::{add, bounds, hex, mul, sha, Cancel, Result, CHUNK}};

pub(crate) const MAX_OPERATIONS: usize = 1_000_000;
const MAX_OP_BYTES: u64 = 128 * 1024 * 1024;
#[derive(Clone)]
pub(crate) struct Payload {
    pub manifest: DeltaArchiveManifest, pub raw_manifest: Vec<u8>,
    pub data_offset: u64, pub block_size: u32, pub source: Input,
    type_present: Vec<Vec<bool>>,
}
#[derive(Clone, Debug)]
pub(crate) struct OutputRange { pub offset: u64, pub length: u64 }
#[derive(Clone)]
pub(crate) struct OperationPlan {
    pub operation: InstallOperation, pub ranges: Vec<OutputRange>,
    pub decoded_size: u64, pub blob_offset: u64, pub blob_length: u64,
}
#[derive(Clone)]
pub(crate) struct Plan { pub operations: Vec<OperationPlan>, pub target_size: u64, pub total: u64, pub all_ranges: Vec<OutputRange> }
pub(crate) struct Extracted { pub verified: Option<bool> }

// prost's generated proto2 "required enum" is an i32 and has no presence bit.
// Decode a tiny parallel presence schema, so a missing type never becomes REPLACE.
#[derive(Clone, PartialEq, Message)]
struct ManifestPresence { #[prost(message,repeated,tag="13")] partitions: Vec<PartitionPresence> }
#[derive(Clone, PartialEq, Message)]
struct PartitionPresence { #[prost(message,repeated,tag="8")] operations: Vec<TypePresence> }
#[derive(Clone, PartialEq, Message)]
struct TypePresence { #[prost(int32,optional,tag="1")] kind: Option<i32> }

pub(crate) fn parse(input: Input,cancel: &Cancel) -> Result<Payload> {
    if input.size() < 24 { return Err("payload header 截断".into()); }
    let header = input.read_exact(0,24,cancel)?;
    if &header[..4] != b"CrAU" { return Err("不是 CrAU payload".into()); }
    let version = u64::from_be_bytes(header[4..12].try_into().unwrap());
    if version != 2 { return Err(format!("不支持 payload major version {version}")); }
    let size = u64::from_be_bytes(header[12..20].try_into().unwrap());
    let signature = u32::from_be_bytes(header[20..24].try_into().unwrap()) as u64;
    if size == 0 || size > 32 * 1024 * 1024 { return Err("payload manifest 超过 32 MiB 预算或为空".into()); }
    let end = bounds(24,size,input.size())?;
    let data_offset = bounds(end,signature,input.size())?;
    let raw_manifest = input.read_exact(24,size as usize,cancel)?;
    let manifest = DeltaArchiveManifest::decode(raw_manifest.as_slice()).map_err(|e| format!("payload manifest protobuf 无效：{e}"))?;
    let presence = ManifestPresence::decode(raw_manifest.as_slice()).map_err(|e| format!("payload operation presence 无效：{e}"))?;
    if manifest.partitions.is_empty() || manifest.partitions.len() > 10_000 { return Err("manifest 分区数无效".into()); }
    let block_size = manifest.block_size.unwrap_or(4096);
    if block_size == 0 { return Err("payload block size 无效".into()); }
    for part in &manifest.partitions { paths::partition(&part.partition_name)?; }
    paths::unique(manifest.partitions.iter().map(|p| p.partition_name.as_str()))?;
    let type_present = presence.partitions.into_iter().map(|p| p.operations.into_iter().map(|o| o.kind.is_some()).collect()).collect();
    Ok(Payload { manifest,raw_manifest,data_offset,block_size,source: input,type_present })
}
pub(crate) fn identity(payload: &Payload) -> String { hex(&sha(&payload.raw_manifest)) }
pub(crate) fn resume_identity(payload: &Payload,name: &str) -> String {
    let mut h = Sha256::new(); h.update(b"Flux native payload resume v3\0");
    h.update((payload.raw_manifest.len() as u64).to_be_bytes()); h.update(&payload.raw_manifest);
    h.update((name.len() as u32).to_be_bytes()); h.update(name.as_bytes()); h.update(payload.block_size.to_be_bytes());
    hex(&h.finalize())
}
fn operation_label(kind: i32) -> String {
    let name = match kind { 0=>"REPLACE",1=>"REPLACE_BZ",2=>"MOVE",3=>"BSDIFF",4=>"SOURCE_COPY",5=>"SOURCE_BSDIFF",6=>"ZERO",7=>"DISCARD",8=>"REPLACE_XZ",9=>"PUFFDIFF",10=>"BROTLI_BSDIFF",11=>"ZUCCHINI",12=>"LZ4DIFF_BSDIFF",13=>"LZ4DIFF_PUFFDIFF",_=>"UNKNOWN" };
    format!("{name}({kind})")
}
pub(crate) fn validate_plan(payload: &Payload,index: usize,cancel: &Cancel) -> Result<Plan> {
    let part = payload.manifest.partitions.get(index).ok_or_else(|| "分区不属于当前 manifest".to_string())?;
    paths::partition(&part.partition_name)?;
    let presence = payload.type_present.get(index).ok_or_else(|| "manifest presence 不一致".to_string())?;
    if presence.len() != part.operations.len() || part.operations.len() > MAX_OPERATIONS { return Err("操作数或 presence 不一致".into()); }
    // Check the WHOLE semantic plan before any blob read, output creation, or resume load.
    // minor_version is compatibility metadata, NEVER the source-dependency criterion.
    for (i,op) in part.operations.iter().enumerate() {
        cancel.check()?;
        let reason = if !presence[i] { Some("missing operation type") }
            else if matches!(op.r#type,2|3|4|5|9|10|11|12|13) { Some("incremental operation requires old/source partition data") }
            else if !matches!(op.r#type,0|1|6|7|8) { Some("unknown operation type; decoding must not be guessed") }
            else if !op.src_extents.is_empty() { Some("references src_extents; old/source partition data is required") }
            else if op.src_sha256_hash.as_ref().is_some_and(|h| !h.is_empty()) { Some("references src_sha256_hash; old/source partition data is required") }
            else { None };
        // Unused src_length on REPLACE/BZ/XZ/ZERO/DISCARD is intentionally ignored.
        if let Some(reason) = reason { return Err(format!("Partition '{}' operation[{i}] ({}): {reason}",part.partition_name,operation_label(op.r#type))); }
    }
    let mut operations = Vec::with_capacity(part.operations.len()); let mut all_ranges = Vec::new();
    let mut allocated = 0u64; let mut total = 0u64;
    for op in &part.operations {
        cancel.check()?;
        if op.dst_extents.is_empty() { return Err("操作没有目标 extents".into()); }
        let mut decoded_size = 0u64; let mut ranges = Vec::with_capacity(op.dst_extents.len());
        for extent in &op.dst_extents {
            let start = extent.start_block.ok_or_else(|| "extent 缺少 start_block".to_string())?;
            let blocks = extent.num_blocks.filter(|v| *v > 0).ok_or_else(|| "extent num_blocks 无效".to_string())?;
            let offset = mul(start,payload.block_size as u64,"extent position")?;
            let length = mul(blocks,payload.block_size as u64,"extent length")?;
            allocated = allocated.max(add(offset,length,"extent end")?);
            decoded_size = add(decoded_size,length,"decoded length")?;
            let range = OutputRange { offset,length }; all_ranges.push(range.clone()); ranges.push(range);
        }
        // dst_length, like src_length, is defined only for the rejected BSDIFF types.
        // Capacity and exact decoder length come from dst_extents, never this unused hint.
        total = add(total,decoded_size,"total decoded bytes")?;
        let blob_length = op.data_length.unwrap_or_default();
        let blob_offset = add(payload.data_offset,op.data_offset.unwrap_or_default(),"blob offset")?;
        bounds(blob_offset,blob_length,payload.source.size())?;
        let hash = op.data_sha256_hash.as_deref().unwrap_or_default();
        if !hash.is_empty() && hash.len() != 32 { return Err("操作 SHA-256 长度无效".into()); }
        if matches!(op.r#type,6|7) {
            if blob_length != 0 { return Err("ZERO/DISCARD 不得携带 blob".into()); }
            if !hash.is_empty() && hash != sha(&[]).as_slice() { return Err("空操作 blob SHA-256 不匹配".into()); }
        } else {
            if op.r#type == 0 && blob_length != decoded_size { return Err("REPLACE 长度与目标 extents 不一致".into()); }
            if blob_length == 0 { return Err("压缩操作 blob 为空".into()); }
        }
        operations.push(OperationPlan { operation: op.clone(),ranges,decoded_size,blob_offset,blob_length });
    }
    let target_size = part.new_partition_info.as_ref().and_then(|p| p.size).unwrap_or(allocated);
    if target_size > i64::MAX as u64 || allocated > target_size { return Err("目标 extents 超出 new partition size".into()); }
    let hash = part.new_partition_info.as_ref().and_then(|p| p.hash.as_deref()).unwrap_or_default();
    if !hash.is_empty() && hash.len() != 32 { return Err("分区 SHA-256 长度无效".into()); }
    all_ranges.sort_by_key(|r| r.offset);
    let mut previous = 0u64;
    for range in &all_ranges {
        if range.offset < previous { return Err("目标 extents overlap，拒绝顺序相关写入".into()); }
        previous = add(range.offset,range.length,"extent end")?;
    }
    Ok(Plan { operations,target_size,total,all_ranges })
}

pub(crate) fn extract(payload: &Payload,part: &PartitionUpdate,plan: &Plan,directory: &Path,workers: usize,verify: bool,cancel: &Cancel,
    progress: &(dyn Fn(f64)+Sync)) -> Result<Extracted> {
    cancel.check()?;
    let target = paths::target(directory,&format!("{}.img",part.partition_name))?;
    let mut journal = Journal::load(directory,&part.partition_name,resume_identity(payload,&part.partition_name),plan.operations.len(),plan.target_size,&target,cancel)?;
    let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&target).map_err(|e| format!("创建镜像失败：{e}"))?;
    let mut reusable = vec![false;plan.operations.len()];
    for (index,record) in journal.completed.clone() {
        cancel.check()?;
        let op = &plan.operations[index];
        let hash = hash_ranges(&mut file,&op.ranges,cancel)?;
        let expected_raw = op.operation.data_sha256_hash.as_deref().unwrap_or_default();
        let raw_match = op.operation.r#type != 0 || expected_raw.is_empty() || hex(expected_raw) == hash;
        let zero_match = !matches!(op.operation.r#type,6|7) || hash == zero_hash(op.decoded_size,cancel)?;
        if record.length == op.decoded_size && record.sha256 == hash && raw_match && zero_match { reusable[index] = true; }
        else { journal.discard(index)?; }
    }
    if !reusable.iter().any(|v| *v) { file.set_len(0).map_err(|e| e.to_string())?; }
    file.set_len(plan.target_size).map_err(|e| format!("设置镜像长度失败：{e}"))?;
    // Uncovered holes must stay zero even when a paused image was externally modified.
    if reusable.iter().any(|v| *v) {
        let mut cursor = 0;
        for range in &plan.all_ranges {
            if range.offset > cursor { write_zeros(&mut file,cursor,range.offset-cursor,cancel)?; }
            cursor = range.offset+range.length;
        }
        if cursor < plan.target_size { write_zeros(&mut file,cursor,plan.target_size-cursor,cancel)?; }
    }
    let initial: u64 = plan.operations.iter().zip(&reusable).filter(|(_,done)| **done).map(|(op,_)| op.decoded_size).sum();
    let done = AtomicU64::new(initial); let next = AtomicUsize::new(0); let failed = AtomicBool::new(false);
    let errors = Mutex::new(Vec::<String>::new()); let output = Mutex::new(file); let journal = Mutex::new(journal);
    let memory = (Mutex::new(0usize),Condvar::new());
    let fraction = |v: u64| (v as f64 / plan.total.max(1) as f64).min(0.99);
    progress(fraction(initial));
    std::thread::scope(|scope| {
        for _ in 0..workers.min(plan.operations.len()).max(1) {
            scope.spawn(|| {
                while !failed.load(Ordering::Acquire) && !cancel.is_cancelled() {
                    let index = next.fetch_add(1,Ordering::AcqRel);
                    if index >= plan.operations.len() { break; }
                    if reusable[index] { continue; }
                    let op = &plan.operations[index];
                    let result: Result<()> = (|| {
                        let amount = match op.operation.r#type { 8 => 128,1 => 8,_ => 1 };
                        let _permit = MemoryPermit::acquire(&memory,amount,cancel,&failed)?;
                        let hash = process_operation(&payload.source,op,&output,cancel)?;
                        cancel.check()?;
                        // fd sync before the atomically replaced journal commit.
                        journal.lock().map_err(|_| "进度日志锁失效".to_string())?.mark(index,hash,op.decoded_size)?;
                        Ok(())
                    })();
                    match result {
                        Ok(()) => progress(fraction(done.fetch_add(op.decoded_size,Ordering::AcqRel)+op.decoded_size)),
                        Err(error) => { failed.store(true,Ordering::Release); errors.lock().unwrap().push(error); break; }
                    }
                }
            });
        }
    });
    cancel.check()?;
    if let Some(error) = errors.lock().unwrap().first().cloned() { return Err(error); }
    let mut file = output.into_inner().map_err(|_| "输出文件锁失效".to_string())?;
    file.sync_all().map_err(|e| format!("同步输出镜像失败：{e}"))?;
    if file.metadata().map_err(|e| e.to_string())?.len() != plan.target_size { return Err("镜像实际长度改变".into()); }
    let expected = part.new_partition_info.as_ref().and_then(|p| p.hash.as_deref()).unwrap_or_default();
    let verified = if verify && !expected.is_empty() {
        let actual = hash_file(&mut file,plan.target_size,cancel)?;
        Some(actual == hex(expected))
    } else { None };
    if verified == Some(false) { journal.into_inner().map_err(|_| "日志锁失效".to_string())?.clear()?; }
    else { progress(1.0); }
    Ok(Extracted { verified })
}
struct MemoryPermit<'a> { gate: &'a (Mutex<usize>,Condvar), amount: usize }
impl<'a> MemoryPermit<'a> {
    fn acquire(gate: &'a (Mutex<usize>,Condvar),amount: usize,cancel: &Cancel,failed: &AtomicBool) -> Result<Self> {
        let mut used = gate.0.lock().map_err(|_| "解码预算锁失效".to_string())?;
        loop {
            cancel.check()?;
            if failed.load(Ordering::Acquire) { return Err("分区操作已停止".into()); }
            if *used+amount <= 256 { *used += amount; return Ok(Self { gate,amount }); }
            used = gate.1.wait_timeout(used,Duration::from_millis(50)).map_err(|_| "解码预算等待失败".to_string())?.0;
        }
    }
}
impl Drop for MemoryPermit<'_> { fn drop(&mut self) { if let Ok(mut used) = self.gate.0.lock() { *used -= self.amount; self.gate.1.notify_all(); } } }

fn process_operation(input: &Input,op: &OperationPlan,output: &Mutex<File>,cancel: &Cancel) -> Result<String> {
    cancel.check()?;
    if matches!(op.operation.r#type,6|7) {
        let mut file = output.lock().map_err(|_| "输出锁失效".to_string())?;
        for range in &op.ranges { write_zeros(&mut file,range.offset,range.length,cancel)?; }
        cancel.check()?;
        file.sync_all().map_err(|e| format!("同步操作输出失败：{e}"))?;
        return zero_hash(op.decoded_size,cancel);
    }
    // Stream into a bounded temporary file, not a giant blob/decoded Vec. Nothing is
    // scattered until the compressed SHA, exact decoder length and trailing bytes pass.
    let (mut decoded,hash) = decode_operation(input,op,cancel)?;
    decoded.file.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
    let mut file = output.lock().map_err(|_| "输出锁失效".to_string())?;
    let mut buffer = vec![0u8;CHUNK];
    for range in &op.ranges {
        file.seek(SeekFrom::Start(range.offset)).map_err(|e| e.to_string())?;
        let mut remaining = range.length;
        while remaining > 0 {
            cancel.check()?; let count = remaining.min(CHUNK as u64) as usize;
            decoded.file.read_exact(&mut buffer[..count]).map_err(|e| format!("临时操作数据截断：{e}"))?;
            file.write_all(&buffer[..count]).map_err(|e| format!("scatter 输出失败：{e}"))?;
            remaining -= count as u64;
        }
    }
    cancel.check()?;
    file.sync_all().map_err(|e| format!("同步操作输出失败：{e}"))?;
    Ok(hash)
}
enum Decoder { Bz(bzip2::Decompress), Xz(xz2::stream::Stream) }
impl Decoder {
    fn new(kind: i32) -> Result<Self> {
        match kind {
            1 => Ok(Self::Bz(bzip2::Decompress::new(false))),
            8 => Ok(Self::Xz(xz2::stream::Stream::new_stream_decoder(MAX_OP_BYTES,xz2::stream::CONCATENATED)
                .map_err(|_| "XZ 解码器初始化失败".to_string())?)),
            _ => Err("未知 blob decoder".into()),
        }
    }
    fn process(&mut self,input: &[u8],output: &mut [u8],eof: bool) -> Result<(usize,usize,bool)> {
        match self {
            Self::Bz(d) => {
                let before_in = d.total_in(); let before_out = d.total_out();
                let status = d.decompress(input,output).map_err(|_| "BZ 解码错误或数据截断".to_string())?;
                Ok(((d.total_in()-before_in) as usize,(d.total_out()-before_out) as usize,status == bzip2::Status::StreamEnd))
            }
            Self::Xz(d) => {
                let before_in = d.total_in(); let before_out = d.total_out();
                let action = if eof { xz2::stream::Action::Finish } else { xz2::stream::Action::Run };
                let status = d.process(input,output,action).map_err(|_| "XZ 解码错误、内存预算超限或数据截断".to_string())?;
                Ok(((d.total_in()-before_in) as usize,(d.total_out()-before_out) as usize,status == xz2::stream::Status::StreamEnd))
            }
        }
    }
}
pub(crate) fn decode_operation(input: &Input,op: &OperationPlan,cancel: &Cancel) -> Result<(Spool,String)> {
    let mut spool = source::spool_file(op.decoded_size)?;
    let mut compressed_hash = Sha256::new(); let mut decoded_hash = Sha256::new();
    let mut loaded = 0u64; let mut produced = 0u64;
    if op.operation.r#type == 0 {
        while loaded < op.blob_length {
            cancel.check()?;
            let count = (op.blob_length-loaded).min(CHUNK as u64) as usize;
            let bytes = input.read_exact(op.blob_offset+loaded,count,cancel)?;
            compressed_hash.update(&bytes); decoded_hash.update(&bytes);
            spool.file.write_all(&bytes).map_err(|e| format!("有界操作缓存写入失败：{e}"))?;
            loaded += count as u64; produced += count as u64;
        }
    } else {
        let mut decoder = Decoder::new(op.operation.r#type)?;
        let mut incoming = Vec::new(); let mut cursor = 0usize; let mut buffer = vec![0u8;CHUNK];
        loop {
            cancel.check()?;
            if cursor == incoming.len() && loaded < op.blob_length {
                let count = (op.blob_length-loaded).min(CHUNK as u64) as usize;
                incoming = input.read_exact(op.blob_offset+loaded,count,cancel)?; cursor = 0;
                compressed_hash.update(&incoming); loaded += count as u64;
            }
            let (used,count,finished) = decoder.process(&incoming[cursor..],&mut buffer,loaded == op.blob_length)?;
            cursor += used;
            if count as u64 > op.decoded_size.saturating_sub(produced) { return Err("操作解码超出 extents 声明长度".into()); }
            decoded_hash.update(&buffer[..count]);
            spool.file.write_all(&buffer[..count]).map_err(|e| format!("有界操作缓存写入失败：{e}"))?;
            produced += count as u64;
            if finished {
                if loaded == op.blob_length && cursor == incoming.len() { break; }
                if op.operation.r#type == 1 { decoder = Decoder::new(1)?; }
                else { return Err("XZ blob 存在未解码尾随数据".into()); }
            } else if used == 0 && count == 0 { return Err("操作压缩数据截断或解码没有进展".into()); }
        }
    }
    cancel.check()?;
    if loaded != op.blob_length || produced != op.decoded_size { return Err("操作 blob 或解码实际长度不精确".into()); }
    let expected = op.operation.data_sha256_hash.as_deref().unwrap_or_default();
    if !expected.is_empty() && compressed_hash.finalize().as_slice() != expected { return Err("Operation blob SHA-256 mismatch".into()); }
    if spool.file.metadata().map_err(|e| e.to_string())?.len() != op.decoded_size { return Err("操作磁盘缓存长度不一致".into()); }
    Ok((spool,hex(&decoded_hash.finalize())))
}
fn write_zeros(file: &mut File,offset: u64,length: u64,cancel: &Cancel) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| e.to_string())?;
    let zeros = vec![0u8;CHUNK]; let mut remaining = length;
    while remaining > 0 {
        cancel.check()?; let count = remaining.min(CHUNK as u64) as usize;
        file.write_all(&zeros[..count]).map_err(|e| format!("ZERO 输出失败：{e}"))?; remaining -= count as u64;
    }
    Ok(())
}
fn zero_hash(length: u64,cancel: &Cancel) -> Result<String> {
    let mut hash = Sha256::new(); let zeros = vec![0u8;CHUNK]; let mut remaining = length;
    while remaining > 0 { cancel.check()?; let count = remaining.min(CHUNK as u64) as usize; hash.update(&zeros[..count]); remaining -= count as u64; }
    Ok(hex(&hash.finalize()))
}
fn hash_ranges(file: &mut File,ranges: &[OutputRange],cancel: &Cancel) -> Result<String> {
    let mut hash = Sha256::new(); let mut buffer = vec![0u8;CHUNK];
    for range in ranges {
        file.seek(SeekFrom::Start(range.offset)).map_err(|e| e.to_string())?;
        let mut remaining = range.length;
        while remaining > 0 {
            cancel.check()?; let count = remaining.min(CHUNK as u64) as usize;
            file.read_exact(&mut buffer[..count]).map_err(|e| format!("续传镜像读取不完整：{e}"))?;
            hash.update(&buffer[..count]); remaining -= count as u64;
        }
    }
    Ok(hex(&hash.finalize()))
}
fn hash_file(file: &mut File,size: u64,cancel: &Cancel) -> Result<String> {
    if file.metadata().map_err(|e| e.to_string())?.len() != size { return Err("分区 SHA-256 前实际长度不符".into()); }
    let hash = hash_ranges(file,&[OutputRange { offset: 0,length: size }],cancel)?;
    let mut byte = [0u8;1];
    if file.read(&mut byte).map_err(|e| e.to_string())? != 0 || file.metadata().map_err(|e| e.to_string())?.len() != size {
        return Err("分区 SHA-256 期间实际长度改变".into());
    }
    Ok(hash)
}
