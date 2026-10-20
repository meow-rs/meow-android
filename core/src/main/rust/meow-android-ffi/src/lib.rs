//! Rust half of the meow-android native stack — JNI surface for the Kotlin
//! VPN service.
//!
//! Embeds the meow-rs proxy engine (pinned to a release tag) and the
//! tun2socks layer in one cdylib. Outbound socket protection is wired
//! through upstream's `meow_common::SocketProtector` hook — see
//! `protect.rs` — so no proxy-side patches are needed. Every netstack TCP flow is dispatched
//! in-process via `meow_tunnel::tcp::handle_tcp` — no SOCKS5 loopback
//! hop. DNS is delegated to meow's resolver, pinned to fake-IP mode
//! (28.0.0.0/8) by `engine::strip_and_inject` but on the profile's own
//! upstreams (the built-in CN-side pool when it names none, or when Settings'
//! "Built-in DNS" switch is on; `live_dns` applies a flip without a restart);
//! the tun2socks UDP/53 intercept hands every in-TUN DNS datagram straight to
//! `meow_dns::DnsServer::handle_query`.

mod diagnostics;
mod engine;
mod ip_info;
mod live_dns;
mod logbook;
mod logging;
mod outbound;
mod owner;
mod probe;
mod protect;
#[cfg(test)]
mod test_support;
mod tun2socks;

use dashmap::DashMap;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use meow_api::log_stream::{LogBroadcastLayer, LogMessage};
use meow_api::ApiServer;
use meow_common::TunnelMode;
use meow_config::proxy_provider::ProxyProvider;
use meow_tunnel::Tunnel;
use parking_lot::{Mutex, RwLock};
use std::sync::{Arc, Once, OnceLock};
use tokio::sync::broadcast;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::prelude::*;

// ---------------------------------------------------------------------------
// Global allocator
//
// mimalloc instead of the platform malloc (scudo on Android API 30+).
// Empirically returns freed pages to the OS more aggressively under the
// allocation patterns meow + tun2socks generate (many short-lived
// per-flow allocations + the geoip mmdb scan), keeping VPN-service RSS
// closer to working set.
// ---------------------------------------------------------------------------

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

pub(crate) fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        // Worker count left at the tokio default (one per CPU). Blocking
        // pool capped at 2 so background work (file I/O, redb writes, geoip
        // mmdb scans) can't explode RSS via tokio's default 512-thread cap.
        // Per-thread stack stays at the tokio default (2 MB): capping it at
        // 512 KB overflowed on the latency-probe call chain (url_test →
        // adapter dial → DNS resolver → tracing → android_logger → logcat)
        // and SIGSEGV'd the :vpn process. Stacks are demand-paged, so the
        // real RSS cost is only the pages actually touched.
        tokio::runtime::Builder::new_multi_thread()
            .max_blocking_threads(2)
            .enable_all()
            .build()
            .expect("Failed to create tokio runtime")
    })
}

pub(crate) struct EngineState {
    pub(crate) tunnel: Tunnel,
    /// The API server's secret (see `engine::api_secret`); empty for none.
    api_secret: String,
    /// Whether the VPN routes IPv6 into the TUN (see `engine::tun_ipv6`).
    tun_ipv6: bool,
    /// What Settings' "Built-in DNS" switch rebuilds the resolver from.
    pub(crate) live_dns: Arc<live_dns::LiveDns>,
    /// The API's providers: a network switch resets the sessions of their
    /// proxies too, as no group may reach them (`outbound`).
    pub(crate) proxy_providers: Arc<DashMap<String, Arc<ProxyProvider>>>,
    _handles: Vec<tokio::task::JoinHandle<()>>,
}

pub(crate) static ENGINE: Mutex<Option<EngineState>> = Mutex::new(None);
pub(crate) static HOME_DIR: Mutex<Option<String>> = Mutex::new(None);
// Overwritten on every engine start (and cleared on stop) so tun2socks' DNS
// intercept always queries the current session's resolver, not the first one.
pub(crate) static DNS_RESOLVER: RwLock<Option<Arc<meow_dns::Resolver>>> = RwLock::new(None);

// ---------------------------------------------------------------------------
// Thread-local error message
// ---------------------------------------------------------------------------

thread_local! {
    static LAST_ERROR: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
}

fn set_error(msg: String) {
    LAST_ERROR.with(|e| *e.borrow_mut() = msg);
}

fn get_error() -> String {
    LAST_ERROR.with(|e| e.borrow().clone())
}

// ---------------------------------------------------------------------------
// Minimal config
// ---------------------------------------------------------------------------

const MINIMAL_CONFIG: &str = "\
mode: rule\n\
log-level: info\n\
allow-lan: false\n\
proxies: []\n\
proxy-groups: []\n\
rules:\n\
  - MATCH,DIRECT\n\
";

// ---------------------------------------------------------------------------
// Process-wide tracing subscriber + log broadcast channel
//
// Mirrors meow-ios `engine::log_broadcast_tx` / `install_tracing_subscriber`.
// `set_global_default` can only be installed once per process; subsequent
// engine restarts reuse the same broadcast::Sender that was registered the
// first time.
// ---------------------------------------------------------------------------

fn log_broadcast_tx() -> &'static broadcast::Sender<LogMessage> {
    static TX: OnceLock<broadcast::Sender<LogMessage>> = OnceLock::new();
    TX.get_or_init(|| {
        let (tx, _rx) = broadcast::channel(128);
        tx
    })
}

fn install_tracing_subscriber() {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let log_layer = LogBroadcastLayer {
            tx: log_broadcast_tx().clone(),
        }
        .with_filter(LevelFilter::INFO);
        let _ = tracing_subscriber::registry().with(log_layer).try_init();
    });
    spawn_log_buffer_drainer();
}

// ---------------------------------------------------------------------------
// In-memory log ring buffer for the Kotlin `getLogs` MethodChannel poll
//
// The logs screen polls every couple of seconds and appends whatever it gets,
// so reads have drain semantics: a background task accumulates formatted lines
// from the same broadcast channel the API `/logs` endpoint uses, and
// `nativeGetLogs` returns + clears the pending lines. The buffer is capped so
// it stays bounded while the screen is closed (oldest lines dropped first).
// ---------------------------------------------------------------------------

const LOG_BUFFER_CAP: usize = 2000;

fn log_buffer() -> &'static Mutex<std::collections::VecDeque<String>> {
    static BUF: OnceLock<Mutex<std::collections::VecDeque<String>>> = OnceLock::new();
    BUF.get_or_init(|| Mutex::new(std::collections::VecDeque::new()))
}

fn spawn_log_buffer_drainer() {
    static SPAWNED: Once = Once::new();
    SPAWNED.call_once(|| {
        let mut rx = log_broadcast_tx().subscribe();
        get_runtime().spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(msg) => {
                        let line = format!("{} {}", msg.level.as_str().to_uppercase(), msg.payload);
                        let mut buf = log_buffer().lock();
                        buf.push_back(line);
                        while buf.len() > LOG_BUFFER_CAP {
                            buf.pop_front();
                        }
                    }
                    // Reader fell behind the 128-slot channel; skip the gap.
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        });
    });
}

fn drain_log_buffer() -> Vec<String> {
    log_buffer().lock().drain(..).collect()
}

// ---------------------------------------------------------------------------
// Engine lifecycle
// ---------------------------------------------------------------------------

fn start_engine(
    external_controller: Option<String>,
    secret: Option<String>,
    mode: Option<TunnelMode>,
    dns: engine::DnsUpstreams,
) -> i32 {
    logging::bridge_log("start_engine: acquiring ENGINE lock");
    let mut engine = ENGINE.lock();
    if engine.is_some() {
        set_error("proxy is already running".to_string());
        return -1;
    }

    let rt = get_runtime();
    match rt.block_on(async { start_engine_async(external_controller, secret, mode, dns).await }) {
        Ok(state) => {
            logging::bridge_log("start_engine: engine started successfully");
            *engine = Some(state);
            0
        }
        Err(e) => {
            logging::bridge_log(&format!("start_engine: ERROR: {}", e));
            meow_common::logbook::engine_stopped(meow_common::logbook::Stop::Failed(&format!(
                "{e:#}"
            )));
            set_error(format!("start proxy: {}", e));
            -1
        }
    }
}

async fn start_engine_async(
    external_controller: Option<String>,
    secret: Option<String>,
    mode: Option<TunnelMode>,
    dns: engine::DnsUpstreams,
) -> Result<EngineState, anyhow::Error> {
    install_tracing_subscriber();

    // Resolve config path + set XDG_CONFIG_HOME (meow-config looks for
    // $XDG_CONFIG_HOME/meow/Country.mmdb). Our dir is .../no_backup/meow,
    // so XDG_CONFIG_HOME is the parent.
    let config_path = if let Some(dir) = HOME_DIR.lock().as_ref() {
        if let Some(parent) = std::path::Path::new(dir).parent() {
            std::env::set_var("XDG_CONFIG_HOME", parent);
            logging::bridge_log(&format!(
                "start_engine_async: set XDG_CONFIG_HOME={}",
                parent.display()
            ));
        }
        Some(format!("{}/config.yaml", dir))
    } else {
        None
    };

    // Geodata DBs are bundled in the APK and seeded into the engine home
    // dir by MeowInstance.copyGeoxAssets() before nativeStartEngine
    // fires. The on-disk files at `$XDG_CONFIG_HOME/meow/Country.mmdb` and
    // `…/GeoLite2-ASN.mmdb` are guaranteed to exist by the time we reach
    // load_config, so no pre-VPN network fetch is needed here. See
    // `core/build.gradle.kts` (downloadGeoxAssets) for the bundling path.

    // Load via engine::load_stripped_config: strips listener/sniffer blocks
    // and pins the profile's DNS to in-process fake-IP, on the upstreams
    // `dns` picks. Falls back to the minimal config (also passed through
    // strip_and_inject) when no home dir is set or the file is unreadable.
    let (loaded, loaded_from) = match config_path.as_deref() {
        Some(path) if std::path::Path::new(path).exists() => {
            logging::bridge_log(&format!(
                "start_engine_async: loading config from {path}, DNS upstreams: {dns:?}"
            ));
            // The logbook opens under the engine home (`XDG_CONFIG_HOME`,
            // set above) before the load, so the load's own events — a
            // provider that fails to fetch — are recorded.
            let profile = std::fs::read_to_string(path).unwrap_or_default();
            meow_common::logbook::apply(engine::logbook_enabled(&profile));
            let stripped_path = engine::sibling_stripped_path(path);
            (
                engine::load_stripped_config(path, dns).await?,
                stripped_path.to_str().map(str::to_string),
            )
        }
        _ => {
            logging::bridge_log("start_engine_async: using minimal config");
            meow_common::logbook::apply(true);
            let stripped = engine::strip_and_inject(MINIMAL_CONFIG, engine::DnsUpstreams::Builtin)?;
            let loaded = engine::Loaded {
                config: meow_config::load_config_from_str(&stripped).await?,
                profile_dns: None,
                upstreams: engine::DnsUpstreams::Builtin,
            };
            (loaded, None)
        }
    };
    // What the logbook's start entry names.
    let config_source = match (&loaded_from, config_path) {
        (Some(_), Some(path)) => path,
        _ => "built-in config".to_owned(),
    };
    let engine::Loaded {
        mut config,
        profile_dns,
        upstreams,
    } = loaded;
    logging::bridge_log(&format!(
        "start_engine_async: config loaded, proxies={}, rules={}",
        config.proxies.len(),
        config.rules.len()
    ));

    if let Some(addr) = external_controller {
        config.api.external_controller = addr.parse().ok();
    }
    config.api.secret = engine::api_secret(config.api.secret.take(), secret);
    let api_secret = config.api.secret.clone().unwrap_or_default();
    let tun_ipv6 = engine::tun_ipv6(config.raw.ipv6);
    // The app's route-mode picker outlives a session, so its pick replaces
    // the profile's `mode:`. `raw` is what `GET /configs` echoes back.
    if let Some(mode) = mode {
        logging::bridge_log(&format!("start_engine_async: route mode override {mode}"));
        config.general.mode = mode;
        config.raw.mode = Some(mode.to_string());
    }

    // Install the global host-resolver hook so `meow_common::connect_tcp_host`
    // (used by every proxy adapter that dials by hostname — Trojan, VLESS,
    // SS, SOCKS5, HTTP, …) routes the lookup through meow-rs's own
    // `Resolver` instead of libc's `getaddrinfo`. Critical on Android: the
    // VPN's DNS server is `172.19.0.2` (our TUN), so `getaddrinfo` would
    // loop the query back through the engine's fake-IP pool and the
    // protected outbound socket would then try to dial a non-routable
    // `28.0.0.0/8` address. `proxy_resolver` is the config's
    // `proxy-server-nameserver` pool, used for proxy-server hostnames when set.
    // Unconditional, as meow-app does on the VPN platforms.
    meow_common::set_host_resolver(Arc::new(
        meow_dns::ResolverHostHook::new_with_proxy_resolver(
            Arc::clone(&config.dns.resolver),
            config.dns.proxy_resolver.clone(),
        ),
    ));

    *DNS_RESOLVER.write() = Some(config.dns.resolver.clone());

    // Same startup order as meow-app's `run`: share the config's resolver
    // slot, install the provider dialer registry before the first
    // `update_routing` (provider nodes resolve `dialer-proxy` against it),
    // then publish the routing table in one step.
    let raw_config = Arc::new(RwLock::new(config.raw.clone()));
    let tunnel = Tunnel::new_with_slot(Arc::clone(&config.dns.resolver_slot));
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    tunnel.set_mode(config.general.mode);
    tunnel.set_connection_notes(config.general.connection_notes);
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    // The UDP NAT sweeper: evicts sessions idle in both directions (the
    // tun2socks reply readers touch the same clock on every reply).
    tunnel.spawn_background_tasks();
    // Periodic probes for url-test / fallback groups. The tasks hold a weak
    // tunnel handle, so they end with the engine.
    tunnel.reconcile_health_checks(&meow_config::extract_health_check_specs(
        config.raw.proxy_groups.as_deref().unwrap_or(&[]),
    ));

    let mut handles: Vec<tokio::task::JoinHandle<()>> = Vec::new();

    let proxy_providers: Arc<DashMap<_, _>> =
        Arc::new(config.proxy_providers.into_iter().collect());
    let rule_providers = Arc::new(RwLock::new(config.rule_providers));
    // No DNS listener: tun2socks answers in-TUN queries in-process.
    let dns_server = Arc::new(RwLock::new(None));
    let live_dns = Arc::new(live_dns::LiveDns::new(
        profile_dns,
        loaded_from,
        upstreams,
        Arc::clone(&raw_config),
        Arc::clone(&rule_providers),
        Arc::clone(&dns_server),
    ));

    // `interval:` refresh loops for rule and proxy providers.
    let rule_provider_refresh =
        Arc::new(meow_config::rule_provider_refresh::RefreshSupervisor::default());
    rule_provider_refresh.reconcile(&rule_providers);
    // The providers the load left out, recorded once now that it is the
    // running config: a DNS fallback loads the profile twice.
    config.dropped_providers.record();
    let proxy_provider_refresh =
        Arc::new(meow_config::proxy_provider_refresh::ProxyProviderRefreshSupervisor::default());
    proxy_provider_refresh.reconcile(&proxy_providers, config.raw.proxy_providers.as_ref());

    // Providers the load left owing a remote fetch (served from their cache,
    // cut off by the deadline, failed, or with a `proxy:` that could not
    // resolve before `update_routing`) fetch now, retrying with backoff.
    meow_config::proxy_provider::spawn_deferred_initial_fetches(&proxy_providers);

    let listeners_for_api = config.listeners.named.clone();
    let log_tx = log_broadcast_tx().clone();

    if let Some(api_addr) = config.api.external_controller {
        let api_server = ApiServer::new(
            tunnel.clone(),
            api_addr,
            config.api.secret.clone(),
            // No config path: the app restarts the engine to apply a config,
            // and `PUT /configs` must not persist into the stripped copy.
            None,
            raw_config,
            log_tx,
            Arc::clone(&proxy_providers),
            rule_providers,
            rule_provider_refresh,
            proxy_provider_refresh,
            listeners_for_api,
            // No external web UI dashboard — the app talks to the API
            // directly (added in meow-rs v0.15.1, #223).
            None,
            dns_server,
            config.provider_dialer_registry.clone(),
        );
        handles.push(tokio::spawn(async move {
            if let Err(e) = api_server.run().await {
                tracing::error!("API server error: {}", e);
            }
        }));
    }

    // No SOCKS5 / HTTP loopback listener — tun2socks dispatches every flow
    // through `meow_tunnel::tcp::handle_tcp` in-process (same path as
    // meow-ios). The `listeners.*` block in user configs is intentionally
    // ignored on Android.

    logging::bridge_log(&format!(
        "start_engine_async: all tasks spawned, handles={}",
        handles.len()
    ));
    // Last: a start that fails from here on is recorded as a failed stop.
    meow_common::logbook::engine_started(&config_source);
    Ok(EngineState {
        tunnel,
        api_secret,
        tun_ipv6,
        live_dns,
        proxy_providers,
        _handles: handles,
    })
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    logging::init_android_logger();
    logging::bridge_log("nativeInit: android logger initialized");
    // Process-wide engine policy, set before any config load (engine start
    // in `:vpn`, validation in the UI process):
    //  * tun2socks names each flow's owner (owner.rs), so PROCESS-NAME / UID
    //    rules stay live; their liveness is fixed when rules are built.
    //  * A provider feed must not hold the VPN start: the tunnel only comes
    //    up once the config has loaded (and with "Block connections without
    //    VPN" no fetch can succeed before then). Uncached providers get this
    //    long; stragglers land in the background, failures retry there.
    meow_common::process_lookup::enable_inbound_process_info();
    meow_config::set_provider_fetch_deadline(Some(PROVIDER_FETCH_DEADLINE));
}

/// How long a config load waits for provider downloads with no cache.
const PROVIDER_FETCH_DEADLINE: std::time::Duration = std::time::Duration::from_secs(3);

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeSetHomeDir(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) {
    let dir_str: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    logging::bridge_log(&format!("nativeSetHomeDir: {}", dir_str));
    if dir_str.is_empty() {
        *HOME_DIR.lock() = None;
    } else {
        // meow-config resolves geodata at `$XDG_CONFIG_HOME/meow/Country.mmdb`.
        // Our home dir is `.../no_backup/meow`, so XDG_CONFIG_HOME is its
        // parent. Set it here (not only in start_engine) so config validation
        // — used by the YAML editor and config import before any VPN start —
        // can load the bundled GeoIP DB instead of failing on the default
        // relative `./meow/Country.mmdb` path.
        if let Some(parent) = std::path::Path::new(&dir_str).parent() {
            std::env::set_var("XDG_CONFIG_HOME", parent);
        }
        *HOME_DIR.lock() = Some(dir_str);
    }
}

/// Drains and returns the buffered engine log lines as a single newline-joined
/// string (empty if none pending). Kotlin splits it back into a list for the
/// logs screen. Safe to call whether or not the engine is running.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeGetLogs(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let joined = drain_log_buffer().join("\n");
    env.new_string(joined)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeStartEngine(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
    secret: JString,
    mode: JString,
    builtin_dns: jboolean,
) -> jint {
    let addr_str: String = env.get_string(&addr).map(|s| s.into()).unwrap_or_default();
    let secret_str: String = env
        .get_string(&secret)
        .map(|s| s.into())
        .unwrap_or_default();
    let mode_str: String = env.get_string(&mode).map(|s| s.into()).unwrap_or_default();
    start_engine(
        Some(addr_str),
        Some(secret_str),
        parse_mode_override(&mode_str),
        dns_upstreams(builtin_dns),
    )
}

/// Settings' "Built-in DNS" switch: on ignores the profile's upstreams.
fn dns_upstreams(builtin_dns: jboolean) -> engine::DnsUpstreams {
    if builtin_dns == JNI_FALSE {
        engine::DnsUpstreams::Profile
    } else {
        engine::DnsUpstreams::Builtin
    }
}

/// Applies Settings' "Built-in DNS" switch to the running engine, keeping
/// the VPN up (see `live_dns`). Returns 0 when it is applied or nothing is
/// running, -1 with `nativeGetLastError` set when the engine keeps its DNS.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeSetBuiltinDns(
    _env: JNIEnv,
    _class: JClass,
    builtin_dns: jboolean,
) -> jint {
    match live_dns::switch(dns_upstreams(builtin_dns)) {
        Ok(()) => 0,
        Err(e) => {
            logging::bridge_log(&format!("nativeSetBuiltinDns: ERROR: {e:#}"));
            set_error(format!("{e:#}"));
            -1
        }
    }
}

/// Empty means "keep the config's `mode:`". An unknown value is logged and
/// ignored rather than failing the start: a stale pick must not brick the VPN.
fn parse_mode_override(raw: &str) -> Option<TunnelMode> {
    if raw.is_empty() {
        return None;
    }
    match raw.parse() {
        Ok(mode) => Some(mode),
        Err(e) => {
            logging::bridge_log(&format!("nativeStartEngine: ignoring route mode: {e}"));
            None
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeStopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_engine();
}

fn stop_engine() {
    // A probe holds a route snapshot, so it would keep dialing through the
    // stopped engine's proxies until its own budget ran out.
    probe::cancel_all();
    tun2socks::stop();
    protect::clear();
    owner::clear();
    let mut engine = ENGINE.lock();
    // Under the lock: a start or a DNS switch (`live_dns`) still running
    // would otherwise install its resolver after this.
    *DNS_RESOLVER.write() = None;
    if let Some(state) = engine.take() {
        // Ends the tun2socks UDP reply readers now rather than at their next
        // idle check.
        state.tunnel.close_all_udp_sessions();
        for handle in state._handles {
            handle.abort();
        }
        meow_common::logbook::engine_stopped(meow_common::logbook::Stop::Requested("app"));
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeStartTun2Socks(
    mut env: JNIEnv,
    _class: JClass,
    vpn_service: JObject,
    fd: jint,
    dns_port: jint,
) -> jint {
    logging::bridge_log(&format!(
        "nativeStartTun2Socks: fd={}, dns={}",
        fd, dns_port
    ));

    if fd < 0 {
        set_error("invalid file descriptor".to_string());
        return -1;
    }

    // Install the global SocketProtector — every outbound TCP/UDP fd
    // meow-rs opens (proxy adapters + the DNS resolver's default
    // SocketFactory) will fire VpnService.protect() before connect/bind.
    protect::install(&env, &vpn_service);
    // Lets tun2socks tag each connection with the app that opened it.
    owner::install(&mut env, &vpn_service);

    match tun2socks::start(fd, dns_port as u16) {
        Ok(()) => {
            logging::bridge_log("nativeStartTun2Socks: started successfully");
            0
        }
        Err(e) => {
            logging::bridge_log(&format!("nativeStartTun2Socks: ERROR: {}", e));
            set_error(e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if ENGINE.lock().is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeGetUploadTraffic(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let engine = ENGINE.lock();
    match engine.as_ref() {
        Some(state) => state.tunnel.statistics().snapshot().0,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeGetDownloadTraffic(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let engine = ENGINE.lock();
    match engine.as_ref() {
        Some(state) => state.tunnel.statistics().snapshot().1,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeValidateConfig(
    mut env: JNIEnv,
    _class: JClass,
    yaml: JString,
) -> jint {
    let yaml_str: String = env.get_string(&yaml).map(|s| s.into()).unwrap_or_default();
    // Validate what the engine will actually load: the started engine runs
    // the config through strip_and_inject (drops listener ports, sniffer,
    // external-controller, pins DNS to fake-IP, …) and falls back to the
    // built-in DNS when the profile's cannot be set up, so validating the
    // raw YAML rejects configs that would run fine — e.g. mihomo's
    // `external-controller: :9090` shorthand, stripped at start but fatal
    // to a raw parse.
    // Boxed for the same reason as in `engine::load_stripped_config`.
    let load = crate::engine::load_with_dns_fallback(&yaml_str, |stripped| {
        Box::pin(async move { meow_config::load_config_from_str(&stripped).await })
    });
    match get_runtime().block_on(load) {
        Ok(_) => 0,
        Err(e) => {
            set_error(format!("validate config: {}", e));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeGetLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let msg = get_error();
    env.new_string(&msg)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

/// The running engine's API secret, for `MeowInstance` to hand to the UI
/// process; empty when the engine is stopped or runs without one.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeApiSecret(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let secret = ENGINE
        .lock()
        .as_ref()
        .map(|s| s.api_secret.clone())
        .unwrap_or_default();
    env.new_string(&secret)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Whether `VpnService` routes IPv6 into the TUN for the running engine's
/// profile (see `engine::tun_ipv6`); false when the engine is stopped.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeTunIpv6(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if ENGINE.lock().as_ref().is_some_and(|s| s.tun_ipv6) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// What Settings shows as the engine version; `build.rs` reads the release
/// from Cargo.lock.
const ENGINE_VERSION: &str = concat!("meow-rs v", env!("MEOW_RS_VERSION"));

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(ENGINE_VERSION)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

#[cfg(test)]
mod tests {
    use super::ENGINE_VERSION;

    #[test]
    fn the_engine_version_is_the_pinned_release() {
        let tag = ENGINE_VERSION.strip_prefix("meow-rs ").unwrap();
        assert!(
            include_str!("../Cargo.toml").contains(&format!("tag = \"{tag}\"")),
            "{ENGINE_VERSION} is not the tag Cargo.toml pins",
        );
    }
}
