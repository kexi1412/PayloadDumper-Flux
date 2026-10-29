use std::collections::VecDeque;
use std::fs::OpenOptions;
use std::io::{self, BufRead, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::{atomic::{AtomicBool, Ordering}, Arc, Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use serde_json::{json, Value};
use crate::archive::{self, Archive, Kind};
use crate::model::{Options, Row, SourceSpec, State};
use crate::util::{sanitize_message, Cancel, Result};
use crate::{metadata, paths, payload, source, zip, EventSink};

struct Inner {
    state: State, parsed: Option<Arc<Archive>>, spec: Option<SourceSpec>, options: Options,
    root: Option<PathBuf>, stamp: Option<String>, generation: u64,
    active: Option<Cancel>, metadata: Option<Cancel>, closed: bool, last_publish: Instant,
}
/// One foreground operation, independently budgeted optional metadata, immutable JSON
/// snapshots. Event callbacks never run while any controller/state/queue mutex is held.
pub struct Controller {
    inner: Mutex<Inner>, finished: Condvar, sink: EventSink,
    events: Mutex<VecDeque<Value>>, draining: AtomicBool,
}
impl Controller {
    pub fn new(sink: EventSink) -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(Inner { state: State::default(),parsed: None,spec: None,options: Options::default(),
            root: None,stamp: None,generation: 0,active: None,metadata: None,closed: false,last_publish: Instant::now() }),
            finished: Condvar::new(),sink,events: Mutex::new(VecDeque::new()),draining: AtomicBool::new(false) })
    }
    pub fn snapshot(&self) -> Value { serde_json::to_value(&self.inner.lock().unwrap().state).unwrap_or(Value::Null) }
    pub fn request(self: &Arc<Self>,method: &str,params: Value) -> Result<Value> {
        if !params.is_object() { return Err("请求参数必须是对象".into()); }
        match method {
            "status" => Ok(self.snapshot()),
            "parse" => { self.start_parse(params)?; Ok(Value::Null) }
            "extract" => { self.start_extract(params)?; Ok(Value::Null) }
            "cancel" => { self.cancel(); Ok(Value::Null) }
            "reset" => { self.reset()?; Ok(Value::Null) }
            "shutdown" => { self.shutdown(); Ok(Value::Null) }
            _ => Err("未知引擎方法".into()),
        }
    }
    fn idle(inner: &Inner) -> Result<()> {
        if inner.closed { Err("引擎已停止".into()) }
        else if inner.active.is_some() { Err("请先等待当前任务结束或暂停任务".into()) } else { Ok(()) }
    }
    fn queue_state(&self,inner: &mut Inner,force: bool) {
        if force || inner.last_publish.elapsed() >= Duration::from_millis(100) {
            inner.last_publish = Instant::now();
            self.events.lock().unwrap().push_back(json!({"event":"state","data":&inner.state}));
        }
    }
    fn flush(&self) {
        if self.draining.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).is_err() { return; }
        loop {
            let next = {
                let mut events = self.events.lock().unwrap();
                if events.is_empty() { self.draining.store(false,Ordering::Release); return; }
                events.pop_front()
            };
            if let Some(event) = next { (self.sink)(event); }
        }
    }
    fn update(&self,epoch: u64,force: bool,change: impl FnOnce(&mut Inner)) {
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.generation != epoch || inner.closed { return; }
            change(&mut inner); self.queue_state(&mut inner,force);
        }
        self.flush();
    }
    fn row(&self,epoch: u64,name: &str,force: bool,change: impl FnOnce(&mut Row)) {
        self.update(epoch,force,|inner| { if let Some(row) = inner.state.partitions.iter_mut().find(|r| r.partition_name == name) { change(row); } });
    }
    fn detail(&self,epoch: u64,message: &str) {
        self.update(epoch,false,|inner| inner.state.detail = sanitize_message(message));
    }
    fn log(&self,message: &str,level: &str) {
        let time = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
        self.events.lock().unwrap().push_back(json!({"event":"log","data":{"time":time,"level":level,"message":sanitize_message(message)}}));
        self.flush();
    }
    fn start_parse(self: &Arc<Self>,params: Value) -> Result<()> {
        let spec = source_spec(&params)?; let options = options(&params)?; let root = output_root(&params)?;
        let token = Cancel::default();
        let epoch = {
            let mut inner = self.inner.lock().unwrap(); Self::idle(&inner)?;
            if let Some(old) = inner.metadata.take() { old.cancel(); }
            inner.generation += 1; let epoch = inner.generation;
            inner.parsed = None; inner.spec = Some(spec.clone()); inner.options = options.clone(); inner.root = Some(root.clone()); inner.stamp = None;
            inner.active = Some(token.clone());
            inner.state = State { phase: "parsing".into(),detail: "正在读取固件目录…".into(),
                source_label: Some(if spec.kind == "file" { Path::new(&spec.value).file_name().unwrap_or_default().to_string_lossy().to_string() } else { "网络固件".into() }),..State::default() };
            self.queue_state(&mut inner,true); epoch
        };
        self.flush();
        let this = self.clone();
        if let Err(error) = std::thread::Builder::new().name("flux-parse".into()).spawn(move || {
            let result: Result<_> = (|| {
                let stamp = if spec.kind == "file" { Some(source::file_stamp(Path::new(&spec.value))?) } else { None };
                let input = source::open(&spec,&options,&token)?;
                let mut parsed = archive::parse(input,&token,&|message| this.detail(epoch,message))?;
                token.check()?;
                parsed.info = metadata::basic(&parsed,&token); token.check()?;
                parsed.validate_outputs()?; let rows = parsed.rows()?;
                if let Some(old) = &stamp { if &source::file_stamp(Path::new(&spec.value))? != old { return Err("本地固件在解析过程中被修改".into()); } }
                let directory = root.join(parsed.folder_name());
                Ok((parsed,rows,stamp,directory))
            })();
            let mut success = None;
            {
                let mut inner = this.inner.lock().unwrap();
                if inner.generation == epoch {
                    inner.active = None;
                    if !inner.closed {
                        match result {
                            Ok((parsed,rows,stamp,directory)) if !token.is_cancelled() => {
                                inner.state.phase = "ready".into(); inner.state.detail = format!("解析完成，共 {} 个分区",rows.len());
                                inner.state.archive = Some(parsed.info.clone()); inner.state.source_label = Some(parsed.info.file_name.clone());
                                inner.state.partitions = rows; inner.state.output_dir = Some(directory.to_string_lossy().to_string()); inner.stamp = stamp;
                                let parsed = Arc::new(parsed); inner.parsed = Some(parsed.clone()); success = Some(parsed);
                            }
                            _ if token.is_cancelled() => {
                                inner.parsed = None; inner.state = State { phase: "ready".into(),detail: "解析已取消，可重新解析".into(),..State::default() };
                            }
                            Err(error) => { inner.state.phase = "error".into(); inner.state.detail = sanitize_message(&error); }
                            Ok(_) => {}
                        }
                        this.queue_state(&mut inner,true);
                    }
                }
                this.finished.notify_all();
            }
            this.flush();
            if let Some(parsed) = success {
                this.log(&format!("解析完成：{}（{} 个分区）",parsed.info.file_name,parsed.info.partition_count),"info");
                this.start_metadata(epoch,parsed);
            } else if !token.is_cancelled() {
                let state = this.snapshot(); if state["phase"] == "error" { this.log(state["detail"].as_str().unwrap_or("解析失败"),"error"); }
            }
        }) {
            self.update(epoch,true,|inner| { inner.active = None; inner.state.phase = "error".into(); inner.state.detail = format!("无法启动解析线程：{error}"); });
            return Err("无法启动解析线程".into());
        }
        Ok(())
    }
    fn start_metadata(self: &Arc<Self>,epoch: u64,archive: Arc<Archive>) {
        let token = Cancel::default();
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.generation != epoch || inner.closed || inner.active.is_some() || inner.parsed.is_none() { return; }
            if let Some(old) = inner.metadata.take() { old.cancel(); }
            inner.metadata = Some(token.clone());
        }
        let this = self.clone();
        let _ = std::thread::Builder::new().name("flux-metadata".into()).spawn(move || {
            let result = metadata::kernel(&archive,&token);
            {
                let mut inner = this.inner.lock().unwrap();
                if inner.generation == epoch && !token.is_cancelled() && !inner.closed {
                    if let Ok(info) = result {
                        inner.state.archive = Some(info.clone());
                        if let Some(parsed) = &mut inner.parsed { Arc::make_mut(parsed).info = info; }
                        this.queue_state(&mut inner,true);
                    }
                    inner.metadata = None;
                }
            }
            this.finished.notify_all(); this.flush();
        });
    }
    fn start_extract(self: &Arc<Self>,params: Value) -> Result<()> {
        let options = options(&params)?; let root = output_root(&params)?;
        let selected = selected(&params)?; let token = Cancel::default();
        let (epoch,parsed,spec,stamp) = {
            let mut inner = self.inner.lock().unwrap(); Self::idle(&inner)?;
            let parsed = inner.parsed.clone().ok_or_else(|| "请先解析固件".to_string())?;
            if selected.iter().any(|n| !inner.state.partitions.iter().any(|r| &r.partition_name == n)) { return Err("所选分区不属于当前固件".into()); }
            let spec = inner.spec.clone().ok_or_else(|| "固件来源已失效".to_string())?;
            if let Some(old) = inner.metadata.take() { old.cancel(); }
            if inner.root.as_ref() != Some(&root) {
                for row in &mut inner.state.partitions { row.extract_state = "IDLE".into(); row.verify_state = "NONE".into(); row.progress = 0.0; row.message.clear(); }
            }
            inner.root = Some(root.clone()); inner.options = options.clone(); inner.active = Some(token.clone());
            inner.state.phase = "extracting".into(); inner.state.detail = format!("准备提取 {} 个分区…",selected.len());
            inner.state.output_dir = Some(root.join(parsed.folder_name()).to_string_lossy().to_string());
            let epoch = inner.generation; let stamp = inner.stamp.clone(); self.queue_state(&mut inner,true); (epoch,parsed,spec,stamp)
        };
        self.flush();
        let this = self.clone();
        if let Err(error) = std::thread::Builder::new().name("flux-extract".into()).spawn(move || {
            let result: Result<()> = (|| {
                token.check()?;
                if let Some(old) = &stamp { if &source::file_stamp(Path::new(&spec.value))? != old { return Err("本地固件已被修改，请重新解析；不会复用旧进度".into()); } }
                let reopened = source::open(&spec,&options,&token)?;
                // No output directory, image, or journal exists/changes until this check passes.
                let parsed = parsed.reopened(reopened,&token)?;
                token.check()?; parsed.validate_outputs()?;
                match &parsed.kind {
                    Kind::Payload(payload) => {
                        let mut plans = Vec::new();
                        for name in &selected {
                            let index = payload.manifest.partitions.iter().position(|p| &p.partition_name == name).ok_or_else(|| "分区不属于当前 manifest".to_string())?;
                            match payload::validate_plan(payload,index,&token) {
                                Ok(plan) => plans.push((index,plan)),
                                Err(error) => { token.check()?; this.failure(epoch,name,&error); }
                            }
                        }
                        if !plans.is_empty() {
                            let directory = paths::directory(&root,&parsed.folder_name())?;
                            this.update(epoch,true,|inner| inner.state.output_dir = Some(directory.to_string_lossy().to_string()));
                            for (index,plan) in plans {
                                token.check()?;
                                let part = &payload.manifest.partitions[index]; let name = &part.partition_name;
                                this.running(epoch,name);
                                let result = payload::extract(payload,part,&plan,&directory,options.workers,options.verify,&token,&|fraction| {
                                    this.row(epoch,name,false,|row| {
                                        row.progress = fraction;
                                        if options.verify && fraction >= 0.99 && !row.sha256.is_empty() { row.verify_state = "VERIFYING".into(); }
                                    });
                                });
                                token.check()?;
                                match result {
                                    Ok(result) if result.verified == Some(false) => {
                                        this.row(epoch,name,true,|row| { row.extract_state = "ERROR".into(); row.verify_state = "FAILED".into(); row.message = "分区 SHA-256 校验失败，请重试".into(); });
                                        this.log(&format!("{name}：分区 SHA-256 校验失败"),"error");
                                    }
                                    Ok(result) => {
                                        this.row(epoch,name,true,|row| { row.extract_state = "DONE".into(); row.progress = 1.0; row.message.clear(); row.verify_state = if result.verified == Some(true) { "PASSED" } else { "SKIPPED" }.into(); });
                                        this.log(&format!("已提取 {name} → {name}.img"),"info");
                                    }
                                    Err(error) => this.failure(epoch,name,&error),
                                }
                            }
                        }
                    }
                    Kind::Raw(items) => {
                        let directory = paths::directory(&root,&parsed.folder_name())?;
                        this.update(epoch,true,|inner| inner.state.output_dir = Some(directory.to_string_lossy().to_string()));
                        for name in &selected {
                            token.check()?;
                            let item = items.iter().find(|i| &i.key == name).ok_or_else(|| "条目不属于当前固件".to_string())?;
                            this.running(epoch,name);
                            let result: Result<()> = (|| {
                                let group = if let Some(group) = &item.group { paths::directory(&directory,group)? } else { directory.clone() };
                                let target = paths::target(&group,&item.name)?;
                                let mut file = OpenOptions::new().write(true).create(true).truncate(true).open(&target).map_err(|e| format!("无法创建输出：{e}"))?;
                                zip::copy_range(&item.input,&item.range,&mut file,&token,&|fraction| this.row(epoch,name,false,|row| row.progress = fraction.min(0.99)))?;
                                if file.metadata().map_err(|e| e.to_string())?.len() != item.range.size { return Err("ZIP/UPDATE.APP 输出实际长度不一致".into()); }
                                file.sync_all().map_err(|e| format!("同步输出失败：{e}"))?;
                                Ok(())
                            })();
                            token.check()?;
                            match result {
                                Ok(()) => { this.row(epoch,name,true,|row| { row.extract_state = "DONE".into(); row.progress = 1.0; row.verify_state = "SKIPPED".into(); row.message.clear(); }); this.log(&format!("已提取 {}",item.key),"info"); }
                                Err(error) => this.failure(epoch,name,&error),
                            }
                        }
                    }
                }
                Ok(())
            })();
            {
                let mut inner = this.inner.lock().unwrap();
                if inner.generation == epoch {
                    inner.active = None;
                    if !inner.closed {
                        if token.is_cancelled() {
                            inner.state.phase = "ready".into(); inner.state.detail = "任务已暂停；payload 正确操作进度保留，普通 ZIP 条目重新开始".into();
                            for row in inner.state.partitions.iter_mut().filter(|r| selected.contains(&r.partition_name)) {
                                if !matches!(row.extract_state.as_str(),"DONE"|"ERROR") { row.extract_state = "PAUSED".into(); row.message = "已暂停".into(); }
                            }
                        } else if let Err(error) = result {
                            let message = sanitize_message(&error); inner.state.phase = "error".into(); inner.state.detail = message.clone();
                            for row in inner.state.partitions.iter_mut().filter(|r| selected.contains(&r.partition_name) && r.extract_state != "DONE") { row.extract_state = "ERROR".into(); row.message = message.clone(); }
                        } else {
                            let failures = inner.state.partitions.iter().filter(|r| selected.contains(&r.partition_name) && r.extract_state == "ERROR").count();
                            inner.state.phase = "ready".into(); inner.state.detail = if failures == 0 { "提取完成，镜像已保存到输出目录".into() } else { format!("提取结束：{failures} 个分区失败，可单独重试") };
                        }
                        this.queue_state(&mut inner,true);
                    }
                }
                this.finished.notify_all();
            }
            this.flush();
            if token.is_cancelled() { this.log("提取已暂停，已校验且同步的 payload 操作进度保留","warn"); }
            else { let state = this.snapshot(); if state["phase"] == "error" { this.log(state["detail"].as_str().unwrap_or("提取失败"),"error"); } }
        }) {
            self.update(epoch,true,|inner| { inner.active = None; inner.state.phase = "error".into(); inner.state.detail = format!("无法启动提取线程：{error}"); });
            return Err("无法启动提取线程".into());
        }
        Ok(())
    }
    fn running(&self,epoch: u64,name: &str) {
        self.update(epoch,true,|inner| {
            inner.state.detail = format!("正在提取 {name}");
            if let Some(row) = inner.state.partitions.iter_mut().find(|r| r.partition_name == name) {
                row.extract_state = "RUNNING".into(); row.verify_state = "NONE".into(); row.message.clear(); row.progress = 0.0;
            }
        });
    }
    fn failure(&self,epoch: u64,name: &str,error: &str) {
        let message = sanitize_message(error);
        self.row(epoch,name,true,|row| { row.extract_state = "ERROR".into(); row.message = message.clone(); if error.contains("SHA-256") { row.verify_state = "FAILED".into(); } });
        self.log(&format!("{name}：{message}"),"error");
    }
    fn cancel(&self) {
        {
            let mut inner = self.inner.lock().unwrap();
            if let Some(token) = &inner.active {
                token.cancel();
                if let Some(metadata) = inner.metadata.take() { metadata.cancel(); }
                inner.state.phase = "cancelling".into(); inner.state.detail = "正在暂停并保存已完成操作…".into(); self.queue_state(&mut inner,true);
            }
        }
        self.flush();
    }
    fn reset(&self) -> Result<()> {
        {
            let mut inner = self.inner.lock().unwrap(); Self::idle(&inner)?;
            if let Some(token) = inner.metadata.take() { token.cancel(); }
            inner.generation += 1; inner.parsed = None; inner.spec = None; inner.stamp = None; inner.root = None;
            inner.state = State::default(); self.queue_state(&mut inner,true);
        }
        self.flush(); Ok(())
    }
    pub fn shutdown(&self) {
        let stop = Instant::now()+Duration::from_secs(3);
        let mut inner = self.inner.lock().unwrap();
        if inner.closed { return; } inner.closed = true;
        if let Some(token) = &inner.active { token.cancel(); }
        if let Some(token) = inner.metadata.take() { token.cancel(); }
        while inner.active.is_some() && Instant::now() < stop {
            match self.finished.wait_timeout(inner,stop.saturating_duration_since(Instant::now())) {
                Ok((guard,_)) => inner = guard,
                Err(_) => return,
            }
        }
        inner.parsed = None; inner.spec = None;
    }
}
fn output_root(params: &Value) -> Result<PathBuf> { paths::root(params.get("outputDir").and_then(Value::as_str).ok_or_else(|| "缺少输出目录".to_string())?) }
fn options(params: &Value) -> Result<Options> {
    let value = params.get("options").filter(|v| v.is_object()).ok_or_else(|| "缺少提取设置".to_string())?;
    let workers = value.get("workers").map(|v| v.as_u64().ok_or_else(|| "并行线程无效".to_string())).transpose()?.unwrap_or(4);
    if !(1..=16).contains(&workers) { return Err("并行线程必须在 1–16 之间".into()); }
    let verify = value.get("verify").map(|v| v.as_bool().ok_or_else(|| "校验选项无效".to_string())).transpose()?.unwrap_or(true);
    let user_agent = value.get("userAgent").map(|v| v.as_str().ok_or_else(|| "User-Agent 无效".to_string())).transpose()?.unwrap_or("").trim().to_string();
    if user_agent.len() > 512 || user_agent.chars().any(|c| matches!(c,'\r'|'\n'|'\0')) { return Err("User-Agent 无效".into()); }
    Ok(Options { workers: workers as usize,verify,user_agent })
}
fn source_spec(params: &Value) -> Result<SourceSpec> {
    let source = params.get("source").filter(|v| v.is_object()).ok_or_else(|| "请选择固件来源".to_string())?;
    let kind = source.get("kind").and_then(Value::as_str).unwrap_or_default();
    let value = source.get("value").and_then(Value::as_str).unwrap_or_default().trim();
    if value.is_empty() || value.len() > 32768 || value.contains('\0') { return Err("固件来源无效".into()); }
    match kind {
        "file" => { let path = Path::new(value); if !path.is_absolute() || !path.is_file() { return Err("固件文件不存在或不是绝对路径".into()); } }
        "url" => { let url = reqwest::Url::parse(value).map_err(|_| "下载直链无效".to_string())?; if !matches!(url.scheme(),"http"|"https") || url.host_str().is_none() { return Err("只支持 HTTP(S) 下载直链".into()); } }
        _ => return Err("未知来源类型".into()),
    }
    Ok(SourceSpec { kind: kind.into(),value: value.into() })
}
fn selected(params: &Value) -> Result<Vec<String>> {
    let array = params.get("partitions").and_then(Value::as_array).ok_or_else(|| "请选择分区".to_string())?;
    if array.is_empty() || array.len() > 10_000 { return Err("请选择分区，且分区数量不超过 10000".into()); }
    let mut out = Vec::new();
    for value in array { let name = value.as_str().ok_or_else(|| "分区标识无效".to_string())?.to_string(); if !out.contains(&name) { out.push(name); } }
    Ok(out)
}
fn write_protocol(output: &Arc<Mutex<BufWriter<io::Stdout>>>,value: &Value) -> bool {
    let Ok(mut writer) = output.lock() else { return false; };
    serde_json::to_writer(&mut *writer,value).is_ok() && writer.write_all(b"\n").is_ok() && writer.flush().is_ok()
}
fn protocol_line(input: &mut impl BufRead) -> io::Result<Option<(Vec<u8>,bool)>> {
    let mut bytes = Vec::with_capacity(4096); let mut oversized = false; let mut saw_input = false;
    loop {
        let buffer = input.fill_buf()?;
        if buffer.is_empty() { return Ok(if saw_input { Some((bytes,oversized)) } else { None }); }
        saw_input = true;
        let newline = buffer.iter().position(|b| *b == b'\n'); let count = newline.map(|i| i+1).unwrap_or(buffer.len());
        let available = (1024*1024usize).saturating_sub(bytes.len());
        bytes.extend_from_slice(&buffer[..count.min(available)]); if count > available { oversized = true; }
        input.consume(count);
        if newline.is_some() { return Ok(Some((bytes,oversized))); }
    }
}
/// Serve the existing id/ok/result JSONL protocol. The Tauri host uses Controller
/// directly and does not bundle this regression executable as an extra worker.
pub fn run_stdio() -> i32 {
    let output = Arc::new(Mutex::new(BufWriter::new(io::stdout())));
    let event_output = output.clone();
    let sink: EventSink = Arc::new(move |value| { let _ = write_protocol(&event_output,&value); });
    let controller = Controller::new(sink); let mut input = BufReader::new(io::stdin()); let mut code = 0;
    loop {
        let line = match protocol_line(&mut input) { Ok(Some(v)) => v,Ok(None) => break,Err(_) => { code = 1; break; } };
        if !line.1 && line.0.iter().all(u8::is_ascii_whitespace) { continue; }
        let mut id = String::new(); let mut stop = false;
        let response = (|| -> Result<Value> {
            if line.1 { return Err("请求超出 1 MiB 大小限制".into()); }
            let request: Value = serde_json::from_slice(&line.0).map_err(|_| "请求 JSON 无效".to_string())?;
            id = request.get("id").and_then(Value::as_str).ok_or_else(|| "缺少请求 ID".to_string())?.to_string();
            if id.chars().count() > 128 { return Err("请求 ID 无效".into()); }
            let method = request.get("method").and_then(Value::as_str).ok_or_else(|| "缺少引擎方法".to_string())?;
            let result = controller.request(method,request.get("params").cloned().unwrap_or_else(|| json!({})))?;
            stop = method == "shutdown"; Ok(result)
        })();
        let response = match response { Ok(result) => json!({"id":id,"ok":true,"result":result}),Err(error) => json!({"id":id,"ok":false,"error":sanitize_message(&error)}) };
        if !write_protocol(&output,&response) { code = 1; break; }
        if stop { break; }
    }
    controller.shutdown(); code
}
