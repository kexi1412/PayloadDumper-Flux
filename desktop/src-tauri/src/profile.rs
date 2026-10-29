use crate::guards::{self, TrustedRoot};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{collections::HashSet, fs::{self, OpenOptions}, io::{Read, Write}, path::{Path, PathBuf}, time::{SystemTime, UNIX_EPOCH}};
use uuid::Uuid;

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis().min(u64::MAX as u128) as u64
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EngineOptions {
    pub workers: u8,
    pub verify: bool,
    pub user_agent: String,
}

impl EngineOptions {
    pub fn validated(mut self) -> Result<Self, String> {
        if !(1..=16).contains(&self.workers) { return Err("并行线程必须在 1–16 之间".into()); }
        self.user_agent = self.user_agent.trim().to_owned();
        if self.user_agent.len() > 512 || self.user_agent.contains(['\r', '\n', '\0']) {
            return Err("User-Agent 不能包含换行或超过 512 字符".into());
        }
        Ok(self)
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Preferences {
    pub workers: u8,
    pub verify: bool,
    pub user_agent: String,
    pub theme: String,
    pub output_dir: String,
}

impl Preferences {
    pub fn defaults(output_dir: &Path) -> Self {
        Self { workers: 4, verify: true, user_agent: String::new(), theme: "light".into(), output_dir: output_dir.to_string_lossy().into_owned() }
    }

    pub fn validated(mut self, defaults: &Self) -> Result<Self, String> {
        if !matches!(self.theme.as_str(), "light" | "dark" | "system") { return Err("主题设置无效".into()); }
        let options = EngineOptions { workers: self.workers, verify: self.verify, user_agent: self.user_agent }.validated()?;
        self.user_agent = options.user_agent;
        if self.output_dir.is_empty() { self.output_dir = defaults.output_dir.clone(); }
        self.output_dir = guards::absolute(&self.output_dir, "输出目录")?.to_string_lossy().into_owned();
        Ok(self)
    }

    pub fn extraction_changed(&self, other: &Self) -> bool {
        self.workers != other.workers || self.verify != other.verify
            || self.user_agent != other.user_agent || self.output_dir != other.output_dir
    }

    pub fn allow_update_from(&self, previous: &Self, busy: bool, dialog_open: bool) -> Result<(), String> {
        if busy && self.extraction_changed(previous) { return Err("任务运行时不能更改提取设置".into()); }
        if dialog_open && self.output_dir != previous.output_dir { return Err("请先关闭原生目录选择对话框".into()); }
        Ok(())
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
    pub id: String,
    pub archive_name: String,
    pub output_dir: String,
    pub started_at: u64,
    pub finished_at: u64,
    pub total_bytes: u64,
    pub completed: usize,
    pub failed: usize,
    pub status: String,
    pub partitions: Vec<String>,
}

impl HistoryEntry {
    fn valid(&self) -> bool {
        !self.id.is_empty() && self.id.len() <= 256 && self.archive_name.len() <= 32768
            && guards::absolute(&self.output_dir, "历史输出目录").is_ok()
            && matches!(self.status.as_str(), "completed" | "cancelled" | "failed")
            && self.partitions.len() <= 10000 && self.partitions.iter().all(|name| !name.is_empty() && name.len() <= 4096 && !name.contains('\0'))
    }
}

pub struct CurrentTask {
    pub id: String,
    pub started_at: u64,
    pub started: bool,
    pub partitions: Vec<String>,
    pub archive_name: String,
    pub output_dir: String,
}

pub struct HistoryUpdate {
    pub entries: Vec<HistoryEntry>,
    pub warning: Option<String>,
}

pub struct Profile {
    pub directory: PathBuf,
    pub defaults: Preferences,
    pub preferences: Preferences,
    pub history: Vec<HistoryEntry>,
    pub roots: Vec<TrustedRoot>,
    pub current_task: Option<CurrentTask>,
}

fn load_json<T: serde::de::DeserializeOwned>(path: &Path) -> Option<T> {
    guards::no_links(path).ok()?;
    let file = fs::File::open(path).ok()?;
    if file.metadata().ok()?.len() > 16 * 1024 * 1024 { return None; }
    let mut bytes = Vec::new();
    file.take(16 * 1024 * 1024 + 1).read_to_end(&mut bytes).ok()?;
    if bytes.len() > 16 * 1024 * 1024 { return None; }
    // Electron wrote UTF-8 without BOM; accept UTF-8 BOM from user-edited profiles.
    let bytes = bytes.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(&bytes);
    serde_json::from_slice(bytes).ok()
}

impl Profile {
    pub fn load(directory: PathBuf, downloads: PathBuf, migrate_legacy: bool) -> Result<Self, String> {
        guards::no_links(&directory)?;
        fs::create_dir_all(&directory).map_err(|error| format!("无法创建用户数据目录：{error}"))?;
        let directory = guards::canonical(&directory)?;
        let default_output = downloads.join("PayloadDumper-Flux");
        let defaults = Preferences::defaults(&default_output);
        let mut legacy = Vec::new();
        if migrate_legacy {
            if let Some(roaming) = std::env::var_os("APPDATA") {
                let roaming = PathBuf::from(roaming);
                legacy.extend([roaming.join("Payload Dumper Flux"), roaming.join("payload-dumper-flux-desktop")]);
            }
        }
        // Copy only settings/history JSON, never a browser session, worker, or executable.
        for name in ["preferences.json", "history.json"] {
            let destination = directory.join(name);
            if destination.try_exists().map_err(|error| error.to_string())? { continue; }
            for root in &legacy {
                let source = root.join(name);
                if let Some(value) = load_json::<Value>(&source) {
                    Self::save_at(&directory, name, &value)?;
                    break;
                }
            }
        }
        let preferences = load_json::<Preferences>(&directory.join("preferences.json"))
            .and_then(|value| value.validated(&defaults).ok()).unwrap_or_else(|| defaults.clone());
        let history = load_json::<Vec<HistoryEntry>>(&directory.join("history.json"))
            .unwrap_or_default().into_iter().filter(HistoryEntry::valid).take(100).collect::<Vec<_>>();
        let output = guards::absolute(&preferences.output_dir, "输出目录")?;
        guards::no_links(&output)?;
        fs::create_dir_all(&output).map_err(|error| format!("无法创建输出目录：{error}"))?;
        let root = TrustedRoot::new(&output)?;
        let mut preferences = preferences;
        preferences.output_dir = root.path().to_string_lossy().into_owned();
        let roots = vec![root];
        // Historical output roots are checked from the current history in open-path;
        // clearing history must revoke authority that was never explicitly chosen.
        Ok(Self { directory, defaults, preferences, history, roots, current_task: None })
    }

    fn save_at<T: Serialize>(directory: &Path, name: &str, value: &T) -> Result<(), String> {
        if !matches!(name, "preferences.json" | "history.json") { return Err("拒绝未知用户数据文件".into()); }
        guards::no_links(directory)?;
        let destination = directory.join(name);
        guards::no_links(&destination)?;
        let temporary = directory.join(format!("{name}.{}.tmp", Uuid::new_v4()));
        let result = (|| {
            let mut file = OpenOptions::new().write(true).create_new(true).open(&temporary)
                .map_err(|error| error.to_string())?;
            serde_json::to_writer_pretty(&mut file, value).map_err(|error| error.to_string())?;
            file.write_all(b"\n").map_err(|error| error.to_string())?;
            file.sync_all().map_err(|error| error.to_string())?;
            drop(file);
            fs::rename(&temporary, &destination).map_err(|error| error.to_string())
        })();
        if result.is_err() { let _ = fs::remove_file(&temporary); }
        result
    }

    pub fn save_preferences(&mut self, value: Preferences) -> Result<(), String> {
        Self::save_at(&self.directory, "preferences.json", &value)?;
        self.preferences = value;
        Ok(())
    }

    pub fn clear_history(&mut self) -> Result<(), String> {
        Self::save_at(&self.directory, "history.json", &Vec::<HistoryEntry>::new())?;
        self.history.clear();
        Ok(())
    }

    /// Consume the supplied event only: never reenter Controller::snapshot/request.
    pub fn finish_history(&mut self, state: &Value) -> Option<HistoryUpdate> {
        let task = self.current_task.as_mut()?;
        let phase = state["phase"].as_str().unwrap_or("");
        if phase == "extracting" { task.started = true; return None; }
        if !task.started || !matches!(phase, "ready" | "error" | "idle") { return None; }
        let task = self.current_task.take()?;
        let selected: HashSet<&str> = task.partitions.iter().map(String::as_str).collect();
        let mut completed = 0;
        let mut failed = 0;
        let mut total_bytes = 0u64;
        if let Some(rows) = state["partitions"].as_array() {
            for row in rows {
                if !row["partitionName"].as_str().is_some_and(|name| selected.contains(name)) { continue; }
                match row["extractState"].as_str() {
                    Some("DONE") => {
                        completed += 1;
                        total_bytes = total_bytes.saturating_add(row["size"].as_u64().unwrap_or(0));
                    }
                    Some("ERROR") => failed += 1,
                    _ => (),
                }
            }
        }
        let status = if failed > 0 || phase == "error" { "failed" }
            else if completed == selected.len() { "completed" } else { "cancelled" };
        let entry = HistoryEntry {
            id: task.id, archive_name: state["archive"]["fileName"].as_str().unwrap_or(&task.archive_name).to_owned(),
            output_dir: state["outputDir"].as_str().unwrap_or(&task.output_dir).to_owned(),
            started_at: task.started_at, finished_at: now_ms(), total_bytes, completed, failed,
            status: status.into(), partitions: task.partitions,
        };
        self.history.insert(0, entry);
        self.history.truncate(100);
        let warning = Self::save_at(&self.directory, "history.json", &self.history)
            .err().map(|error| format!("记录保存失败：{error}"));
        Some(HistoryUpdate { entries: self.history.clone(), warning })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn fixture() -> (tempfile::TempDir, Profile, PathBuf) {
        let temporary = tempfile::Builder::new().prefix("flux-shell-profile-")
            .tempdir_in(dunce::canonicalize(std::env::temp_dir()).unwrap()).unwrap();
        let downloads = temporary.path().join("Downloads");
        let profile = Profile::load(temporary.path().join("profile"), downloads.clone(), false).unwrap();
        (temporary, profile, downloads)
    }

    fn task(profile: &Profile, id: &str, names: &[&str]) -> CurrentTask {
        CurrentTask { id: id.into(), started_at: 1234, started: false,
            archive_name: "firmware.zip".into(), output_dir: profile.preferences.output_dir.clone(),
            partitions: names.iter().map(|name| (*name).to_owned()).collect() }
    }

    fn row(name: &str, size: u64, status: &str) -> Value {
        json!({ "partitionName": name, "size": size, "extractState": status })
    }

    fn state(phase: &str, rows: Vec<Value>, output: &str) -> Value {
        json!({ "phase": phase, "archive": { "fileName": "actual-firmware.zip" }, "partitions": rows, "outputDir": output })
    }

    fn history(profile: &Profile, id: &str) -> HistoryEntry {
        HistoryEntry { id: id.into(), archive_name: "old.zip".into(), output_dir: profile.preferences.output_dir.clone(),
            started_at: 1, finished_at: 2, total_bytes: 4096, completed: 1, failed: 0,
            status: "completed".into(), partitions: vec!["system".into()] }
    }

    #[test]
    fn preferences_validate_fields_and_reject_unknown_data() {
        let (_temporary, profile, _downloads) = fixture();
        let defaults = profile.defaults.clone();
        assert!(defaults.clone().validated(&defaults).is_ok());
        for theme in ["sepia", "", "LIGHT"] {
            let mut value = defaults.clone(); value.theme = theme.into();
            assert!(value.validated(&defaults).is_err());
        }
        for workers in [0, 17, 255] {
            let mut value = defaults.clone(); value.workers = workers;
            assert!(value.validated(&defaults).is_err());
        }
        for agent in ["hello\rheader".to_owned(), "hello\nheader".to_owned(), "agent\0value".to_owned(), "x".repeat(513)] {
            let mut value = defaults.clone(); value.user_agent = agent;
            assert!(value.validated(&defaults).is_err());
        }
        let mut value = defaults.clone(); value.output_dir = "relative/output".into();
        assert!(value.validated(&defaults).is_err());
        let mut encoded = serde_json::to_value(&defaults).unwrap();
        encoded["unknownSetting"] = json!(true);
        assert!(serde_json::from_value::<Preferences>(encoded).is_err());
        assert!(serde_json::from_value::<EngineOptions>(json!({"workers": 4, "verify": "true", "userAgent": ""})).is_err());
        assert!(serde_json::from_value::<EngineOptions>(json!({"workers": 4, "verify": true, "userAgent": "", "command": "exec"})).is_err());
    }

    #[test]
    fn busy_preferences_allow_only_appearance_updates() {
        let (_temporary, profile, _downloads) = fixture();
        let previous = profile.preferences;
        let mut appearance = previous.clone(); appearance.theme = "dark".into();
        assert!(appearance.allow_update_from(&previous, true, false).is_ok());
        for field in ["workers", "verify", "userAgent", "outputDir"] {
            let mut changed = previous.clone();
            match field {
                "workers" => changed.workers = 8,
                "verify" => changed.verify = !changed.verify,
                "userAgent" => changed.user_agent = "Flux-Test".into(),
                "outputDir" => changed.output_dir.push_str("-other"),
                _ => unreachable!(),
            }
            assert!(changed.allow_update_from(&previous, true, false).is_err(), "accepted busy {field}");
            assert!(changed.allow_update_from(&previous, false, false).is_ok());
        }
        let mut output = previous.clone(); output.output_dir.push_str("-new");
        assert!(output.allow_update_from(&previous, false, true).is_err());
        assert!(appearance.allow_update_from(&previous, true, true).is_ok());
    }

    #[test]
    fn preferences_are_saved_and_loaded_from_the_real_profile_only() {
        let (_temporary, mut profile, downloads) = fixture();
        let mut preferences = profile.preferences.clone();
        preferences.theme = "dark".into(); preferences.workers = 2; preferences.user_agent = "Flux Native Test".into();
        profile.save_preferences(preferences.clone()).unwrap();
        let loaded = Profile::load(profile.directory.clone(), downloads, false).unwrap();
        assert_eq!(serde_json::to_value(loaded.preferences).unwrap(), serde_json::to_value(preferences).unwrap());
        assert!(profile.directory.join("preferences.json").is_file());
        assert!(fs::read_dir(&profile.directory).unwrap().all(|entry| !entry.unwrap().path().to_string_lossy().ends_with(".tmp")));
    }

    #[test]
    fn history_starts_only_after_an_actual_extracting_event() {
        let (_temporary, mut profile, _downloads) = fixture();
        let output = profile.preferences.output_dir.clone();
        profile.current_task = Some(task(&profile, "actual-task", &["system"]));
        let complete = state("ready", vec![row("system", 4096, "DONE")], &output);
        assert!(profile.finish_history(&complete).is_none());
        assert!(profile.history.is_empty());
        assert!(profile.finish_history(&state("extracting", Vec::new(), &output)).is_none());
        assert!(profile.finish_history(&state("cancelling", Vec::new(), &output)).is_none());
        let update = profile.finish_history(&complete).unwrap();
        assert_eq!(update.entries[0].status, "completed");
        assert_eq!(update.entries[0].archive_name, "actual-firmware.zip");
        assert_eq!(update.entries[0].completed, 1);
        assert_eq!(update.entries[0].total_bytes, 4096);
        assert!(profile.current_task.is_none());
        assert!(profile.finish_history(&complete).is_none());
        assert_eq!(profile.history.len(), 1);
    }

    #[test]
    fn history_counts_selected_complete_failed_and_cancelled_rows() {
        let (_temporary, mut profile, _downloads) = fixture();
        let output = profile.preferences.output_dir.clone();
        profile.current_task = Some(task(&profile, "failure", &["system", "vendor", "boot"]));
        profile.finish_history(&state("extracting", Vec::new(), &output));
        let update = profile.finish_history(&state("error", vec![row("system", 4096, "DONE"), row("vendor", 8192, "ERROR"),
            row("boot", 2048, "PAUSED"), row("not-selected", 999999, "DONE")], &output)).unwrap();
        let record = &update.entries[0];
        assert_eq!((record.completed, record.failed, record.total_bytes), (1, 1, 4096));
        assert_eq!(record.status, "failed");
        assert_eq!(record.partitions, vec!["system", "vendor", "boot"]);
        assert!(record.finished_at >= record.started_at);
        assert!(update.warning.is_none());

        profile.current_task = Some(task(&profile, "cancelled", &["system", "vendor"]));
        profile.finish_history(&state("extracting", Vec::new(), &output));
        let update = profile.finish_history(&state("ready", vec![row("system", 512, "DONE"), row("vendor", 1024, "PAUSED")], &output)).unwrap();
        assert_eq!(update.entries[0].status, "cancelled");
        assert_eq!((update.entries[0].completed, update.entries[0].failed, update.entries[0].total_bytes), (1, 0, 512));
    }

    #[test]
    fn history_is_bounded_to_100_on_load_and_insert() {
        let (_temporary, profile, downloads) = fixture();
        let records: Vec<_> = (0..125).map(|index| history(&profile, &format!("old-{index}"))).collect();
        Profile::save_at(&profile.directory, "history.json", &records).unwrap();
        let mut loaded = Profile::load(profile.directory.clone(), downloads.clone(), false).unwrap();
        assert_eq!(loaded.history.len(), 100);
        assert_eq!(loaded.history[0].id, "old-0");
        assert_eq!(loaded.history[99].id, "old-99");
        let output = loaded.preferences.output_dir.clone();
        loaded.current_task = Some(task(&loaded, "latest", &["system"]));
        loaded.finish_history(&state("extracting", Vec::new(), &output));
        loaded.finish_history(&state("ready", vec![row("system", 2048, "DONE")], &output)).unwrap();
        assert_eq!(loaded.history.len(), 100);
        assert_eq!(loaded.history[0].id, "latest");
        assert_eq!(loaded.history[99].id, "old-98");
        let reloaded = Profile::load(loaded.directory.clone(), downloads, false).unwrap();
        assert_eq!(reloaded.history.len(), 100);
        assert_eq!(reloaded.history[0].id, "latest");
    }

    #[test]
    fn clearing_history_is_durable_and_never_deletes_output_images() {
        let (_temporary, mut profile, downloads) = fixture();
        let image = PathBuf::from(&profile.preferences.output_dir).join("system.img");
        fs::write(&image, b"real-image-bytes").unwrap();
        profile.history.push(history(&profile, "old"));
        Profile::save_at(&profile.directory, "history.json", &profile.history).unwrap();
        profile.clear_history().unwrap();
        assert!(profile.history.is_empty());
        assert_eq!(fs::read(&image).unwrap(), b"real-image-bytes");
        let loaded = Profile::load(profile.directory.clone(), downloads, false).unwrap();
        assert!(loaded.history.is_empty());
        assert!(image.is_file());
    }

    #[test]
    fn malformed_and_invalid_profile_values_do_not_authorize_history_paths() {
        let (_temporary, mut profile, downloads) = fixture();
        let mut invalid = history(&profile, "invalid"); invalid.output_dir = "relative/escape".into();
        let mut invalid_status = history(&profile, "bad-status"); invalid_status.status = "unknown".into();
        let valid = history(&profile, "valid");
        Profile::save_at(&profile.directory, "history.json", &vec![invalid, invalid_status, valid]).unwrap();
        let loaded = Profile::load(profile.directory.clone(), downloads.clone(), false).unwrap();
        assert_eq!(loaded.history.len(), 1);
        assert_eq!(loaded.history[0].id, "valid");
        profile.preferences.theme = "unknown".into();
        Profile::save_at(&profile.directory, "preferences.json", &profile.preferences).unwrap();
        let loaded = Profile::load(profile.directory.clone(), downloads, false).unwrap();
        assert_eq!(loaded.preferences.theme, "light");
        assert!(Profile::save_at(&profile.directory, "../arbitrary.json", &json!({})).is_err());
    }
}
