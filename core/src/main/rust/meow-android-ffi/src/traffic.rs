//! The engine's traffic ledger (`meow_common::traffic_ledger`): every day's
//! traffic by host, app, policy and network, kept for 62 days. The `:vpn`
//! process writes it under the engine home and tells it the network and the
//! time zone; the Traffic details page reads and clears it from the UI
//! process, also while the engine is stopped.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use meow_common::traffic_ledger::{self, GroupBy, Query, TimeZone};
use std::path::Path;

/// The traffic under the engine home `home` of the local days `from..=to`
/// (days since 1970-01-01, `LocalDate.toEpochDay()`), grouped `by` `host`,
/// `app` (package names), `policy` or `interface`, largest first, keeping the rows that match the
/// non-empty filters: `{"from": "2026-11-01", "to": "2026-11-30", "by":
/// "host", "upload": n, "download": n, "requests": n, "rows": [{"name": "…",
/// "upload": n, "download": n, "requests": n}]}`, or `{"error": "…"}`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeReadTraffic(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
    from: jint,
    to: jint,
    by: JString,
    host: JString,
    app: JString,
    policy: JString,
    interface: JString,
) -> jstring {
    let mut read =
        |s: &JString| -> String { env.get_string(s).map(Into::into).unwrap_or_default() };
    let (home, by, host, app, policy, interface) = (
        read(&home),
        read(&by),
        read(&host),
        read(&app),
        read(&policy),
        read(&interface),
    );
    let json = std::panic::catch_unwind(|| {
        let filter = |s: String| Some(s).filter(|s| !s.is_empty());
        read_report(
            Path::new(&home),
            from,
            to,
            &by,
            [filter(host), filter(app), filter(policy), filter(interface)],
        )
    })
    .unwrap_or_else(|_| error_json("traffic read panicked"));
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn read_report(
    home: &Path,
    from: i32,
    to: i32,
    by: &str,
    [host, app, policy, interface]: [Option<String>; 4],
) -> String {
    let by = match by.parse::<GroupBy>() {
        Ok(by) => by,
        Err(e) => return error_json(&e),
    };
    let query = Query {
        from,
        to,
        by,
        host,
        app,
        policy,
        interface,
    };
    match traffic_ledger::read(&home.join(traffic_ledger::DIR_NAME), &query) {
        Ok(report) => serde_json::to_string(&report).unwrap_or_else(|e| error_json(&e.to_string())),
        Err(e) => error_json(&e.to_string()),
    }
}

/// Delete everything in the ledger under the engine home `home`. A running
/// engine notices at its next write (at most a minute later) and drops
/// what it counted until then instead of writing it over the clear. False
/// when the database could not be cleared.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeClearTraffic(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
) -> jboolean {
    let home: String = env.get_string(&home).map(Into::into).unwrap_or_default();
    let cleared = std::panic::catch_unwind(|| {
        traffic_ledger::clear_at(&Path::new(&home).join(traffic_ledger::DIR_NAME))
    });
    match cleared {
        Ok(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// The network the engine's traffic goes out on now, as the ledger labels
/// it: `Wi-Fi`, `Cellular`, `Ethernet`, `Other`. What was counted before is
/// written under the previous label first.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeSetTrafficInterface(
    mut env: JNIEnv,
    _class: JClass,
    label: JString,
) {
    let label: String = env.get_string(&label).map(Into::into).unwrap_or_default();
    let _ = std::panic::catch_unwind(|| traffic_ledger::set_interface(&label));
}

/// The device's time zone, which decides the day traffic is counted under:
/// the UTC offset now, in seconds, and the next change of it — at the Unix
/// second `next_at` (0 for none known) to `next_offset`.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeSetTimeZone(
    _env: JNIEnv,
    _class: JClass,
    offset: jint,
    next_at: jlong,
    next_offset: jint,
) {
    traffic_ledger::set_time_zone(TimeZone {
        offset,
        next: (next_at > 0).then_some((next_at, next_offset)),
    });
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::SERIAL;
    use smol_str::SmolStr;
    use std::path::PathBuf;
    use std::sync::Arc;

    struct Home(PathBuf);

    impl Home {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("meow-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for Home {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn no_ledger_reads_as_an_empty_report() {
        let home = Home::new("traffic-none");
        let json = read_report(home.path(), 20_000, 20_001, "policy", Default::default());
        let report: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(report["by"], "policy");
        assert_eq!(report["rows"], serde_json::json!([]));
        assert!(read_report(home.path(), 0, 0, "nodes", Default::default()).contains("error"));
    }

    #[test]
    fn the_ui_reads_and_clears_what_the_engine_wrote() {
        // The ledger is process-wide, and an engine start installs one.
        let _serial = SERIAL.lock();
        let home = Home::new("traffic-read");
        let dir = home.path().join(traffic_ledger::DIR_NAME);
        traffic_ledger::install(&dir, Default::default()).unwrap();
        traffic_ledger::set_interface("Wi-Fi");
        let app = SmolStr::new("org.example.browser");
        let host = SmolStr::new("www.example.com");
        traffic_ledger::add(
            &host,
            &app,
            &traffic_ledger::Route::from("HK 01"),
            10,
            20,
            1,
        );
        // A flow out through JP 02 whose hop to its server HK 01 carried
        // (a `dialer-proxy`): HK 01's policy row counts it too, the totals
        // once.
        let chained = traffic_ledger::Route {
            policy: Arc::from("JP 02"),
            via: Some(Arc::from("HK 01")),
        };
        traffic_ledger::add(&host, &app, &chained, 1, 2, 1);
        traffic_ledger::flush();
        let today = traffic_ledger::today();
        let json = read_report(
            home.path(),
            today,
            today,
            "host",
            [None, None, Some("HK 01".into()), Some("Wi-Fi".into())],
        );
        let report: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(report["rows"][0]["name"], "example.com");
        assert_eq!(report["download"], 22);
        let json = read_report(home.path(), today, today, "policy", Default::default());
        let report: serde_json::Value = serde_json::from_str(&json).unwrap();
        let rows: Vec<_> = report["rows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| {
                (
                    row["name"].as_str().unwrap(),
                    row["download"].as_u64().unwrap(),
                )
            })
            .collect();
        assert_eq!(rows, [("HK 01", 22), ("JP 02", 2)]);
        assert_eq!(
            (&report["download"], &report["requests"]),
            (&22.into(), &2.into())
        );
        let json = read_report(
            home.path(),
            today,
            today,
            "app",
            [Some("example.com".into()), None, None, None],
        );
        let report: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(report["rows"][0]["name"], "org.example.browser");
        traffic_ledger::clear_at(&dir).unwrap();
        let json = read_report(home.path(), today, today, "host", Default::default());
        assert!(json.contains(r#""rows":[]"#), "{json}");
        traffic_ledger::uninstall();
        traffic_ledger::set_interface("");
    }

    /// The Other IP addresses page names the engine's cap from a Kotlin
    /// copy; an engine bump that moves it fails here until the copy follows.
    #[test]
    fn the_ip_cap_matches_traffic_row() {
        assert_eq!(
            traffic_ledger::MAX_IP_HOSTS,
            8192,
            "update TrafficRow.MAX_IP_HOSTS"
        );
    }
}
