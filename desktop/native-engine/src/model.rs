use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ArchiveInfo {
    pub file_name: String,
    pub file_size: u64,
    pub security_patch_level: String,
    pub partition_count: usize,
    pub block_size: u32,
    pub android_version: String,
    pub kernel_version: String,
    pub fingerprint: String,
}
impl ArchiveInfo {
    pub fn new(name: String, size: u64, count: usize, block: u32) -> Self {
        Self { file_name: name, file_size: size, partition_count: count, block_size: block,
            security_patch_level: String::new(), android_version: String::new(),
            kernel_version: String::new(), fingerprint: String::new() }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Row {
    pub partition_name: String,
    pub display_title: String,
    pub subtitle: String,
    pub size: u64,
    pub compressed_size: u64,
    pub sha256: String,
    pub extract_state: String,
    pub verify_state: String,
    pub progress: f64,
    pub message: String,
}
impl Row {
    pub fn new(key: String, title: String, subtitle: String, size: u64, compressed: u64, hash: String) -> Self {
        Self { partition_name: key, display_title: title, subtitle, size,
            compressed_size: compressed, sha256: hash, extract_state: "IDLE".into(),
            verify_state: "NONE".into(), progress: 0.0, message: String::new() }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct State {
    pub phase: String,
    pub detail: String,
    pub archive: Option<ArchiveInfo>,
    pub partitions: Vec<Row>,
    pub output_dir: Option<String>,
    pub source_label: Option<String>,
}
impl Default for State {
    fn default() -> Self {
        Self { phase: "idle".into(), detail: String::new(), archive: None,
            partitions: Vec::new(), output_dir: None, source_label: None }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct Options {
    pub workers: usize,
    pub verify: bool,
    pub user_agent: String,
}
impl Default for Options {
    fn default() -> Self { Self { workers: 4, verify: true, user_agent: String::new() } }
}
#[derive(Clone, Debug)]
pub(crate) struct SourceSpec { pub kind: String, pub value: String }
