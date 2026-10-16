//! Owning-app lookup for tunnelled connections.
//!
//! Android hands the active VPN app the owner of a connection it carries
//! through `ConnectivityManager.getConnectionOwnerUid` (API 29+), keyed on
//! the app socket's own 4-tuple. Only the TUN inbound still has that tuple
//! — the engine rewrites a fake-IP destination to its hostname before any
//! rule runs — so tun2socks asks here before handing a connection to the
//! tunnel, and stores the answer in `Metadata.uid` / `Metadata.process`
//! (the package name: the PROCESS-NAME key on Android, as in mihomo).
//!
//! The lookup is a binder round trip into the system server. It runs on
//! the blocking pool under a short deadline; a miss only leaves the
//! connection without an owner. Below API 29, or before the VPN service
//! registers, nothing is installed and no lookup is attempted.

use parking_lot::Mutex;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

/// `IPPROTO_TCP` / `IPPROTO_UDP`, the protocol numbers
/// `getConnectionOwnerUid` takes.
pub const IPPROTO_TCP: i32 = 6;
pub const IPPROTO_UDP: i32 = 17;

/// Upper bound one lookup may add to connection setup.
const LOOKUP_DEADLINE: Duration = Duration::from_millis(100);

/// The app that owns one connection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Owner {
    pub uid: u32,
    /// First package installed under `uid`; `None` for uids no package owns
    /// (root, system daemons).
    pub package: Option<Arc<str>>,
}

/// Owner of the app socket `local` → `remote`, or `None` when it cannot be
/// told in time.
pub async fn lookup(protocol: i32, local: SocketAddr, remote: SocketAddr) -> Option<Owner> {
    if !inner::installed() {
        return None;
    }
    let task = tokio::task::spawn_blocking(move || inner::lookup(protocol, local, remote));
    match tokio::time::timeout(LOOKUP_DEADLINE, task).await {
        Ok(Ok(owner)) => owner,
        _ => None,
    }
}

/// Register the running `VpnService`. Called from `nativeStartTun2Socks`;
/// safe to call again across VPN restarts.
pub fn install(env: &mut jni::JNIEnv, service: &jni::objects::JObject) {
    inner::install(env, service);
}

/// Drop the service reference and the package cache on VPN tear-down.
pub fn clear() {
    inner::clear();
}

/// `getConnectionOwnerUid` result → uid; `Process.INVALID_UID` (-1) and any
/// other negative value mean the owner is unknown.
#[cfg_attr(
    not(target_os = "android"),
    allow(
        dead_code,
        reason = "used by the Android lookup; host builds only test it"
    )
)]
fn uid_from_jint(uid: i32) -> Option<u32> {
    u32::try_from(uid).ok()
}

/// uid → package memo. Apps keep their uid for as long as they stay
/// installed, so only the VPN session bounds an entry's lifetime; failed
/// resolutions are not stored and get retried by the next connection.
#[cfg_attr(
    not(target_os = "android"),
    allow(
        dead_code,
        reason = "used by the Android lookup; host builds only test it"
    )
)]
#[derive(Default)]
struct PackageCache(Mutex<HashMap<u32, Option<Arc<str>>>>);

#[cfg_attr(
    not(target_os = "android"),
    allow(
        dead_code,
        reason = "used by the Android lookup; host builds only test it"
    )
)]
impl PackageCache {
    fn get_or_resolve<E>(
        &self,
        uid: u32,
        resolve: impl FnOnce(u32) -> Result<Option<String>, E>,
    ) -> Result<Option<Arc<str>>, E> {
        if let Some(hit) = self.0.lock().get(&uid) {
            return Ok(hit.clone());
        }
        // Resolved outside the lock: it is a binder call, and concurrent
        // misses on one uid at worst resolve it twice.
        let package: Option<Arc<str>> = resolve(uid)?.map(Into::into);
        self.0.lock().insert(uid, package.clone());
        Ok(package)
    }
}

#[cfg(target_os = "android")]
mod inner {
    use super::{uid_from_jint, Owner, PackageCache};
    use jni::objects::{GlobalRef, JObject, JString, JValue};
    use jni::{JNIEnv, JavaVM};
    use parking_lot::RwLock;
    use std::net::{IpAddr, SocketAddr};
    use std::sync::Arc;

    /// First release with `ConnectivityManager.getConnectionOwnerUid`.
    const API_Q: i32 = 29;

    struct OwnerLookup {
        jvm: JavaVM,
        service: GlobalRef,
        packages: PackageCache,
    }

    static LOOKUP: RwLock<Option<Arc<OwnerLookup>>> = RwLock::new(None);

    pub(super) fn installed() -> bool {
        LOOKUP.read().is_some()
    }

    pub(super) fn install(env: &mut JNIEnv, service: &JObject) {
        *LOOKUP.write() = None;
        let sdk = match env
            .get_static_field("android/os/Build$VERSION", "SDK_INT", "I")
            .and_then(|v| v.i())
        {
            Ok(sdk) => sdk,
            Err(e) => {
                clear_pending_exception(env);
                crate::logging::bridge_log(&format!("owner: reading SDK_INT failed: {e}"));
                return;
            }
        };
        if sdk < API_Q {
            crate::logging::bridge_log(&format!(
                "owner: API {sdk} has no getConnectionOwnerUid; lookup disabled"
            ));
            return;
        }
        let lookup = match (env.get_java_vm(), env.new_global_ref(service)) {
            (Ok(jvm), Ok(service)) => OwnerLookup {
                jvm,
                service,
                packages: PackageCache::default(),
            },
            (Err(e), _) | (_, Err(e)) => {
                crate::logging::bridge_log(&format!("owner: install failed: {e}"));
                return;
            }
        };
        *LOOKUP.write() = Some(Arc::new(lookup));
        crate::logging::bridge_log("owner: connection owner lookup installed");
    }

    pub(super) fn clear() {
        *LOOKUP.write() = None;
    }

    pub(super) fn lookup(protocol: i32, local: SocketAddr, remote: SocketAddr) -> Option<Owner> {
        let lookup = LOOKUP.read().clone()?;
        let mut env = lookup.jvm.attach_current_thread().ok()?;
        // The frame frees every local reference made here even when this
        // thread was already attached and the guard does not detach it.
        let result = env.with_local_frame(8, |env| -> jni::errors::Result<Option<Owner>> {
            let Some(uid) =
                uid_from_jint(owner_uid(env, &lookup.service, protocol, local, remote)?)
            else {
                return Ok(None);
            };
            let package = lookup
                .packages
                .get_or_resolve(uid, |uid| package_for_uid(env, &lookup.service, uid))?;
            Ok(Some(Owner { uid, package }))
        });
        match result {
            Ok(owner) => owner,
            Err(e) => {
                clear_pending_exception(&mut env);
                tracing::debug!("owner: lookup {local} -> {remote} failed: {e}");
                None
            }
        }
    }

    fn owner_uid(
        env: &mut JNIEnv,
        service: &GlobalRef,
        protocol: i32,
        local: SocketAddr,
        remote: SocketAddr,
    ) -> jni::errors::Result<i32> {
        let local_ip = env.byte_array_from_slice(&ip_octets(local.ip()))?;
        let remote_ip = env.byte_array_from_slice(&ip_octets(remote.ip()))?;
        env.call_method(
            service,
            "connectionOwnerUid",
            "(I[BI[BI)I",
            &[
                JValue::Int(protocol),
                JValue::Object(&local_ip),
                JValue::Int(i32::from(local.port())),
                JValue::Object(&remote_ip),
                JValue::Int(i32::from(remote.port())),
            ],
        )?
        .i()
    }

    fn package_for_uid(
        env: &mut JNIEnv,
        service: &GlobalRef,
        uid: u32,
    ) -> jni::errors::Result<Option<String>> {
        // Came from a non-negative jint, so it always fits back.
        let Ok(uid) = i32::try_from(uid) else {
            return Ok(None);
        };
        let package = env
            .call_method(
                service,
                "packageForUid",
                "(I)Ljava/lang/String;",
                &[JValue::Int(uid)],
            )?
            .l()?;
        if package.is_null() {
            return Ok(None);
        }
        let package = JString::from(package);
        let package: String = env.get_string(&package)?.into();
        Ok(Some(package))
    }

    fn ip_octets(ip: IpAddr) -> Vec<u8> {
        match ip {
            IpAddr::V4(v4) => v4.octets().to_vec(),
            IpAddr::V6(v6) => v6.octets().to_vec(),
        }
    }

    /// A Java exception left pending makes every later JNI call on this
    /// thread undefined; the Kotlin callbacks catch their own, so this only
    /// covers JNI-level failures.
    fn clear_pending_exception(env: &mut JNIEnv) {
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    }
}

#[cfg(not(target_os = "android"))]
mod inner {
    use super::Owner;
    use std::net::SocketAddr;

    pub(super) fn installed() -> bool {
        false
    }

    pub(super) fn install(_env: &mut jni::JNIEnv, _service: &jni::objects::JObject) {}

    pub(super) fn clear() {}

    pub(super) fn lookup(_protocol: i32, _local: SocketAddr, _remote: SocketAddr) -> Option<Owner> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_uid_means_no_owner() {
        assert_eq!(uid_from_jint(-1), None);
        assert_eq!(uid_from_jint(i32::MIN), None);
        assert_eq!(uid_from_jint(0), Some(0));
        assert_eq!(uid_from_jint(10_123), Some(10_123));
        assert_eq!(uid_from_jint(1_010_123), Some(1_010_123));
    }

    #[test]
    fn package_cache_resolves_each_uid_once() {
        let cache = PackageCache::default();
        let mut calls = 0;
        for _ in 0..3 {
            let got = cache.get_or_resolve(10_123, |uid| {
                calls += 1;
                assert_eq!(uid, 10_123);
                Ok::<_, ()>(Some("com.example.app".to_string()))
            });
            assert_eq!(got.unwrap().as_deref(), Some("com.example.app"));
        }
        assert_eq!(calls, 1);
    }

    #[test]
    fn package_cache_remembers_uids_without_a_package() {
        let cache = PackageCache::default();
        let mut calls = 0;
        for _ in 0..2 {
            let got = cache.get_or_resolve(1051, |_| {
                calls += 1;
                Ok::<_, ()>(None)
            });
            assert_eq!(got, Ok(None));
        }
        assert_eq!(calls, 1);
    }

    #[test]
    fn package_cache_retries_failed_resolutions() {
        let cache = PackageCache::default();
        assert_eq!(
            cache.get_or_resolve(10_200, |_| Err("binder died")),
            Err("binder died")
        );
        let got = cache.get_or_resolve(10_200, |_| Ok::<_, &str>(Some("com.example.retry".into())));
        assert_eq!(got.unwrap().as_deref(), Some("com.example.retry"));
    }

    #[tokio::test]
    async fn lookup_without_a_service_is_none() {
        let local: SocketAddr = "172.19.0.1:40000".parse().unwrap();
        let remote: SocketAddr = "198.18.0.7:443".parse().unwrap();
        assert_eq!(lookup(IPPROTO_TCP, local, remote).await, None);
    }
}
