//! Execute the real JSONL binary, not an in-process protocol mock.
use std::fs;
use std::io::{BufRead,BufReader,Write};
use std::path::PathBuf;
use std::process::{Child,ChildStdin,Command,Stdio};
use std::sync::mpsc::{self,Receiver};
use std::time::{Duration,Instant,SystemTime,UNIX_EPOCH};
use prost::Message;
use serde_json::{json,Value};
use sha2::{Digest,Sha256};
use flux_native_engine::proto::{DeltaArchiveManifest,Extent,InstallOperation,PartitionInfo,PartitionUpdate};

struct Temp { path: PathBuf,parent: PathBuf }
impl Temp {
    fn new() -> Self {
        let parent = std::env::temp_dir().canonicalize().unwrap();
        let n = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = parent.join(format!("flux-native-jsonl-{}-{n}",std::process::id())); fs::create_dir(&path).unwrap(); Self { path,parent }
    }
}
impl Drop for Temp {
    fn drop(&mut self) { if self.path.parent() == Some(self.parent.as_path()) && self.path.file_name().unwrap().to_string_lossy().starts_with("flux-native-jsonl-") { let _ = fs::remove_dir_all(&self.path); } }
}
struct Process { child: Child,input: ChildStdin,output: Receiver<Value>,events: Vec<Value>,next: usize,reader: Option<std::thread::JoinHandle<()>> }
impl Process {
    fn start() -> Self {
        let mut child = Command::new(env!("CARGO_BIN_EXE_flux-native-engine")).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::inherit()).spawn().unwrap();
        let input = child.stdin.take().unwrap(); let stdout = child.stdout.take().unwrap(); let (sender,output) = mpsc::channel();
        let reader = std::thread::spawn(move || { for line in BufReader::new(stdout).lines() { let Ok(line) = line else { break; }; if let Ok(value) = serde_json::from_str(&line) { if sender.send(value).is_err() { break; } } } });
        Self { child,input,output,events: Vec::new(),next: 0,reader: Some(reader) }
    }
    fn request(&mut self,method: &str,params: Value) -> Value {
        self.next += 1; let id = self.next.to_string(); writeln!(self.input,"{}",json!({"id":id,"method":method,"params":params})).unwrap(); self.input.flush().unwrap();
        let until = Instant::now()+Duration::from_secs(5);
        loop { let value = self.output.recv_timeout(until.saturating_duration_since(Instant::now())).expect("JSONL response timeout"); if value["id"] == id { return value; } self.events.push(value); }
    }
    fn state(&mut self,predicate: impl Fn(&Value)->bool) -> Value {
        if let Some(index) = self.events.iter().position(|v| v["event"] == "state" && predicate(&v["data"])) { return self.events.remove(index)["data"].clone(); }
        let until = Instant::now()+Duration::from_secs(5);
        loop { let event = self.output.recv_timeout(until.saturating_duration_since(Instant::now())).expect("JSONL state timeout"); if event["event"] == "state" && predicate(&event["data"]) { return event["data"].clone(); } }
    }
}
impl Drop for Process {
    fn drop(&mut self) { let _ = self.child.kill(); let _ = self.child.wait(); if let Some(reader) = self.reader.take() { let _ = reader.join(); } }
}
#[test]
fn real_binary_status_minor9_parse_extract_reset_shutdown() {
    let temp = Temp::new(); let file = temp.path.join("magic-not-extension.dat"); let output = temp.path.join("output"); let image = vec![0x5a;4096];
    let hash = Sha256::digest(&image).to_vec();
    let op = InstallOperation { r#type: 0,data_offset: Some(0),data_length: Some(image.len() as u64),src_length: Some(8192),dst_length: Some(0),
        dst_extents: vec![Extent { start_block: Some(0),num_blocks: Some(1) }],data_sha256_hash: Some(hash.clone()),..Default::default() };
    let part = PartitionUpdate { partition_name: "boot".into(),new_partition_info: Some(PartitionInfo { size: Some(4096),hash: Some(hash) }),operations: vec![op],..Default::default() };
    let manifest = DeltaArchiveManifest { block_size: Some(4096),minor_version: Some(9),partitions: vec![part],security_patch_level: Some("2026-10-01".into()),..Default::default() }.encode_to_vec();
    let mut payload = b"CrAU".to_vec(); payload.extend(2u64.to_be_bytes()); payload.extend((manifest.len() as u64).to_be_bytes()); payload.extend(0u32.to_be_bytes()); payload.extend(manifest); payload.extend(&image); fs::write(&file,payload).unwrap();
    let options = json!({"workers":2,"verify":true,"userAgent":"Flux-Native-JSONL/1"}); let mut process = Process::start();
    assert_eq!(process.request("status",json!({}))["result"]["phase"],"idle");
    assert_eq!(process.request("unknown",json!({}))["ok"],false);
    let parse = process.request("parse",json!({"source":{"kind":"file","value":file},"outputDir":output,"options":options})); assert_eq!(parse["ok"],true); assert!(parse["result"].is_null());
    let state = process.state(|s| s["phase"] == "ready" || s["phase"] == "error"); assert_eq!(state["phase"],"ready","{state}"); assert_eq!(state["archive"]["securityPatchLevel"],"2026-10-01");
    assert_eq!(process.request("extract",json!({"partitions":["boot"],"outputDir":output,"options":options}))["ok"],true);
    let state = process.state(|s| s["phase"] == "error" || s["phase"] == "ready" && s["partitions"][0]["extractState"] == "DONE"); assert_eq!(state["phase"],"ready","{state}"); assert_eq!(state["partitions"][0]["verifyState"],"PASSED");
    assert_eq!(fs::read(PathBuf::from(state["outputDir"].as_str().unwrap()).join("boot.img")).unwrap(),image);
    assert_eq!(process.request("reset",json!({}))["ok"],true); assert_eq!(process.request("status",json!({}))["result"]["phase"],"idle");
    assert_eq!(process.request("shutdown",json!({}))["ok"],true); assert!(process.child.wait().unwrap().success());
}
