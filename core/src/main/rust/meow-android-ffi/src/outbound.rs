//! A switch of the network the engine's sockets go out on, which the app
//! hears of through `DefaultNetwork` in `MeowInstance`.
//!
//! A protected socket stays on the network it connected on, so what the
//! engine opened before the switch is cut off with that network, or left on
//! one no longer in use: an app's connection hangs until it times out, and a
//! pooled session (QUIC, mux, smux, a Snell pool) holds the requests sent
//! through it meanwhile. It is all torn down at once instead, as meow-api
//! does when the outbound interface binding changes, so the apps and the
//! engine redial on the new network.

use crate::logbook::NETWORK_CHANGE;
use crate::{get_runtime, ENGINE};
use jni::objects::{JClass, JString};
use jni::JNIEnv;
use meow_common::logbook::{self, Level};
use meow_tunnel::{OutboundFlush, TrackedTcp};
use std::sync::Arc;

/// The engine's sockets now go out on `network` (`Wi-Fi 324`): see
/// [`reset`]. Returns at once.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeResetOutbound(
    mut env: JNIEnv,
    _class: JClass,
    network: JString,
) {
    let network: String = env.get_string(&network).map(Into::into).unwrap_or_default();
    let _ = reset(network);
}

/// Spawns the teardown on the engine's runtime: tracked TCP flows (the
/// apps' connections through the TUN among them) and UDP sessions are
/// closed, pooled upstream DNS connections dropped, and the pooled sessions
/// of every proxy and group reset, provider members no group uses included.
/// Recorded as a [`NETWORK_CHANGE`]. `None` while no engine runs.
fn reset(network: String) -> Option<tokio::task::JoinHandle<()>> {
    let (tunnel, providers) = {
        let engine = ENGINE.lock();
        let engine = engine.as_ref()?;
        (engine.tunnel.clone(), Arc::clone(&engine.proxy_providers))
    };
    Some(get_runtime().spawn(async move {
        // Collected first: no DashMap shard lock is held across the flush.
        let members: Vec<_> = providers
            .iter()
            .flat_map(|entry| entry.value().proxies())
            .collect();
        let flush = tunnel.flush_for_outbound_interface_change(TrackedTcp::Cancel, members);
        logbook::event(NETWORK_CHANGE, Level::Info, message(&network, flush))
            .field("tcp", flush.tcp)
            .field("udp", flush.udp)
            .field("dns", flush.dns)
            .field("adapters", flush.adapters)
            .record();
    }))
}

fn message(network: &str, flush: OutboundFlush) -> String {
    format!(
        "The engine moved to {network}: it closed {} and {}, dropped {} and reset the \
         sessions of {}.",
        count(flush.tcp, "TCP connection", "TCP connections"),
        count(flush.udp, "UDP session", "UDP sessions"),
        count(flush.dns, "pooled DNS connection", "pooled DNS connections"),
        count(flush.adapters, "proxy or group", "proxies and groups"),
    )
}

fn count(n: usize, one: &str, many: &str) -> String {
    if n == 1 {
        format!("1 {one}")
    } else {
        format!("{n} {many}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::SERIAL;
    use meow_common::logbook::Query;

    #[test]
    fn the_message_counts_what_was_torn_down() {
        let flush = OutboundFlush {
            tcp: 1,
            udp: 0,
            dns: 2,
            adapters: 1,
        };
        assert_eq!(
            message("Wi-Fi 324", flush),
            "The engine moved to Wi-Fi 324: it closed 1 TCP connection and 0 UDP sessions, \
             dropped 2 pooled DNS connections and reset the sessions of 1 proxy or group."
        );
    }

    #[test]
    fn a_switch_tears_down_the_running_engines_outbound_and_is_recorded() {
        let _serial = SERIAL.lock();
        // Another test's engine may have left its logbook installed.
        logbook::uninstall();
        assert!(reset("Wi-Fi 324".into()).is_none(), "no engine runs");

        // `<dir>/meow`, as the app's `no_backup/meow`.
        let dir = std::env::temp_dir().join(format!("meow-outbound-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let home = dir.join("meow");
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(home.join("config.yaml"), "rules:\n  - MATCH,DIRECT\n").unwrap();
        *crate::HOME_DIR.lock() = Some(home.to_str().unwrap().to_string());
        let started = crate::start_engine(None, None, None, crate::engine::DnsUpstreams::Builtin);
        let error = crate::get_error();
        let reset = reset("Wi-Fi 324".into()).map(|task| get_runtime().block_on(task));
        crate::stop_engine();
        *crate::HOME_DIR.lock() = None;
        logbook::uninstall();
        assert_eq!(started, 0, "{error}");
        assert!(reset.expect("an engine runs").is_ok());

        let query = Query {
            kind: Some(NETWORK_CHANGE.into()),
            ..Query::default()
        };
        let page = logbook::read(&home.join(logbook::DIR_NAME), &query).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
        let [entry] = page.entries.as_slice() else {
            panic!("one entry: {:?}", page.entries);
        };
        assert_eq!(entry.level, Level::Info);
        assert!(
            entry.message.starts_with(
                "The engine moved to Wi-Fi 324: it closed 0 TCP connections and 0 UDP sessions"
            ),
            "{}",
            entry.message
        );
        for field in ["tcp", "udp", "dns", "adapters"] {
            assert!(entry.fields[field].is_u64(), "{field}: {:?}", entry.fields);
        }
    }
}
