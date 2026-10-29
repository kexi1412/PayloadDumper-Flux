//! Semantic fixtures are genuinely serialized protobuf/CrAU bytes, not fabricated
//! post-parse objects. Private engine APIs are exercised by this lib test module.
use std::fs::{File, OpenOptions};
use std::io::{Seek, SeekFrom, Write};
use std::path::PathBuf;
use std::sync::{atomic::{AtomicU64,Ordering}, Arc, Mutex};
use std::time::{Duration,SystemTime,UNIX_EPOCH};
use serde_json::{json,Value};
use crate::journal::Journal;
use crate::payload::{self, Payload};
use crate::source::{Input, SliceSource, Source};
use crate::util::{bounds,hex,sha,Cancel,Result};
use crate::{Controller,EventSink};

static SEQUENCE: AtomicU64 = AtomicU64::new(0);
struct Temp { path: PathBuf,parent: PathBuf }
impl Temp {
    fn new() -> Self {
        let parent = std::env::temp_dir().canonicalize().unwrap();
        let n = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = parent.join(format!("flux-native-test-{}-{n}-{}",std::process::id(),SEQUENCE.fetch_add(1,Ordering::AcqRel)));
        std::fs::create_dir(&path).unwrap(); Self { path,parent }
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        if self.path.parent() == Some(self.parent.as_path()) && self.path.file_name().unwrap().to_string_lossy().starts_with("flux-native-test-") {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }
}
struct Memory { bytes: Vec<u8>,size: u64,reads: Mutex<Vec<(u64,usize)>> }
impl Source for Memory {
    fn size(&self) -> u64 { self.size }
    fn name(&self) -> String { "extensionless".into() }
    fn read_exact(&self,offset: u64,length: usize,cancel: &Cancel) -> Result<Vec<u8>> {
        cancel.check()?; bounds(offset,length as u64,self.size)?;
        self.reads.lock().unwrap().push((offset,length));
        self.bytes.get(offset as usize..offset as usize+length).map(<[u8]>::to_vec).ok_or_else(|| "mock blob must not be read during planning".into())
    }
}
fn memory(bytes: Vec<u8>,virtual_size: Option<u64>) -> Arc<Memory> {
    let size = virtual_size.unwrap_or(bytes.len() as u64); Arc::new(Memory { bytes,size,reads: Mutex::new(Vec::new()) })
}
fn varint(mut value: u64) -> Vec<u8> {
    let mut out = Vec::new(); loop { let mut b = (value&127) as u8; value >>= 7; if value != 0 { b |= 128; } out.push(b); if value == 0 { return out; } }
}
fn integer(tag: u32,value: u64) -> Vec<u8> { [varint((tag as u64)<<3),varint(value)].concat() }
fn bytes(tag: u32,value: &[u8]) -> Vec<u8> { [varint(((tag as u64)<<3)|2),varint(value.len() as u64),value.to_vec()].concat() }
fn extent(start: u64,blocks: u64) -> Vec<u8> { [integer(1,start),integer(2,blocks)].concat() }
#[derive(Clone,Default)]
struct Op {
    kind: Option<i32>,offset: u64,length: u64,dst: Vec<(u64,u64)>,src: Vec<(u64,u64)>,
    src_length: Option<u64>,dst_length: Option<u64>,hash: Option<Vec<u8>>,src_hash: Option<Vec<u8>>,
}
impl Op {
    fn replace(offset: u64,data: &[u8],start: u64,blocks: u64) -> Self {
        Self { kind: Some(0),offset,length: data.len() as u64,dst: vec![(start,blocks)],hash: Some(sha(data)),..Self::default() }
    }
    fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        if let Some(kind) = self.kind { out.extend(integer(1,kind as i64 as u64)); }
        out.extend(integer(2,self.offset)); out.extend(integer(3,self.length));
        for (start,blocks) in &self.src { out.extend(bytes(4,&extent(*start,*blocks))); }
        if let Some(length) = self.src_length { out.extend(integer(5,length)); }
        for (start,blocks) in &self.dst { out.extend(bytes(6,&extent(*start,*blocks))); }
        if let Some(length) = self.dst_length { out.extend(integer(7,length)); }
        if let Some(hash) = &self.hash { out.extend(bytes(8,hash)); }
        if let Some(hash) = &self.src_hash { out.extend(bytes(9,hash)); } out
    }
}
fn manifest(ops: &[Op],block: u32,minor: u32,size: u64,hash: Option<&[u8]>,unknown: &[u8]) -> Vec<u8> {
    let mut info = integer(1,size); if let Some(hash) = hash { info.extend(bytes(2,hash)); }
    let mut part = [bytes(1,b"boot"),bytes(7,&info)].concat();
    for op in ops { part.extend(bytes(8,&op.serialize())); }
    let mut raw = [integer(3,block as u64),integer(12,minor as u64),bytes(13,&part)].concat(); raw.extend(unknown); raw
}
fn frame(raw: &[u8],blobs: &[u8]) -> Vec<u8> {
    let mut out = b"CrAU".to_vec(); out.extend(2u64.to_be_bytes()); out.extend((raw.len() as u64).to_be_bytes()); out.extend(0u32.to_be_bytes()); out.extend(raw); out.extend(blobs); out
}
fn parsed(source: &Arc<Memory>) -> Payload {
    let input: Input = source.clone(); payload::parse(input,&Cancel::default()).unwrap()
}
fn extract(payload: &Payload,temp: &Temp,cancel: &Cancel,verify: bool) -> Result<payload::Extracted> {
    let plan = payload::validate_plan(payload,0,cancel)?;
    payload::extract(payload,&payload.manifest.partitions[0],&plan,&temp.path,2,verify,cancel,&|_|{})
}

#[test]
fn real_minor9_with_unused_src_and_dst_length_is_self_contained() {
    let image = vec![0x51;4096]; let mut op = Op::replace(0,&image,0,1); op.src_length = Some(8192); op.dst_length = Some(0);
    let raw = manifest(&[op],4096,9,4096,Some(&sha(&image)),&[]);
    let source = memory(frame(&raw,&image),None); let payload = parsed(&source); let temp = Temp::new();
    assert_eq!(payload.manifest.minor_version,Some(9));
    assert_eq!(extract(&payload,&temp,&Cancel::default(),true).unwrap().verified,Some(true));
    assert_eq!(std::fs::read(temp.path.join("boot.img")).unwrap(),image);
}
#[test]
fn replace_bz_xz_zero_discard_scatter_exact_image_and_hash() {
    let replace: Vec<u8> = (1..=32).collect(); let bz_data = vec![0x42;16]; let xz_data = vec![0x58;16];
    let mut bz = bzip2::write::BzEncoder::new(Vec::new(),bzip2::Compression::best()); bz.write_all(&bz_data).unwrap(); let bz = bz.finish().unwrap();
    let mut xz = xz2::write::XzEncoder::new(Vec::new(),6); xz.write_all(&xz_data).unwrap(); let xz = xz.finish().unwrap();
    let blobs = [&replace[..],&bz[..],&xz[..]].concat();
    let ops = vec![
        Op { dst: vec![(2,1),(0,1)],..Op::replace(0,&replace,0,2) },
        Op { kind: Some(1),dst: vec![(3,1)],..Op::replace(replace.len() as u64,&bz,3,1) },
        Op { kind: Some(8),dst: vec![(4,1)],..Op::replace((replace.len()+bz.len()) as u64,&xz,4,1) },
        Op { kind: Some(6),dst: vec![(1,1)],hash: Some(sha(&[])),..Op::default() },
        Op { kind: Some(7),dst: vec![(5,1)],..Op::default() },
    ];
    let expected = [&replace[16..],&[0u8;16][..],&replace[..16],&bz_data[..],&xz_data[..],&[0u8;16][..]].concat();
    let raw = manifest(&ops,16,9,96,Some(&sha(&expected)),&[]); let source = memory(frame(&raw,&blobs),None); let payload = parsed(&source); let temp = Temp::new();
    assert_eq!(extract(&payload,&temp,&Cancel::default(),true).unwrap().verified,Some(true));
    assert_eq!(std::fs::read(temp.path.join("boot.img")).unwrap(),expected);
}
#[test]
fn whole_plan_source_dependency_rejected_before_any_blob_or_image() {
    let image = vec![0x33;4096];
    for kind in [2,3,4,5,9,10,11,12,13,99] {
        let source_op = Op { kind: Some(kind),dst: vec![(1,1)],..Op::default() };
        let raw = manifest(&[Op::replace(0,&image,0,1),source_op],4096,0,8192,None,&[]);
        let source = memory(frame(&raw,&image),None); let payload = parsed(&source); source.reads.lock().unwrap().clear();
        let temp = Temp::new(); let result = extract(&payload,&temp,&Cancel::default(),false);
        assert!(result.is_err(),"kind={kind}"); assert!(source.reads.lock().unwrap().is_empty()); assert!(!temp.path.join("boot.img").exists());
        if kind == 4 { assert!(result.err().unwrap().contains("SOURCE_COPY(4)")); }
    }
    for dependency in [true,false] {
        let mut op = Op::replace(0,&image,0,1);
        if dependency { op.src = vec![(0,1)]; } else { op.src_hash = Some(sha(&image)); }
        let raw = manifest(&[op],4096,9,4096,None,&[]); let source = memory(frame(&raw,&image),None); let payload = parsed(&source); source.reads.lock().unwrap().clear();
        assert!(payload::validate_plan(&payload,0,&Cancel::default()).is_err()); assert!(source.reads.lock().unwrap().is_empty());
    }
}
#[test]
fn missing_type_and_unknown_type_must_never_become_replace() {
    let image = vec![0x31;4096];
    for kind in [None,Some(42)] {
        let raw = manifest(&[Op { kind,..Op::replace(0,&image,0,1) }],4096,9,4096,None,&[]);
        let source = memory(frame(&raw,&image),None); let payload = parsed(&source);
        let error = payload::validate_plan(&payload,0,&Cancel::default()).err().unwrap();
        if kind.is_none() { assert!(error.contains("missing operation type")); } else { assert!(error.contains("UNKNOWN(42)")); }
    }
}
#[test]
fn exact_extents_range_and_overlap_checks_do_not_trust_unused_length_hint() {
    let image = vec![0x41;8192];
    let cases = vec![
        vec![Op::replace(0,&image[..4096],1,1)], // beyond declared image
        vec![Op::replace(0,&image[..4096],0,1),Op::replace(4096,&image[4096..],0,1)], // overlap
        vec![Op { dst: vec![(u64::MAX,1)],..Op::replace(0,&image[..4096],0,1) }],
        vec![Op { dst: vec![(0,0)],..Op::replace(0,&image[..4096],0,1) }],
        vec![Op { length: 4095,..Op::replace(0,&image[..4096],0,1) }],
        vec![Op { offset: u64::MAX,..Op::replace(0,&image[..4096],0,1) }],
    ];
    for ops in cases {
        let raw = manifest(&ops,4096,9,4096,None,&[]); let source = memory(frame(&raw,&image),None); let payload = parsed(&source);
        assert!(payload::validate_plan(&payload,0,&Cancel::default()).is_err());
    }
    let mut op = Op::replace(0,&image[..4096],0,1); op.dst_length = Some(123);
    let raw = manifest(&[op],4096,9,4096,None,&[]); assert!(payload::validate_plan(&parsed(&memory(frame(&raw,&image[..4096]),None)),0,&Cancel::default()).is_ok());
}
#[test]
fn plan_accepts_64bit_blob_offsets_and_replace_larger_than_128mib_without_reading_blob() {
    for (offset,length) in [(1u64<<32,4096u64),(0,129u64*1024*1024)] {
        let op = Op { kind: Some(0),offset,length,dst: vec![(0,length/4096)],src_length: Some(8192),dst_length: Some(0),..Op::default() };
        let raw = manifest(&[op],4096,9,length,None,&[]); let serialized = frame(&raw,&[]); let size = serialized.len() as u64+offset+length;
        let source = memory(serialized,Some(size)); let payload = parsed(&source); source.reads.lock().unwrap().clear();
        let plan = payload::validate_plan(&payload,0,&Cancel::default()).unwrap();
        assert_eq!(plan.operations[0].blob_offset,payload.data_offset+offset); assert_eq!(plan.target_size,length); assert!(source.reads.lock().unwrap().is_empty());
    }
}
#[test]
fn bad_operation_hash_prevents_scatter_and_bad_partition_hash_clears_resume() {
    let image = vec![0x78;4096]; let mut bad_op = Op::replace(0,&image,0,1); bad_op.hash = Some(vec![0u8;32]);
    let raw = manifest(&[bad_op],4096,9,4096,Some(&sha(&image)),&[]); let payload = parsed(&memory(frame(&raw,&image),None)); let temp = Temp::new();
    assert!(extract(&payload,&temp,&Cancel::default(),true).err().unwrap().contains("blob SHA-256 mismatch"));
    assert_eq!(std::fs::read(temp.path.join("boot.img")).unwrap(),vec![0u8;4096]);
    let raw = manifest(&[Op::replace(0,&image,0,1)],4096,9,4096,Some(&[0u8;32]),&[]); let payload = parsed(&memory(frame(&raw,&image),None)); let temp = Temp::new();
    assert_eq!(extract(&payload,&temp,&Cancel::default(),true).unwrap().verified,Some(false));
    let journal = Journal::load(&temp.path,"boot",payload::resume_identity(&payload,"boot"),1,4096,&temp.path.join("boot.img"),&Cancel::default()).unwrap();
    assert!(journal.completed.is_empty());
}
#[test]
fn raw_manifest_unknown_fields_change_journal_but_url_filename_and_container_offset_do_not() {
    let image = vec![0x45;4096]; let ops = [Op::replace(0,&image,0,1)];
    let raw1 = manifest(&ops,4096,9,4096,None,&bytes(127,b"opaque-v1")); let raw2 = manifest(&ops,4096,9,4096,None,&bytes(127,b"opaque-v2"));
    let p1 = parsed(&memory(frame(&raw1,&image),None)); let p2 = parsed(&memory(frame(&raw2,&image),None));
    assert_eq!(p1.manifest,p2.manifest); assert_ne!(payload::resume_identity(&p1,"boot"),payload::resume_identity(&p2,"boot"));
    let direct = frame(&raw1,&image); let mut wrapped = vec![0x22;4096]; wrapped.extend(&direct);
    let backing: Input = memory(wrapped,None); let slice = SliceSource::make(backing,4096,direct.len() as u64,"renamed.bin".into()).unwrap();
    let p3 = payload::parse(slice,&Cancel::default()).unwrap(); assert_eq!(payload::resume_identity(&p1,"boot"),payload::resume_identity(&p3,"boot"));
    let temp = Temp::new(); extract(&p1,&temp,&Cancel::default(),false).unwrap();
    let next = Journal::load(&temp.path,"boot",payload::resume_identity(&p2,"boot"),1,4096,&temp.path.join("boot.img"),&Cancel::default()).unwrap(); assert!(next.completed.is_empty());
}
#[test]
fn disk_hash_is_checked_before_resume_and_only_tampered_op_is_rewritten() {
    let image = [vec![0x61;4096],vec![0x62;4096]].concat(); let ops = [Op::replace(0,&image[..4096],0,1),Op::replace(4096,&image[4096..],1,1)];
    let raw = manifest(&ops,4096,9,8192,Some(&sha(&image)),&[]); let source = memory(frame(&raw,&image),None); let payload = parsed(&source); let temp = Temp::new();
    extract(&payload,&temp,&Cancel::default(),true).unwrap();
    let mut file = OpenOptions::new().write(true).open(temp.path.join("boot.img")).unwrap(); file.seek(SeekFrom::Start(7)).unwrap(); file.write_all(&[0xff]).unwrap(); file.sync_all().unwrap(); drop(file);
    source.reads.lock().unwrap().clear(); assert_eq!(extract(&payload,&temp,&Cancel::default(),true).unwrap().verified,Some(true));
    let reads = source.reads.lock().unwrap(); assert!(reads.iter().any(|(p,_)| *p == payload.data_offset)); assert!(!reads.iter().any(|(p,_)| *p == payload.data_offset+4096));
    assert_eq!(std::fs::read(temp.path.join("boot.img")).unwrap(),image);
}
#[test]
fn cancel_preserves_synced_correct_operation_and_resume_reuses_it() {
    let image: Vec<u8> = (0..8).flat_map(|i| vec![0x40+i;4096]).collect();
    let ops: Vec<Op> = (0..8).map(|i| Op::replace(i*4096,&image[i as usize*4096..(i as usize+1)*4096],i,1)).collect();
    let raw = manifest(&ops,4096,9,image.len() as u64,Some(&sha(&image)),&[]); let source = memory(frame(&raw,&image),None); let payload = parsed(&source); let plan = payload::validate_plan(&payload,0,&Cancel::default()).unwrap(); let temp = Temp::new();
    let token = Cancel::default(); let result = payload::extract(&payload,&payload.manifest.partitions[0],&plan,&temp.path,1,true,&token,&|f| { if f > 0.0 && f < 1.0 { token.cancel(); } });
    assert!(result.is_err());
    let journal = Journal::load(&temp.path,"boot",payload::resume_identity(&payload,"boot"),8,image.len() as u64,&temp.path.join("boot.img"),&Cancel::default()).unwrap(); assert!(journal.completed.contains_key(&0));
    source.reads.lock().unwrap().clear(); assert_eq!(extract(&payload,&temp,&Cancel::default(),true).unwrap().verified,Some(true));
    assert!(!source.reads.lock().unwrap().iter().any(|(p,_)| *p == payload.data_offset)); assert_eq!(std::fs::read(temp.path.join("boot.img")).unwrap(),image);
}
#[test]
fn controller_source_rejection_creates_no_output_and_sink_can_reenter_snapshot() {
    let temp = Temp::new(); let fixture = temp.path.join("looks-like-a-zip.zip"); let output = temp.path.join("outputs");
    let raw = manifest(&[Op { kind: Some(4),dst: vec![(0,1)],src: vec![(0,1)],..Op::default() }],4096,0,4096,None,&[]); std::fs::write(&fixture,frame(&raw,&[])).unwrap();
    let slot: Arc<Mutex<Option<std::sync::Weak<Controller>>>> = Arc::new(Mutex::new(None)); let callback_slot = slot.clone(); let (sender,receiver) = std::sync::mpsc::channel();
    let sink: EventSink = Arc::new(move |event| {
        if let Some(controller) = callback_slot.lock().unwrap().as_ref().and_then(std::sync::Weak::upgrade) { assert!(controller.request("status",json!({})).unwrap().is_object()); }
        let _ = sender.send(event);
    });
    let controller = Controller::new(sink); *slot.lock().unwrap() = Some(Arc::downgrade(&controller));
    let options = json!({"workers":2,"verify":true,"userAgent":"Flux-Native-Test/1"});
    controller.request("parse",json!({"source":{"kind":"file","value":fixture},"outputDir":output,"options":options})).unwrap();
    wait_event(&receiver,|s| s["phase"] == "ready" && s["partitions"].as_array().is_some_and(|p| p.len() == 1));
    controller.request("extract",json!({"partitions":["boot"],"outputDir":output,"options":options})).unwrap();
    let state = wait_event(&receiver,|s| s["phase"] == "ready" && s["partitions"][0]["extractState"] == "ERROR");
    assert!(state["partitions"][0]["message"].as_str().unwrap().contains("SOURCE_COPY(4)")); assert!(!output.exists());
    controller.shutdown();
}
// A complete serialized ZIP with one STORED image. The overlap case declares one
// extra byte belonging to the central signature, and even gives that byte a matching
// CRC: source-size, local/central agreement and CRC alone cannot prove ZIP boundaries.
fn stored_zip_fixture(local_extra: u16,overlap: u32) -> (Vec<u8>,u64,Vec<u8>) {
    assert!(overlap <= 4);
    let name = b"boot.img"; let actual = vec![0x11u8,0x22,0x33,0x44];
    let declared = actual.len() as u32+overlap;
    let mut advertised = actual.clone(); advertised.extend_from_slice(&[0x50u8,0x4b,0x01,0x02][..overlap as usize]);
    let mut checksum = crc32fast::Hasher::new(); checksum.update(&advertised); let crc = checksum.finalize();
    let mut local = vec![0u8;30]; local[0..4].copy_from_slice(&0x04034b50u32.to_le_bytes()); local[4..6].copy_from_slice(&20u16.to_le_bytes());
    local[6..8].copy_from_slice(&0x800u16.to_le_bytes()); local[14..18].copy_from_slice(&crc.to_le_bytes());
    local[18..22].copy_from_slice(&declared.to_le_bytes()); local[22..26].copy_from_slice(&declared.to_le_bytes());
    local[26..28].copy_from_slice(&(name.len() as u16).to_le_bytes()); local[28..30].copy_from_slice(&local_extra.to_le_bytes());
    let cd_start = (local.len()+name.len()+actual.len()) as u64;
    let mut central = vec![0u8;46]; central[0..4].copy_from_slice(&0x02014b50u32.to_le_bytes());
    central[4..6].copy_from_slice(&20u16.to_le_bytes()); central[6..8].copy_from_slice(&20u16.to_le_bytes());
    central[8..10].copy_from_slice(&0x800u16.to_le_bytes()); central[16..20].copy_from_slice(&crc.to_le_bytes());
    central[20..24].copy_from_slice(&declared.to_le_bytes()); central[24..28].copy_from_slice(&declared.to_le_bytes());
    central[28..30].copy_from_slice(&(name.len() as u16).to_le_bytes());
    let mut end = vec![0u8;22]; end[0..4].copy_from_slice(&0x06054b50u32.to_le_bytes());
    end[8..10].copy_from_slice(&1u16.to_le_bytes()); end[10..12].copy_from_slice(&1u16.to_le_bytes());
    end[12..16].copy_from_slice(&((central.len()+name.len()) as u32).to_le_bytes()); end[16..20].copy_from_slice(&(cd_start as u32).to_le_bytes());
    let serialized = [&local[..],&name[..],&actual[..],&central[..],&name[..],&end[..]].concat();
    (serialized,cd_start,actual)
}
#[test]
fn zip_stored_data_ending_exactly_at_central_directory_is_valid() {
    let (serialized,cd_start,expected) = stored_zip_fixture(0,0); let source: Input = memory(serialized,None); let token = Cancel::default();
    let entries = crate::zip::scan(&source,&token).unwrap(); assert_eq!(entries[0].central_directory_offset,cd_start);
    let range = crate::zip::entry_range(&source,&entries[0],&token).unwrap(); assert_eq!(range.offset+range.compressed,cd_start);
    let mut output = Vec::new(); crate::zip::copy_range(&source,&range,&mut output,&token,&|_|{}).unwrap(); assert_eq!(output,expected);
}
#[test]
fn serialized_zip_compressed_data_overlapping_central_directory_is_rejected() {
    let (serialized,cd_start,_) = stored_zip_fixture(0,1); let source = memory(serialized,None); let input: Input = source.clone(); let token = Cancel::default();
    let entries = crate::zip::scan(&input,&token).unwrap(); assert_eq!(entries[0].central_directory_offset,cd_start);
    // The malformed data range still has the advertised CRC, so rejection must be
    // structural and occur before a data read or extraction rather than at CRC time.
    let data_start = 30+b"boot.img".len(); let mut crc = crc32fast::Hasher::new(); crc.update(&source.bytes[data_start..data_start+5]); assert_eq!(crc.finalize(),entries[0].crc);
    source.reads.lock().unwrap().clear();
    let error = crate::zip::entry_range(&input,&entries[0],&token).err().unwrap(); assert!(error.contains("压缩数据与中央目录重叠"),"{error}");
    assert!(source.reads.lock().unwrap().iter().all(|(offset,length)| offset+*length as u64 <= cd_start));
    assert!(crate::archive::parse(input,&token,&|_|{}).is_err());
}
#[test]
fn serialized_zip_full_local_header_overlapping_central_directory_is_rejected() {
    let (serialized,cd_start,_) = stored_zip_fixture(8,0); let source = memory(serialized,None); let input: Input = source.clone(); let token = Cancel::default();
    let entries = crate::zip::scan(&input,&token).unwrap(); source.reads.lock().unwrap().clear();
    let error = crate::zip::entry_range(&input,&entries[0],&token).err().unwrap(); assert!(error.contains("本地头与中央目录重叠"),"{error}");
    assert!(source.reads.lock().unwrap().iter().all(|(offset,length)| offset+*length as u64 <= cd_start));
    assert!(crate::archive::parse(input,&token,&|_|{}).is_err());
}
fn wait_event(receiver: &std::sync::mpsc::Receiver<Value>,predicate: impl Fn(&Value)->bool) -> Value {
    let until = std::time::Instant::now()+Duration::from_secs(5);
    loop { let event = receiver.recv_timeout(until.saturating_duration_since(std::time::Instant::now())).unwrap(); if event["event"] == "state" && predicate(&event["data"]) { return event["data"].clone(); } }
}
