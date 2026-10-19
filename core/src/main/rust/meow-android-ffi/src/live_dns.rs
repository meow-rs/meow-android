//! Settings' "Built-in DNS" switch, applied to the running engine without a
//! restart.
//!
//! Only the resolver is rebuilt, the way meow-api's own config commits
//! rebuild it (`reconcile_dns_config`, then `install_resolver_everywhere`)
//! and inside the same `CONFIG_MUTATION` lane, so a switch cannot interleave
//! with an API commit. Routing, selector picks and open connections stay as
//! they are. The new resolver keeps the old one's fake-IP pool, so a fake IP
//! an app still holds names the same host, and its IP → host table, so a
//! flow to an address answered before the switch still matches domain rules.

use crate::engine::{self, DnsUpstreams};
use anyhow::{anyhow, Context, Result};
use meow_api::routes::DnsServerHandle;
use meow_config::raw::{RawConfig, RawDns};
use meow_config::rule_provider::RuleProvider;
use meow_config::DnsConfig;
use meow_tunnel::Tunnel;
use parking_lot::{Mutex, RwLock};
use std::collections::HashMap;
use std::sync::Arc;

/// What a switch rebuilds from, for the life of one engine start.
pub(crate) struct LiveDns {
    /// The profile's own `dns:` block, as read at start.
    profile_dns: Option<serde_yaml::Value>,
    /// The file the config loaded from (`engine::sibling_stripped_path`).
    config_path: Option<String>,
    /// What the engine runs on now.
    upstreams: Mutex<DnsUpstreams>,
    /// The API's copy of the running config. Its `dns:` follows each switch:
    /// GET /configs reports it, and later API commits compare against it.
    raw_config: Arc<RwLock<RawConfig>>,
    rule_providers: Arc<RwLock<HashMap<String, Arc<RuleProvider>>>>,
    dns_server: Arc<RwLock<Option<DnsServerHandle>>>,
}

impl LiveDns {
    pub(crate) fn new(
        profile_dns: Option<serde_yaml::Value>,
        config_path: Option<String>,
        upstreams: DnsUpstreams,
        raw_config: Arc<RwLock<RawConfig>>,
        rule_providers: Arc<RwLock<HashMap<String, Arc<RuleProvider>>>>,
        dns_server: Arc<RwLock<Option<DnsServerHandle>>>,
    ) -> Self {
        Self {
            profile_dns,
            config_path,
            upstreams: Mutex::new(upstreams),
            raw_config,
            rule_providers,
            dns_server,
        }
    }

    /// The running config with the `dns:` section `upstreams` asks for, or
    /// `None` when the engine already runs on them.
    fn candidate(&self, upstreams: DnsUpstreams) -> Result<Option<RawConfig>> {
        if *self.upstreams.lock() == upstreams {
            return Ok(None);
        }
        let mut block = serde_yaml::Value::Mapping(engine::engine_dns_block(
            self.profile_dns.clone(),
            upstreams,
        ));
        block.apply_merge().context("expanding the dns block")?;
        let mut dns: RawDns = serde_yaml::from_value(block).context("parsing the dns block")?;
        let mut candidate = self.raw_config.read().clone();
        // The fake-IP store stays the one this start opened, even though
        // `Builtin` drops the profile's `store-fake-ip`: a new store would
        // strand the fake IPs apps still hold and hand them out to other
        // hosts.
        dns.store_fake_ip = candidate.dns.as_ref().and_then(|d| d.store_fake_ip);
        candidate.dns = Some(dns);
        Ok(Some(candidate))
    }

    /// The resolver `candidate` describes; `None` when it would be the same
    /// as the running one. Called inside the `CONFIG_MUTATION` lane.
    async fn rebuild(&self, tunnel: &Tunnel, candidate: &RawConfig) -> Result<Option<DnsConfig>> {
        let route = tunnel.route_snapshot();
        let rule_providers = self.rule_providers.read().clone();
        meow_api::routes::reconcile_dns_config(
            &self.raw_config,
            candidate,
            self.config_path.as_deref(),
            &route.proxies,
            Some(&rule_providers),
            None,
            Some(tunnel.resolver()),
            Some(&route.dialer_registry),
        )
        .await
        .map_err(|(_, e)| anyhow!(e))
    }

    /// Puts `dns` (when there is a new resolver) and `candidate`'s `dns:`
    /// section in place. Called inside the `CONFIG_MUTATION` lane, with
    /// `ENGINE` held by the caller so a stop cannot interleave.
    fn publish(
        &self,
        tunnel: &Tunnel,
        dns: Option<&DnsConfig>,
        candidate: RawConfig,
        upstreams: DnsUpstreams,
    ) {
        if let Some(dns) = dns {
            dns.resolver
                .restore_reverse_cache(tunnel.resolver().reverse_cache_snapshot());
            // The tunnel's resolver slot (routing lookups, DIRECT dials) and
            // the host hook proxy adapters resolve server names through.
            meow_api::routes::install_resolver_everywhere(tunnel, &self.dns_server, dns);
            // The tun2socks DNS intercept.
            *crate::DNS_RESOLVER.write() = Some(Arc::clone(&dns.resolver));
        }
        self.raw_config.write().dns = candidate.dns;
        *self.upstreams.lock() = upstreams;
    }
}

/// Puts the running engine on `upstreams`. Does nothing when it already runs
/// on them or no engine runs: a start reads the switch itself. On an error
/// the engine keeps the DNS it had.
pub(crate) fn switch(upstreams: DnsUpstreams) -> Result<()> {
    crate::get_runtime().block_on(async {
        let _lane = meow_api::routes::CONFIG_MUTATION.lock().await;
        let running = crate::ENGINE
            .lock()
            .as_ref()
            .map(|s| (s.tunnel.clone(), Arc::clone(&s.live_dns)));
        let Some((tunnel, live)) = running else {
            return Ok(());
        };
        let Some(candidate) = live.candidate(upstreams)? else {
            return Ok(());
        };
        let dns = live.rebuild(&tunnel, &candidate).await?;
        let engine = crate::ENGINE.lock();
        // Stopped, and maybe started again, while the resolver was building:
        // it belongs to an engine that is gone.
        if !engine
            .as_ref()
            .is_some_and(|s| Arc::ptr_eq(&s.live_dns, &live))
        {
            return Ok(());
        }
        live.publish(&tunnel, dns.as_ref(), candidate, upstreams);
        tracing::info!("dns: switched to the {upstreams:?} upstreams");
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{dns_query, from_the_profile_nameserver, txt_nameserver, SERIAL};
    use std::net::IpAddr;
    use std::time::Duration;

    fn resolver() -> Arc<meow_dns::Resolver> {
        crate::DNS_RESOLVER.read().clone().expect("a resolver")
    }

    fn nameservers() -> Option<Vec<String>> {
        let engine = crate::ENGINE.lock();
        let raw = engine.as_ref().unwrap().live_dns.raw_config.read();
        raw.dns.as_ref().and_then(|d| d.nameserver.clone())
    }

    /// Whether `resolver` answers a TXT query from the profile's nameserver.
    fn txt_from_the_profile(resolver: &meow_dns::Resolver) -> bool {
        let query = dns_query("meow-test.invalid", 16);
        let reply = crate::get_runtime()
            .block_on(meow_dns::DnsServer::handle_query(&query, resolver))
            .unwrap();
        from_the_profile_nameserver(&reply)
    }

    /// Starts an engine on `profile`, run from a fresh home dir that the
    /// returned guard removes along with the engine.
    fn start(profile: &str, upstreams: DnsUpstreams, name: &str) -> impl Drop {
        struct Running(std::path::PathBuf);
        impl Drop for Running {
            fn drop(&mut self) {
                crate::stop_engine();
                *crate::HOME_DIR.lock() = None;
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
        // `<dir>/meow`, as the app's `no_backup/meow`: the engine takes the
        // parent for `XDG_CONFIG_HOME`, and keeps its logbook under the home.
        let dir = std::env::temp_dir().join(format!("meow-{name}-{}", std::process::id()));
        let home = dir.join("meow");
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(home.join("config.yaml"), profile).unwrap();
        *crate::HOME_DIR.lock() = Some(home.to_str().unwrap().to_string());
        let running = Running(dir);
        assert_eq!(
            crate::start_engine(None, None, None, upstreams),
            0,
            "{}",
            crate::get_error()
        );
        running
    }

    /// Off and on again on a running engine: the queries move to the other
    /// upstreams, everything the tunnel and tun2socks resolve through moves
    /// with them, and the addresses answered before the switch still name
    /// their hosts: a real one, and a fake one also for a profile that
    /// persists its fake IPs, whose `store-fake-ip` the built-in block drops.
    #[test]
    fn the_switch_moves_a_running_engine_and_keeps_the_addresses_it_answered() {
        let _serial = SERIAL.lock();
        let rt = crate::get_runtime();
        let nameserver = rt.block_on(txt_nameserver());
        let engine = start(
            &format!(
                "dns:\n  store-fake-ip: true\n  nameserver: [\"udp://{nameserver}\"]\n\
                 rules: [MATCH,DIRECT]\n"
            ),
            DnsUpstreams::Profile,
            "live-dns",
        );

        let before = resolver();
        assert!(txt_from_the_profile(&before));
        let fake = rt
            .block_on(before.lookup_ipv4("a.example"))
            .expect("a fake IP");
        assert!(before.is_fake_ip(fake));
        let real = IpAddr::from([203, 0, 113, 7]);
        before.preload_cache("real.example", &[real], Duration::from_secs(60));

        switch(DnsUpstreams::Builtin).unwrap();
        let builtin = resolver();
        assert!(
            !Arc::ptr_eq(&builtin, &before),
            "the resolver was not rebuilt"
        );
        assert!(Arc::ptr_eq(&builtin, &engine::tunnel().unwrap().resolver()));
        assert_eq!(builtin.reverse_lookup(fake).as_deref(), Some("a.example"));
        assert_eq!(
            builtin.reverse_lookup(real).as_deref(),
            Some("real.example")
        );
        let builtin_pool = engine::BUILTIN_NAMESERVERS.iter().map(|ns| ns.to_string());
        assert_eq!(nameservers(), Some(builtin_pool.collect()));

        switch(DnsUpstreams::Profile).unwrap();
        let profile = resolver();
        assert!(!Arc::ptr_eq(&profile, &builtin));
        assert!(txt_from_the_profile(&profile));
        assert_eq!(profile.reverse_lookup(fake).as_deref(), Some("a.example"));
        assert_eq!(
            profile.reverse_lookup(real).as_deref(),
            Some("real.example")
        );
        assert_eq!(
            rt.block_on(profile.lookup_ipv4("a.example")),
            Some(fake),
            "the host got another fake IP"
        );
        assert_eq!(nameservers(), Some(vec![format!("udp://{nameserver}")]));

        // Already there: nothing is rebuilt.
        switch(DnsUpstreams::Profile).unwrap();
        assert!(Arc::ptr_eq(&resolver(), &profile));

        drop(engine);
        assert!(crate::DNS_RESOLVER.read().is_none());
        // With no engine there is nothing to switch.
        switch(DnsUpstreams::Builtin).unwrap();
    }

    /// A profile DNS block that does not build leaves the engine where it
    /// was, and still counts as not switched: turning the switch back on is
    /// a no-op, not a second rebuild.
    #[test]
    fn a_profile_dns_that_fails_keeps_the_running_one() {
        let _serial = SERIAL.lock();
        let _engine = start(
            "dns:\n  nameserver-policy:\n    'rule-set:missing': udp://127.0.0.1:1\n\
             rules: [MATCH,DIRECT]\n",
            DnsUpstreams::Builtin,
            "live-dns-broken",
        );
        let before = resolver();

        let err = switch(DnsUpstreams::Profile).unwrap_err();
        assert!(format!("{err:#}").contains("rule-set: missing"), "{err:#}");
        assert!(Arc::ptr_eq(&resolver(), &before));
        assert!(Arc::ptr_eq(&engine::tunnel().unwrap().resolver(), &before));

        switch(DnsUpstreams::Builtin).unwrap();
        assert!(Arc::ptr_eq(&resolver(), &before));
    }
}
