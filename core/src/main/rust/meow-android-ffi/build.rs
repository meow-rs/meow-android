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
/// Settings shows follows the pin instead of a hand-edited string. A pin to
/// a commit (`rev = "…"`) between releases reads as the version plus the
/// commit's first seven digits: `0.28.0+d8a2ce7`.
fn meow_rs_version(lock: &Path) -> String {
    let lock = fs::read_to_string(lock).expect("reading Cargo.lock");
    let pkg = lock
        .split("[[package]]")
        .find(|pkg| pkg.contains("\nname = \"meow-tunnel\"\n"))
        .expect("meow-tunnel in Cargo.lock");
    let field = |name: &str| {
        pkg.lines().find_map(|line| {
            line.strip_prefix(name)?
                .strip_prefix(" = \"")?
                .strip_suffix('"')
        })
    };
    let version = field("version").expect("meow-tunnel's version in Cargo.lock");
    let rev = field("source").and_then(|source| source.split_once("?rev=")?.1.get(..7));
    match rev {
        Some(rev) => format!("{version}+{rev}"),
        None => version.to_string(),
    }
}
