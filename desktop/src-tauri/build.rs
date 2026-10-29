fn main() {
    // Register an app-command ACL rather than exposing unrestricted plugin APIs.
    let attributes = tauri_build::Attributes::new()
        .app_manifest(tauri_build::AppManifest::new().commands(&["flux_invoke"]));
    tauri_build::try_build(attributes).expect("failed to build Flux desktop metadata");
}
