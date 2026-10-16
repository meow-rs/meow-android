use std::{env, fs, path::Path};

fn main() {
    let lock = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).join("Cargo.lock");
    println!("cargo:rerun-if-changed={}", lock.display());
    println!("cargo:rustc-env=MEOW_RS_VERSION={}", meow_rs_version(&lock));

    // boring-sys links libc++ statically (BORING_BSSL_RUST_CPPLIB=c++_static,
    // exported by core/build.gradle.kts) so the APK doesn't have to package
    // libc++_shared.so. libc++_static.a in turn needs libc++abi.a for the
    // __cxa_* runtime symbols; both are resolved by the NDK clang linker driver
    // from its own sysroot.
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    println!("cargo:rustc-link-lib=c++abi");
}

/// The meow-rs release the engine is built from, as Cargo.lock resolved it
/// (every meow-* crate carries the release's version), so the version
/// Settings shows follows the pin instead of a hand-edited string.
fn meow_rs_version(lock: &Path) -> String {
    let lock = fs::read_to_string(lock).expect("reading Cargo.lock");
    lock.split("[[package]]")
        .find(|pkg| pkg.contains("\nname = \"meow-tunnel\"\n"))
        .and_then(|pkg| {
            pkg.lines()
                .find_map(|line| line.strip_prefix("version = \"")?.strip_suffix('"'))
        })
        .expect("meow-tunnel's version in Cargo.lock")
        .to_string()
}
