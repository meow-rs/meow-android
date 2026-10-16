//! Engine helpers — `tunnel()` accessor plus the config strip + DNS
//! plumbing, adapted from meow-ios `engine::pinned_dns_block` /
//! `strip_listener_fields`. The strip enforces architecturally that:
//!
//!   * No loopback listener bind: `port`, `socks-port`, `mixed-port`,
//!     `tproxy-port`, and `listeners:` are stripped. tun2socks dispatches
//!     every TCP flow in-process via `meow_tunnel::tcp::handle_tcp`.
//!   * No SNI / ALPN sniffing: redundant once DNS is fake-IP, because
//!     `pre_handle_metadata` reverses the fake-IP back to the qname the
//!     resolver originally returned.
//!   * DNS stays in-process fake-IP: whatever the profile's `dns:` block
//!     says, the resolver runs in fake-IP mode (`28.0.0.0/8`) with no listen
//!     socket. The profile still picks the upstreams (`nameserver`,
//!     `fallback`, `nameserver-policy`, `fake-ip-filter`, …); a profile
//!     without them, or whose upstreams cannot be set up, runs on the
//!     built-in CN-side pool.

use anyhow::{Context, Result};
use meow_config::{load_config, Config};
use meow_tunnel::Tunnel;
use std::path::{Path, PathBuf};

/// Returns a clone of the running engine's `Tunnel` handle, or `None` if the
/// engine isn't running. Mirrors meow-ios `engine::tunnel()`.
pub fn tunnel() -> Option<Tunnel> {
    crate::ENGINE.lock().as_ref().map(|s| s.tunnel.clone())
}

/// Built-in upstreams, used when the profile names none. CN-side resolvers
/// over TCP (UDP/53 is filtered on some networks), as IP literals so nothing
/// needs bootstrapping. CN-side only because meow's `query_pool` races every
/// entry in parallel ("first response wins"), and mixing a global anycast
/// resolver into the same pool lets it win the race from outside CN —
/// returning the global / SG / HK PoP for split-horizon hosts like
/// xiaohongshu.com.
const BUILTIN_NAMESERVERS: &[&str] = &["tcp://119.29.29.29", "tcp://223.5.5.5"];

/// Where the engine's DNS upstreams come from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsUpstreams {
    /// The profile's own `dns:` settings; the built-in pool fills in a
    /// missing `nameserver` / `default-nameserver`.
    Profile,
    /// The built-in pool only; the profile's `dns:` block is dropped.
    Builtin,
}

/// The `dns:` block the engine runs with. `profile_dns` is the profile's
/// own block, if it has one.
///
/// The plumbing is fixed whoever picks the upstreams: the tun2socks UDP/53
/// intercept hands every in-TUN A/AAAA query straight to
/// `meow_dns::DnsServer::handle_query`, so this resolver — fake-IP mode on
/// the FFI's CIDR, no listen socket — is the single source of truth for
/// synthesis, reverse mapping, hosts and NXDOMAIN.
fn engine_dns_block(
    profile_dns: Option<serde_yaml::Value>,
    upstreams: DnsUpstreams,
) -> serde_yaml::Mapping {
    let mut dns = match (upstreams, profile_dns) {
        (DnsUpstreams::Profile, Some(serde_yaml::Value::Mapping(dns))) => dns,
        _ => serde_yaml::Mapping::new(),
    };
    for (key, value) in [
        ("enable", serde_yaml::Value::from(true)),
        ("listen", "".into()),
        ("enhanced-mode", "fake-ip".into()),
        ("fake-ip-range", "28.0.0.0/8".into()),
    ] {
        dns.insert(key.into(), value);
    }
    let builtin =
        || serde_yaml::Value::Sequence(BUILTIN_NAMESERVERS.iter().map(|&ns| ns.into()).collect());
    // `default-nameserver` bootstraps hostname upstreams
    // (`tls://dns.alidns.com`). Without one, meow falls back to
    // /etc/resolv.conf, which Android does not have, and then to 1.1.1.1 /
    // 8.8.8.8 over UDP — unreliable from CN. The built-in pool is all IP
    // literals and never bootstraps; filling it in either way keeps a
    // profile without a `dns:` block identical under both `DnsUpstreams`.
    for key in ["nameserver", "default-nameserver"] {
        if !lists_anything(dns.get(key)) {
            dns.insert(key.into(), builtin());
        }
    }
    dns
}

/// Whether a nameserver-list key is set to something. A value that is not
/// a list is left for the config parser to reject.
fn lists_anything(value: Option<&serde_yaml::Value>) -> bool {
    match value {
        None | Some(serde_yaml::Value::Null) => false,
        Some(serde_yaml::Value::Sequence(list)) => !list.is_empty(),
        Some(_) => true,
    }
}

/// Strip the shorthand listener ports + explicit `listeners:` array +
/// `sniffer:` block from a raw config YAML and replace its `dns:` block with
/// [`engine_dns_block`]. Operates on a generic `serde_yaml::Value` so
/// unmodelled top-level keys (`tun:`, `experimental:`, `profile:`, etc.)
/// round-trip unchanged.
pub fn strip_and_inject(yaml: &str, upstreams: DnsUpstreams) -> Result<String> {
    let mut doc: serde_yaml::Value = serde_yaml::from_str(yaml).context("parsing config YAML")?;
    if let serde_yaml::Value::Mapping(m) = &mut doc {
        let profile_dns = m.remove("dns");
        for key in [
            "port",
            "socks-port",
            "mixed-port",
            "tproxy-port",
            "listeners",
            "sniffer",
            // The app pins the API server to 127.0.0.1:9090 via
            // nativeStartEngine, so the user's value is never used — and
            // mihomo-style shorthand like `external-controller: :9090`
            // fails meow-config's strict address parse since v0.20.
            "external-controller",
            "external-controller-tls",
            "secret",
        ] {
            m.remove(serde_yaml::Value::String(key.to_string()));
        }
        m.insert(
            serde_yaml::Value::String("dns".into()),
            serde_yaml::Value::Mapping(engine_dns_block(profile_dns, upstreams)),
        );
    }
    serde_yaml::to_string(&doc).context("serializing stripped config YAML")
}

/// Strip `yaml` and hand it to `load`: first with the profile's DNS
/// upstreams, then — when that fails and the profile's `dns:` block made a
/// difference — with the built-in pool. Upstreams are set up while the
/// config loads, so a DoT hostname the bootstrap servers cannot resolve
/// would otherwise keep the engine from starting at all. When both loads
/// fail, the first error is returned: it is the one the profile caused.
pub async fn load_with_dns_fallback<T, F, Fut>(yaml: &str, load: F) -> Result<T>
where
    F: Fn(String) -> Fut,
    Fut: std::future::Future<Output = Result<T>>,
{
    let profile = strip_and_inject(yaml, DnsUpstreams::Profile)?;
    let builtin = strip_and_inject(yaml, DnsUpstreams::Builtin)?;
    if profile == builtin {
        return load(profile).await;
    }
    match load(profile).await {
        Ok(config) => Ok(config),
        Err(e) => {
            tracing::warn!("config: the profile's DNS failed ({e:#}); using the built-in DNS");
            load(builtin).await.map_err(|_| e)
        }
    }
}

/// RAII handle that removes a file on drop. Used so the sibling
/// `config.yaml.android-stripped.yaml` we hand to `load_config` never
/// survives past the load call — including on `?` early-returns, panics,
/// and profile-swap failures.
struct TempFileGuard(PathBuf);

impl Drop for TempFileGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

/// Read `config_path`, strip it (see [`load_with_dns_fallback`]), and hand
/// a sibling `config.yaml.android-stripped.yaml` to `load_config`. The
/// sibling placement is deliberate: `load_config` uses `path.parent()` as
/// the rule-/proxy-provider `cache_dir`, so colocating with the original
/// keeps rule-provider cache files in the home dir. Using
/// `load_config_from_str` would silently disable that caching.
pub async fn load_stripped_config(config_path: &str) -> Result<Config> {
    let original = std::fs::read_to_string(config_path)
        .with_context(|| format!("reading config from {config_path}"))?;
    let stripped_path = sibling_stripped_path(config_path);
    load_with_dns_fallback(&original, |stripped| {
        let stripped_path = stripped_path.clone();
        async move {
            std::fs::write(&stripped_path, stripped).with_context(|| {
                format!("writing stripped config to {}", stripped_path.display())
            })?;
            let _guard = TempFileGuard(stripped_path.clone());
            load_config(stripped_path.to_str().expect("utf-8 path")).await
        }
    })
    .await
}

fn sibling_stripped_path(config_path: &str) -> PathBuf {
    Path::new(config_path).with_extension("android-stripped.yaml")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn dns_of(stripped: &str) -> serde_yaml::Mapping {
        let doc: serde_yaml::Value = serde_yaml::from_str(stripped).unwrap();
        doc["dns"].as_mapping().cloned().expect("dns block")
    }

    fn list(items: &[&str]) -> serde_yaml::Value {
        serde_yaml::Value::Sequence(items.iter().map(|&s| s.into()).collect())
    }

    const PROFILE: &str = "\
mixed-port: 7890
external-controller: :9090
mode: rule
dns:
  enable: false
  listen: 0.0.0.0:53
  enhanced-mode: redir-host
  fake-ip-range: 198.18.0.1/16
  default-nameserver: [223.5.5.5]
  nameserver: [tls://dns.alidns.com]
  fallback: [tls://8.8.8.8]
  fake-ip-filter: ['*.local']
rules:
  - MATCH,DIRECT
";

    #[test]
    fn profile_keeps_its_upstreams_on_pinned_plumbing() {
        let stripped = strip_and_inject(PROFILE, DnsUpstreams::Profile).unwrap();
        let dns = dns_of(&stripped);
        assert_eq!(dns["enable"], serde_yaml::Value::from(true));
        assert_eq!(dns["listen"], serde_yaml::Value::from(""));
        assert_eq!(dns["enhanced-mode"], serde_yaml::Value::from("fake-ip"));
        assert_eq!(dns["fake-ip-range"], serde_yaml::Value::from("28.0.0.0/8"));
        assert_eq!(dns["nameserver"], list(&["tls://dns.alidns.com"]));
        assert_eq!(dns["default-nameserver"], list(&["223.5.5.5"]));
        assert_eq!(dns["fallback"], list(&["tls://8.8.8.8"]));
        assert_eq!(dns["fake-ip-filter"], list(&["*.local"]));

        let doc: serde_yaml::Value = serde_yaml::from_str(&stripped).unwrap();
        assert!(doc.get("mixed-port").is_none());
        assert!(doc.get("external-controller").is_none());
        assert_eq!(doc["mode"], serde_yaml::Value::from("rule"));
    }

    #[test]
    fn profile_without_upstreams_runs_on_the_builtin_pool() {
        for yaml in [
            "dns:\n  nameserver: []\n  fake-ip-filter: ['*.lan']\n",
            "dns:\n  nameserver:\n",
            "mode: rule\n",
            "dns: off\n",
        ] {
            let dns = dns_of(&strip_and_inject(yaml, DnsUpstreams::Profile).unwrap());
            assert_eq!(dns["nameserver"], list(BUILTIN_NAMESERVERS), "{yaml}");
            assert_eq!(
                dns["default-nameserver"],
                list(BUILTIN_NAMESERVERS),
                "{yaml}"
            );
            assert_eq!(
                dns["enhanced-mode"],
                serde_yaml::Value::from("fake-ip"),
                "{yaml}"
            );
        }
        let dns = dns_of(
            &strip_and_inject(
                "dns:\n  nameserver: []\n  fake-ip-filter: ['*.lan']\n",
                DnsUpstreams::Profile,
            )
            .unwrap(),
        );
        assert_eq!(dns["fake-ip-filter"], list(&["*.lan"]));
    }

    #[test]
    fn builtin_drops_the_profile_dns_block() {
        let dns = dns_of(&strip_and_inject(PROFILE, DnsUpstreams::Builtin).unwrap());
        let keys: Vec<_> = dns.keys().filter_map(serde_yaml::Value::as_str).collect();
        assert_eq!(
            keys,
            [
                "enable",
                "listen",
                "enhanced-mode",
                "fake-ip-range",
                "nameserver",
                "default-nameserver"
            ]
        );
        assert_eq!(dns["nameserver"], list(BUILTIN_NAMESERVERS));
        assert_eq!(dns["default-nameserver"], list(BUILTIN_NAMESERVERS));
    }

    #[tokio::test]
    async fn a_profile_whose_dns_loads_runs_on_it() {
        let calls = AtomicUsize::new(0);
        let loaded = load_with_dns_fallback(PROFILE, |stripped| {
            calls.fetch_add(1, Ordering::SeqCst);
            async move { Ok(stripped) }
        })
        .await
        .unwrap();
        assert_eq!(
            dns_of(&loaded)["nameserver"],
            list(&["tls://dns.alidns.com"])
        );
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn profile_dns_that_cannot_be_set_up_falls_back_to_builtin() {
        let calls = AtomicUsize::new(0);
        let loaded = load_with_dns_fallback(PROFILE, |stripped| {
            calls.fetch_add(1, Ordering::SeqCst);
            async move {
                if stripped.contains("dns.alidns.com") {
                    anyhow::bail!("cannot resolve 'dns.alidns.com' via bootstrap nameserver");
                }
                Ok(stripped)
            }
        })
        .await
        .unwrap();
        let dns = dns_of(&loaded);
        assert_eq!(dns["nameserver"], list(BUILTIN_NAMESERVERS));
        assert!(dns.get("fake-ip-filter").is_none());
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn a_profile_that_fails_either_way_reports_its_own_error() {
        let calls = AtomicUsize::new(0);
        let loaded: Result<()> = load_with_dns_fallback(PROFILE, |s| {
            calls.fetch_add(1, Ordering::SeqCst);
            async move {
                if s.contains("dns.alidns.com") {
                    anyhow::bail!("profile error");
                }
                anyhow::bail!("builtin error")
            }
        })
        .await;
        assert_eq!(loaded.unwrap_err().to_string(), "profile error");
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn a_profile_without_dns_loads_once() {
        let calls = AtomicUsize::new(0);
        let loaded: Result<()> = load_with_dns_fallback("rules: [MATCH,DIRECT]\n", |_| {
            calls.fetch_add(1, Ordering::SeqCst);
            async { anyhow::bail!("bad proxies") }
        })
        .await;
        assert_eq!(loaded.unwrap_err().to_string(), "bad proxies");
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }
}
