//! Per-node diagnostics behind the proxy detail page.
//!
//! The UI process asks `:vpn` (`IMeowService.probeProxy`) to run one probe
//! through one named proxy of the running engine:
//!
//! * `udp-latency` — a DNS query's round trip over the node's UDP relay;
//! * `http-get` — a GET of `arg` (the exit-IP lookup);
//! * `nat` — the NAT behaviour the node's UDP relay presents (RFC 5780);
//! * `download` / `upload` — a bandwidth sample against Cloudflare's speed
//!   test endpoints.
//!
//! Each dials the adapter directly, as the engine's own latency test does,
//! so rules play no part, and marks its dial as a probe
//! (`ConnType::Tunnel`) so it does not count as use of a group. Every probe
//! has its own time budget and can be ended early by the UI's id. Answers are
//! JSON objects; a failure is `{"error": …, "code": …}`.
//!
//! With no proxy name a probe goes where the engine's routing sends its
//! traffic instead, for Home's exit card: only `nat` can, routed as an app's
//! UDP flow to the first STUN server would be ([`routed`]).

mod http;
mod stun;
mod udp;

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use meow_common::{ConnType, Metadata, Network, ProxyAdapter};
use meow_tunnel::{ResolvedTarget, Tunnel};
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fmt;
use std::net::SocketAddr;
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

/// Why a probe produced no measurement. `code` is for the UI to branch on;
/// `message` is shown as is.
#[derive(Debug)]
pub(crate) struct ProbeError {
    code: &'static str,
    message: String,
}

impl ProbeError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub(crate) fn failed(message: impl Into<String>) -> Self {
        Self::new("failed", message)
    }

    pub(crate) fn timeout(message: impl Into<String>) -> Self {
        Self::new("timeout", message)
    }

    pub(crate) fn udp_unsupported() -> Self {
        Self::new("udp-unsupported", "the proxy does not relay UDP")
    }
}

impl fmt::Display for ProbeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} ({})", self.message, self.code)
    }
}

type ProbeResult = Result<Value, ProbeError>;

/// A dial marked as a probe: groups skip their usage bump for it, as they do
/// for the engine's own latency test.
pub(crate) fn tcp_metadata(host: &str, port: u16) -> Metadata {
    Metadata {
        network: Network::Tcp,
        conn_type: ConnType::Tunnel,
        host: host.into(),
        dst_port: port,
        ..Default::default()
    }
}

pub(crate) fn udp_metadata(dst: SocketAddr) -> Metadata {
    Metadata {
        network: Network::Udp,
        conn_type: ConnType::Tunnel,
        dst_ip: Some(dst.ip()),
        dst_port: dst.port(),
        ..Default::default()
    }
}

/// Milliseconds for the UI, never 0: a rate divides by it.
pub(crate) fn millis(elapsed: Duration) -> u64 {
    u64::try_from(elapsed.as_millis())
        .unwrap_or(u64::MAX)
        .max(1)
}

async fn probe(proxy: &dyn ProxyAdapter, kind: &str, arg: &str) -> ProbeResult {
    match kind {
        "udp-latency" => udp::latency(proxy).await,
        "http-get" => http::get(proxy, arg).await,
        "nat" => stun::nat_type(proxy).await,
        "download" => http::download(proxy).await,
        "upload" => http::upload(proxy).await,
        other => Err(ProbeError::new(
            "bad-request",
            format!("unknown probe {other:?}"),
        )),
    }
}

/// Runs `kind` through the proxy named `proxy_name`, or with an empty name
/// through whichever adapter the engine routes the probe's traffic to.
async fn probe_through(tunnel: &Tunnel, proxy_name: &str, kind: &str, arg: &str) -> ProbeResult {
    // The route table is held for the whole probe: a `dialer-proxy` chain
    // resolves its front hop through the route generation it was built in.
    if proxy_name.is_empty() {
        let ResolvedTarget {
            adapter,
            route: _route,
            ..
        } = routed(tunnel, kind).await?;
        return probe(adapter.as_ref(), kind, arg).await;
    }
    let routes = tunnel.route_snapshot();
    let proxy = routes
        .proxies
        .get(proxy_name)
        .ok_or_else(|| ProbeError::new("no-proxy", format!("no proxy named {proxy_name:?}")))?;
    probe(proxy.as_ref(), kind, arg).await
}

/// The adapter the engine routes a probe of `kind` to, picked the way the
/// tunnel picks one for a UDP flow from the TUN (`handle_udp`): mode, rules
/// (IP rules on the resolved address), groups' current selections.
async fn routed(tunnel: &Tunnel, kind: &str) -> Result<ResolvedTarget, ProbeError> {
    let (host, port) = match kind {
        "nat" => stun::ROUTED_SERVER,
        other => {
            return Err(ProbeError::new(
                "bad-request",
                format!("probe {other:?} needs a proxy name"),
            ))
        }
    };
    // As the TUN's flows are typed, for the rules: the probe's own dials
    // still mark themselves as probes.
    let mut metadata = Metadata {
        network: Network::Udp,
        conn_type: ConnType::Inner,
        host: host.into(),
        dst_port: port,
        ..Default::default()
    };
    let inner = tunnel.inner();
    inner.pre_resolve(&mut metadata).await;
    inner
        .resolve_proxy(&metadata)
        .await
        .ok_or_else(|| ProbeError::failed("the engine has no route for it"))
}

fn run(id: i64, proxy_name: &str, kind: &str, arg: &str) -> String {
    let token = inflight().start(id);
    let result = match crate::engine::tunnel() {
        None => Err(ProbeError::new(
            "engine-stopped",
            "the engine is not running",
        )),
        Some(tunnel) => crate::get_runtime().block_on(async {
            tokio::select! {
                result = probe_through(&tunnel, proxy_name, kind, arg) => result,
                () = token.cancelled() => {
                    Err(ProbeError::new("cancelled", "cancelled"))
                }
            }
        }),
    };
    inflight().finish(id);
    answer(result)
}

fn answer(result: ProbeResult) -> String {
    match result {
        Ok(value) => value.to_string(),
        Err(e) => json!({ "error": e.message, "code": e.code }).to_string(),
    }
}

// ---------------------------------------------------------------------------
// Cancellation by id
// ---------------------------------------------------------------------------

/// Probes in flight by the UI's id. A cancel can reach `:vpn` before its
/// probe does (each binder call takes its own thread), so a cancel for an
/// unknown id leaves a cancelled token behind for the probe to find. Tokens
/// nobody claims are dropped once they are old.
#[derive(Default)]
struct Inflight {
    tokens: Mutex<HashMap<i64, (CancellationToken, Instant)>>,
}

const STALE_TOKEN: Duration = Duration::from_secs(300);

impl Inflight {
    fn start(&self, id: i64) -> CancellationToken {
        let mut tokens = self.tokens.lock();
        tokens.retain(|_, (_, since)| since.elapsed() < STALE_TOKEN);
        tokens
            .entry(id)
            .or_insert_with(|| (CancellationToken::new(), Instant::now()))
            .0
            .clone()
    }

    fn finish(&self, id: i64) {
        self.tokens.lock().remove(&id);
    }

    fn cancel(&self, id: i64) {
        self.tokens
            .lock()
            .entry(id)
            .or_insert_with(|| (CancellationToken::new(), Instant::now()))
            .0
            .cancel();
    }

    fn cancel_all(&self) {
        for (token, _) in self.tokens.lock().values() {
            token.cancel();
        }
    }
}

fn inflight() -> &'static Inflight {
    static INFLIGHT: OnceLock<Inflight> = OnceLock::new();
    INFLIGHT.get_or_init(Inflight::default)
}

/// Ends every running probe; the engine is going away.
pub(crate) fn cancel_all() {
    inflight().cancel_all();
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

/// Runs one probe and returns its JSON answer. Blocks the calling (binder)
/// thread until the probe ends; never panics into the JVM.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeProbeProxy(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    proxy: JString,
    kind: JString,
    arg: JString,
) -> jstring {
    let mut read =
        |s: &JString| -> String { env.get_string(s).map(Into::into).unwrap_or_default() };
    let (proxy, kind, arg) = (read(&proxy), read(&kind), read(&arg));
    let answer = std::panic::catch_unwind(|| run(id, &proxy, &kind, &arg)).unwrap_or_else(|_| {
        inflight().finish(id);
        answer(Err(ProbeError::failed("the probe crashed")))
    });
    env.new_string(answer)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeCancelProxyProbe(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    inflight().cancel(id);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_cancel_that_arrives_first_still_ends_the_probe() {
        let inflight = Inflight::default();
        inflight.cancel(7);
        let token = inflight.start(7);
        assert!(token.is_cancelled());
        inflight.finish(7);
        assert!(!inflight.start(7).is_cancelled());
    }

    #[test]
    fn cancel_all_reaches_every_running_probe() {
        let inflight = Inflight::default();
        let (a, b) = (inflight.start(1), inflight.start(2));
        inflight.cancel_all();
        assert!(a.is_cancelled() && b.is_cancelled());
    }

    /// A NAT probe with no proxy name is routed as UDP to its first STUN
    /// server: the TCP rule for that host is passed over for the UDP one.
    #[test]
    fn a_nameless_nat_probe_goes_where_the_rules_send_udp_to_the_stun_server() {
        let _serial = crate::test_support::SERIAL.lock();
        let (host, _) = stun::ROUTED_SERVER;
        let profile = format!(
            "rules:\n  - AND,((NETWORK,TCP),(DOMAIN,{host})),DIRECT\n  \
             - DOMAIN,{host},REJECT\n  - MATCH,DIRECT\n"
        );
        // `<dir>/meow`, as the app's `no_backup/meow`.
        let dir = std::env::temp_dir().join(format!("meow-probe-route-{}", std::process::id()));
        let home = dir.join("meow");
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(home.join("config.yaml"), profile).unwrap();
        *crate::HOME_DIR.lock() = Some(home.to_str().unwrap().to_string());
        let started = crate::start_engine(None, None, None, crate::engine::DnsUpstreams::Builtin);
        let error = crate::get_error();
        let routed = crate::engine::tunnel().map(|tunnel| {
            let rt = crate::get_runtime();
            let nat = rt
                .block_on(routed(&tunnel, "nat"))
                .map(|t| t.adapter.name().to_string());
            let other = rt.block_on(routed(&tunnel, "download")).map(|_| ());
            (nat, other)
        });
        crate::stop_engine();
        *crate::HOME_DIR.lock() = None;
        meow_common::logbook::uninstall();
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(started, 0, "{error}");

        let (nat, other) = routed.expect("an engine runs");
        assert_eq!(nat.unwrap(), "REJECT");
        assert_eq!(other.unwrap_err().code, "bad-request");
    }

    #[test]
    fn errors_carry_their_code() {
        let text = answer(Err(ProbeError::udp_unsupported()));
        let value: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["code"], "udp-unsupported");
        assert!(value["error"].as_str().is_some_and(|m| !m.is_empty()));
    }
}
