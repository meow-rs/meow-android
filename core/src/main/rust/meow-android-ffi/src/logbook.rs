//! Reads of the engine's logbook (`meow_common::logbook`), the persistent
//! record of notable events: starts and stops, unclean exits, config and
//! provider failures, group switches. The `:vpn` process writes it under the
//! engine home; the Logbook page reads it from the UI process, also while
//! the engine is stopped, through `logbook::read` — one SQLite library per
//! process for the file, as the logbook requires of a process that links
//! meow.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use meow_common::logbook::{self, Level, Query};
use std::path::Path;

/// A page of the logbook under the engine home `home`, newest first, as
/// `GET /logbook` serves it: `{"entries": [...], "rev": n, "more": bool}`,
/// or `{"error": "..."}`. `before` pages back from an entry id, `since`
/// polls for what was written after a page's `rev`; 0 leaves either unset.
/// `level` is the lowest level returned, `kind` a kind or its area
/// (`config` matches `config.reload`); empty for all.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MeowCore_nativeReadLogbook(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
    before: jlong,
    since: jlong,
    limit: jint,
    level: JString,
    kind: JString,
) -> jstring {
    let mut read =
        |s: &JString| -> String { env.get_string(s).map(Into::into).unwrap_or_default() };
    let (home, level, kind) = (read(&home), read(&level), read(&kind));
    let json = std::panic::catch_unwind(|| {
        read_page(Path::new(&home), before, since, limit, &level, &kind)
    })
    .unwrap_or_else(|_| error_json("logbook read panicked"));
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn read_page(home: &Path, before: i64, since: i64, limit: i32, level: &str, kind: &str) -> String {
    let level = match level {
        "" => None,
        level => match level.parse::<Level>() {
            Ok(level) => Some(level),
            Err(e) => return error_json(&e),
        },
    };
    let query = Query {
        limit: usize::try_from(limit).ok().filter(|&limit| limit > 0),
        before: u64::try_from(before).ok().filter(|&id| id > 0),
        since: u64::try_from(since).ok().filter(|&rev| rev > 0),
        level,
        kind: Some(kind).filter(|kind| !kind.is_empty()).map(Into::into),
        ..Query::default()
    };
    match logbook::read(&home.join(logbook::DIR_NAME), &query) {
        Ok(page) => serde_json::to_string(&page).unwrap_or_else(|e| error_json(&e.to_string())),
        Err(e) => error_json(&e.to_string()),
    }
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::SERIAL;
    use serde_json::Value;
    use std::path::PathBuf;

    /// An empty engine home, `<dir>/meow` as the app's `no_backup/meow`;
    /// `<dir>` is removed on drop.
    struct Home(PathBuf);

    impl Home {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("meow-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(dir.join("meow")).unwrap();
            Self(dir)
        }

        fn path(&self) -> PathBuf {
            self.0.join("meow")
        }
    }

    impl Drop for Home {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn page(home: &Path, before: i64, since: i64, limit: i32, level: &str) -> Value {
        of_kind(home, before, since, limit, level, "")
    }

    fn of_kind(home: &Path, before: i64, since: i64, limit: i32, level: &str, kind: &str) -> Value {
        serde_json::from_str(&read_page(home, before, since, limit, level, kind)).unwrap()
    }

    fn messages(page: &Value) -> Vec<&str> {
        page["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|entry| entry["message"].as_str().unwrap())
            .collect()
    }

    #[test]
    fn no_logbook_reads_as_an_empty_page() {
        let home = Home::new("logbook-none");
        let page = page(&home.path(), 0, 0, 0, "");
        assert_eq!(messages(&page), Vec::<&str>::new());
        assert_eq!(page["rev"], 0);
        assert_eq!(page["more"], false);
    }

    #[test]
    fn reads_what_the_engine_recorded_after_it_stopped() {
        // The logbook is process-wide, and an engine start installs one.
        let _serial = SERIAL.lock();
        let home = Home::new("logbook-read");
        logbook::install(&home.path().join(logbook::DIR_NAME), Default::default()).unwrap();
        for (level, message) in [
            (Level::Info, "first"),
            (Level::Error, "second"),
            (Level::Warning, "third"),
        ] {
            logbook::event(logbook::kind::CONFIG_RELOAD, level, message).record();
        }
        logbook::event(logbook::kind::GROUP_SWITCH, Level::Info, "switched")
            .subject("Auto")
            .record();
        // Writes out what is pending and closes the database, as a stopped
        // `:vpn` process would have.
        logbook::uninstall();

        let all = page(&home.path(), 0, 0, 0, "");
        assert_eq!(messages(&all), ["switched", "third", "second", "first"]);
        assert_eq!(all["entries"][1]["kind"], "config.reload");
        assert_eq!(all["entries"][1]["level"], "warning");
        let rev = all["rev"].as_i64().unwrap();
        assert!(rev > 0);

        let newest = page(&home.path(), 0, 0, 1, "");
        assert_eq!(messages(&newest), ["switched"]);
        assert_eq!(newest["more"], true);
        let older = newest["entries"][0]["id"].as_i64().unwrap();
        assert_eq!(
            messages(&page(&home.path(), older, 0, 0, "")),
            ["third", "second", "first"]
        );

        // A kind, or its area; the page's rev is the logbook's either way.
        let switches = of_kind(&home.path(), 0, 0, 0, "", "group.switch");
        assert_eq!(messages(&switches), ["switched"]);
        assert_eq!(switches["rev"], all["rev"]);
        assert_eq!(
            messages(&of_kind(&home.path(), 0, 0, 0, "", "config")),
            ["third", "second", "first"]
        );
        assert_eq!(
            messages(&of_kind(&home.path(), 0, 0, 0, "", "group.health")),
            Vec::<&str>::new()
        );

        assert_eq!(
            messages(&page(&home.path(), 0, 0, 0, "warning")),
            ["third", "second"]
        );
        assert_eq!(
            messages(&page(&home.path(), 0, rev, 0, "")),
            Vec::<&str>::new()
        );
        assert!(page(&home.path(), 0, 0, 0, "loud")["error"].is_string());
    }

    #[test]
    fn an_engine_run_is_recorded_under_its_home() {
        let _serial = SERIAL.lock();
        // Another test's engine may have left its logbook installed.
        logbook::uninstall();
        let home = Home::new("logbook-run");
        std::fs::write(home.path().join("config.yaml"), "rules: [MATCH,DIRECT]\n").unwrap();
        *crate::HOME_DIR.lock() = Some(home.path().to_str().unwrap().to_string());
        let started = crate::start_engine(None, None, None, crate::engine::DnsUpstreams::Builtin);
        let error = crate::get_error();
        crate::stop_engine();
        *crate::HOME_DIR.lock() = None;
        logbook::uninstall();
        assert_eq!(started, 0, "{error}");

        let page = page(&home.path(), 0, 0, 0, "");
        let kinds: Vec<_> = page["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|entry| entry["kind"].as_str().unwrap())
            .collect();
        assert_eq!(kinds, ["engine.stop", "engine.start"]);
    }
}
