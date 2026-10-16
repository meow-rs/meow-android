//! Offline lookups of where an IP is registered — its country (or the
//! MetaCubeX special code, e.g. `GOOGLE`, `PRIVATE`) and the network (ASN)
//! that owns it — in the GeoX databases seeded into the engine home dir.
//! Backs the connection detail page's IP section, so it runs in the UI
//! process and needs no engine: nothing leaves the device.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use maxminddb::{Mmap, PathElement, Reader};
use parking_lot::Mutex;
use serde::Serialize;
use std::fs::Metadata;
use std::net::IpAddr;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};

/// The engine's discovery names (`meow_config::default_geoip_path`), as
/// seeded by `MeowInstance.copyGeoxAssets`.
const COUNTRY_DB: &str = "Country.mmdb";
const ASN_DB: &str = "GeoLite2-ASN.mmdb";

#[derive(Debug, Default, PartialEq, Serialize)]
pub(crate) struct IpInfo {
    #[serde(skip_serializing_if = "Option::is_none")]
    country: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    asn: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    org: Option<String>,
}

/// One open database and the file it was mapped from. A geodata update
/// replaces the file (write-then-rename), which changes the inode, so the
/// next lookup maps the new one.
struct Db {
    path: PathBuf,
    stamp: (u64, u64, i64, i64),
    reader: Reader<Mmap>,
}

static COUNTRY: Mutex<Option<Db>> = Mutex::new(None);
static ASN: Mutex<Option<Db>> = Mutex::new(None);

fn stamp(meta: &Metadata) -> (u64, u64, i64, i64) {
    (meta.ino(), meta.len(), meta.mtime(), meta.mtime_nsec())
}

/// Runs `f` on the database at `path`, mapping it on first use and again
/// after the file is replaced. None when it is missing or unreadable.
fn with_db<T>(
    slot: &Mutex<Option<Db>>,
    path: &Path,
    f: impl FnOnce(&Reader<Mmap>) -> Option<T>,
) -> Option<T> {
    let stamp = stamp(&std::fs::metadata(path).ok()?);
    let mut slot = slot.lock();
    let fresh = slot
        .as_ref()
        .is_some_and(|db| db.path == path && db.stamp == stamp);
    if !fresh {
        *slot = None;
        // SAFETY: nothing rewrites these files in place. The engine's geodata
        // update writes a sibling and renames it over (meow-config
        // `geodata.rs`), which leaves this mapping on the old inode, and
        // `copyGeoxAssets` only creates a missing or empty file.
        let reader = unsafe { Reader::open_mmap(path) }.ok()?;
        *slot = Some(Db {
            path: path.to_owned(),
            stamp,
            reader,
        });
    }
    f(&slot.as_ref()?.reader)
}

fn country(reader: &Reader<impl AsRef<[u8]>>, ip: IpAddr) -> Option<String> {
    let found = reader.lookup(ip).ok()?;
    // Fall back to where the block is registered: anycast and satellite
    // ranges carry only that.
    ["country", "registered_country"].iter().find_map(|key| {
        let code = found
            .decode_path::<&str>(&[PathElement::Key(key), PathElement::Key("iso_code")])
            .ok()??;
        (!code.is_empty()).then(|| code.to_owned())
    })
}

fn asn(reader: &Reader<impl AsRef<[u8]>>, ip: IpAddr) -> (Option<u32>, Option<String>) {
    let Some(found) = reader.lookup(ip).ok() else {
        return (None, None);
    };
    let number = found
        .decode_path::<u32>(&[PathElement::Key("autonomous_system_number")])
        .ok()
        .flatten();
    let org = found
        .decode_path::<&str>(&[PathElement::Key("autonomous_system_organization")])
        .ok()
        .flatten()
        .filter(|org| !org.is_empty())
        .map(str::to_owned);
    (number, org)
}

pub(crate) fn lookup(dir: &Path, ip: IpAddr) -> IpInfo {
    let ip = ip.to_canonical();
    let country = with_db(&COUNTRY, &dir.join(COUNTRY_DB), |r| country(r, ip));
    let (asn, org) = with_db(&ASN, &dir.join(ASN_DB), |r| Some(asn(r, ip))).unwrap_or_default();
    IpInfo { country, asn, org }
}

/// `{"country": "CN", "asn": 136958, "org": "…"}` for `ip`, looked up in the
/// databases under `dir`; fields it has no answer for are left out, so an
/// unparseable IP gets `{}`. Null only when the JVM could not take it.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeLookupIp(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    ip: JString,
) -> jstring {
    let mut read =
        |s: &JString| -> String { env.get_string(s).map(Into::into).unwrap_or_default() };
    let (dir, ip) = (read(&dir), read(&ip));
    let info = std::panic::catch_unwind(|| {
        ip.trim()
            .parse::<IpAddr>()
            .map(|ip| lookup(Path::new(&dir), ip))
            .unwrap_or_default()
    })
    .unwrap_or_default();
    let json = serde_json::to_string(&info).unwrap_or_else(|_| "{}".to_owned());
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The real databases, when `MEOW_GEOX_DIR` points at them (e.g.
    /// `core/build/generated/assets/downloadGeoxAssets/geox` after
    /// `:core:downloadGeoxAssets`, with `country.mmdb` linked as
    /// `Country.mmdb`); skipped otherwise.
    fn geox_dir() -> Option<PathBuf> {
        let dir = PathBuf::from(std::env::var_os("MEOW_GEOX_DIR")?);
        dir.join(COUNTRY_DB).exists().then_some(dir)
    }

    #[test]
    fn missing_databases_answer_nothing() {
        let dir = std::env::temp_dir().join("meow-ip-info-missing");
        assert_eq!(lookup(&dir, "8.8.8.8".parse().unwrap()), IpInfo::default());
        assert_eq!(serde_json::to_string(&IpInfo::default()).unwrap(), "{}");
    }

    #[test]
    fn looks_up_country_and_network_owner() {
        let Some(dir) = geox_dir() else { return };
        let info = lookup(&dir, "157.148.41.176".parse().unwrap());
        assert_eq!(info.country.as_deref(), Some("CN"));
        assert_eq!(info.asn, Some(136958));
        assert_eq!(
            info.org.as_deref(),
            Some("China Unicom Guangdong IP network")
        );
        // MetaCubeX's special codes come back as they are.
        assert_eq!(
            lookup(&dir, "8.8.8.8".parse().unwrap()).country.as_deref(),
            Some("GOOGLE")
        );
        let private = lookup(&dir, "192.168.1.1".parse().unwrap());
        assert_eq!(private.country.as_deref(), Some("PRIVATE"));
        assert_eq!((private.asn, private.org), (None, None));
        // An IPv4-mapped address is looked up as the IPv4 one.
        let mapped = lookup(&dir, "::ffff:157.148.41.176".parse().unwrap());
        assert_eq!(mapped.asn, Some(136958));
        assert_eq!(
            serde_json::to_string(&info).unwrap(),
            r#"{"country":"CN","asn":136958,"org":"China Unicom Guangdong IP network"}"#,
        );
    }
}
