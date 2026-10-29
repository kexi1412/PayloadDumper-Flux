//! Native engine shared by the Tauri host and the JSONL regression executable.
//! No child worker, JVM, Node runtime, or browser engine is required by this crate.

mod archive;
mod controller;
mod journal;
mod metadata;
mod model;
mod paths;
mod payload;
mod source;
mod util;
mod zip;

pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/chromeos_update_engine.rs"));
}

pub type EventSink = std::sync::Arc<dyn Fn(serde_json::Value) + Send + Sync>;
pub use controller::{run_stdio, Controller};
pub const VERSION: &str = "3.2.0-pc.1";

#[cfg(test)]
#[path = "../tests/support/payload_semantics.rs"]
mod semantic_tests;
