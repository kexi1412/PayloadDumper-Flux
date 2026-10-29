fn main() {
    let proto = "../../app/src/main/proto/update_metadata.proto";
    println!("cargo:rerun-if-changed={proto}");
    println!("cargo:rerun-if-changed=build.rs");
    let protoc = protoc_bin_vendored::protoc_bin_path().expect("vendored protoc is unavailable");
    std::env::set_var("PROTOC", protoc);
    prost_build::Config::new()
        .compile_protos(&[proto], &["../../app/src/main/proto"])
        .expect("generate AOSP payload protobuf");
}
