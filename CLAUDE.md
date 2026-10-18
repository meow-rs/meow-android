# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK (arm64 only, release Rust for smaller .so)
export JAVA_HOME=/path/to/jdk  # 17–25
./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release

# Build all ABIs
./gradlew :mobile:assembleDebug -PCARGO_PROFILE=release

# Build Rust only (faster iteration on native code)
./gradlew :core:cargoBuildArm64 -PCARGO_PROFILE=release

# Clean (includes cargo clean)
./gradlew clean

# E2E test (requires ssserver, Android emulator or device, adb)
# Env vars: EMULATOR, ADB, AVD, APK, SSSERVER, PKG, MAIN_ACTIVITY,
#   SS_HOST_FROM_EMU, SUB_PORT, SKIP_EMULATOR_BOOT, TUN_WAIT_SECS
./test-e2e.sh

# Run with existing emulator
SKIP_EMULATOR_BOOT=true ./test-e2e.sh

# Run on a physical device on the same LAN — SS_HOST_FROM_EMU is how the
# device reaches the host's ssserver/sub server (10.0.2.2 = emulator NAT
# default, host's LAN IP on a physical device). ANDROID_SERIAL selects the
# device when several are attached.
SKIP_EMULATOR_BOOT=true SS_HOST_FROM_EMU=<host-LAN-IP> ./test-e2e.sh
```

**JDK 17–25.** CI uses 17. Gradle 8.14 could not start on JDK 25 — that was the old "JDK 25 breaks Kotlin" — but Gradle 9.1+ runs on it. Set `JAVA_HOME` explicitly.

Gradle is pinned to 9.4.1, the newest release whose compatibility matrix still tests AGP 8.13 (9.5+ only tests AGP 9). Gradle 9 needs rust-android-gradle ≥ 0.10.0, because 0.9.6 calls `AbstractCopyTask.setFileMode`, which Gradle 9 removed.

## Lint Commands

**You MUST run the relevant lint commands before considering any code change complete.** Fix all lint errors before committing.

```bash
# Android lint (Kotlin)
./gradlew :mobile:lintDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release

# Rust clippy (from repo root)
cd core/src/main/rust/meow-android-ffi && cargo clippy -- -D warnings && cd -

# Rust format check
cd core/src/main/rust/meow-android-ffi && cargo fmt --check && cd -

# Unit tests (engine API client + string parity across every locale)
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest
```

Run Android lint and the unit tests after Kotlin changes, clippy/rustfmt after Rust changes.

## Architecture

Three-layer stack: **Compose UI → Kotlin VPN Service → Rust FFI**

```
Compose (Kotlin)                  ViewModels ← StateFlow ← VpnStateRepository
    ↕ direct calls                                          (AIDL callbacks)
Kotlin (Android)                  MeowApi (OkHttp) → 127.0.0.1:9090
    ↕ JNI
Rust (libmeow_android_ffi.so)   lwip netstack tun2socks + meow-rs engine
```

The UI runs in the same process as the Kotlin layer and calls it directly —
there is no serialization boundary between them. The one remote hop is the
engine's own controller API on loopback, which crosses into `:vpn`.

### Rust FFI (`core/src/main/rust/meow-android-ffi/`)

- **lib.rs**: JNI entry points (`Java_io_github_madeye_meow_core_MeowCore_*`), engine lifecycle (tokio runtime, Tunnel, API server). No SOCKS5/HTTP loopback listener — every TUN flow is dispatched in-process.
- **tun2socks.rs**: Reads TUN fd packets. UDP/53 is intercepted pre-stack: every query is answered in-process by the engine's `meow_dns::Resolver` (`DnsServer::handle_query`): A/AAAA get fake-IP synthesis, other qtypes are forwarded over the same upstreams (protected sockets), so they follow the Built-in DNS switch too. Everything else feeds the `lwip` netstack — each accepted TCP flow is wrapped as `NetstackConn` (a `ProxyConn` newtype around `lwip::TcpStream`) and handed straight to `meow_tunnel::tcp::handle_tcp(&inner, conn, metadata)`; UDP flows are dispatched to `meow_tunnel::udp::handle_udp` with per-session reply readers.
- **protect.rs**: Implements `meow_common::SocketProtector` via a JNI shim around `VpnService.protect(int)`. Installed once in `nativeStartTun2Socks`; meow-rs invokes it for every outbound TCP/UDP fd (proxy adapters + the DNS resolver's default `SocketFactory`) before `connect()`/`bind()`.
- **engine.rs**: `tunnel()` accessor — returns the running `Tunnel` handle so `tun2socks` can dispatch flows through `meow_tunnel::{tcp,udp}` without re-implementing rule routing. Also `strip_and_inject`: strips listener ports and `sniffer:` from config.yaml and pins the profile's `dns:` block to in-process fake-IP (`enable`, `listen`, `enhanced-mode`, `fake-ip-range` are overridden). The profile keeps its upstreams, `fake-ip-filter`, etc.; a missing `nameserver` / `default-nameserver` gets the built-in CN-side pool (`tcp://119.29.29.29`, `tcp://223.5.5.5`). `load_with_dns_fallback` reloads on the built-in pool alone when the profile's DNS cannot be set up (e.g. a DoT hostname that fails to bootstrap), so start and validation never fail on DNS alone. Settings' "Built-in DNS" switch (`preference/BuiltinDnsStore`, passed as `nativeStartEngine`'s `builtinDns`) skips the profile's upstreams altogether. It is on by default (#135); only an explicitly saved off value runs on the profile's DNS.
- **live_dns.rs**: applies that switch to a running engine (`nativeSetBuiltinDns`, reached over the AIDL `setBuiltinDns`). Rebuilds only the resolver, the way meow-api's own commits do: inside `meow_api::routes::CONFIG_MUTATION`, `reconcile_dns_config` with the running resolver as the prior one, then `install_resolver_everywhere`, `DNS_RESOLVER` (the tun2socks intercept) and the API's raw config. The fake-IP pool is kept: the candidate keeps the running `store-fake-ip`, which the built-in block would otherwise drop and so reopen the pool; the IP → host table is carried over (`restore_reverse_cache`). A rebuild that fails leaves the running DNS in place and the error reaches Settings' snackbar.
- **diagnostics.rs**: JNI-exposed native connectivity probes (`nativeTestDirectTcp`, UDP) used by the Settings diagnostics UI.
- **logging.rs**: `android_logger` / tracing setup bridging Rust logs to logcat and the in-app log stream.

### Kotlin Core (`core/src/main/java/io/github/madeye/meow/`)

- **bg/BaseService.kt**: State machine (Idle→Connecting→Connected→Stopping→Stopped) with AIDL binder, RemoteCallbackList for traffic callbacks. Ported from shadowsocks-android.
- **bg/VpnService.kt**: Creates TUN interface (172.19.0.1/30, MTU 1500, route 0.0.0.0/0; plus fdfe:dcba:9876::1/126 and ::/0 unless the profile sets `ipv6: false`, read via `nativeTunIpv6` — mihomo also drops the TUN's IPv6 address then, and Android blocks IPv6 for the covered apps). Passes TUN fd + `this` (VpnService) to Rust via JNI. DNS set to 172.19.0.2 (routed through TUN → in-process DNS interception in tun2socks).
- **bg/ServiceNotification.kt**: The foreground-service notification (type `systemExempted` on API 34+, which Android grants VPN apps holding consent): profile name, live ↑/↓ speed from the FFI byte counters (1 s ticks, only while the screen is on), Stop (`Action.CLOSE`), tap opens the app. Callers start the service with `startForegroundService` (`VpnService.start`), so `onStartCommand` posts it before any early return; a restart (`stopRunner(true)`) keeps it, and the foreground, up.
- **bg/MeowInstance.kt**: Writes config.yaml (stripping only the app-managed `subscriptions:` block — listeners/`sniffer:` and the `dns:` pinning are handled by `engine::strip_and_inject` on the Rust side), calls JNI start/stop. Passes the Home route-mode pick (`preference/RouteModeStore`, a one-line file because `:vpn` can't see fresh SharedPreferences writes from the UI process) to `nativeStartEngine`, which overrides the profile's `mode:`, and the Settings "Built-in DNS" switch (`preference/BuiltinDnsStore`, same one-line-file reason). Settings saves a flip, then sends it over the binder (`BuiltinDnsPreference`'s `applyLive`); a start re-reads the store once the engine is up, for a flip that came in before there was one to switch.
- **bg/NetworkTraffic.kt**: the Statistics screen's per-network split. `DefaultNetworkKind` follows the best non-VPN network (`registerBestMatchingNetworkCallback` on API 31+, `requestNetwork` on 28-30, the default-network callback below; shadowsocks-android's registration), and `NetworkTrafficMeter` charges the engine's byte totals gained since the last change to the network in use meanwhile (bytes counted with none go to the next one). `MeowInstance` runs one per engine start and the binder's `getSessionStats` returns its start time and Wi-Fi/Ethernet, cellular and other totals.
- **core/MeowCore.kt**: JNI bridge object. `System.loadLibrary("meow_android_ffi")`.
- **database/**: Room database with `ClashProfile` entity (id, name, url, yamlContent, selected, lastUpdated, tx, rx).
  A profile with a `url` is a subscription, and its config is read-only, as on Surge: it
  belongs to the provider and every download replaces it. `ProfileDao.updateYamlContent`
  only writes local profiles (`url = ''`: file imports and copies), so editing a
  subscription means `ProfileRepository.duplicate`, which makes a local copy.

### Engine API client (`core/src/main/java/io/github/madeye/meow/api/`)

- **MeowApi.kt**: OkHttp + kotlinx-serialization client for the embedded engine's
  Clash-compatible controller (`http://127.0.0.1:9090`, started by `MeowInstance`).
  Proxies/groups, delay probes, rules, rule providers (`updateRuleProvider` gets its
  own 90 s read timeout: the engine answers only after the download), connections,
  configs, per-proxy traffic (`proxyTraffic`, `GET /statistics`; null on an engine
  without it), and the `/logs` websocket (500ms→30s reconnect ramp). Every call,
  the websocket handshake included, sends `Authorization: Bearer` with the
  secret read from `preference/ApiSecretStore` at call time: `MeowInstance`
  mints a fresh one per engine start, the profile's own `secret:` wins (kept by
  `strip_and_inject` for on-device dashboards), and `:vpn` writes the one the
  engine runs with (`nativeApiSecret`) before the service reports Connected.
  `baseUrl` and `secret` are injectable for tests.
- **MeowApiModels.kt**: `/proxies` is a heterogeneous map discriminated by a field
  *value*, so it is parsed by hand; `ProxyHistory.time` accepts both the Go string
  and Rust `SystemTime` encodings.

### State layer (`core/src/main/java/io/github/madeye/meow/{vpn,repo}/`)

- **vpn/VpnStateRepository.kt**: AIDL callbacks → `StateFlow`. Bound per-Activity.
- **vpn/DailyTrafficRecorder.kt**, **vpn/SpeedSampleStore.kt**: per-day totals and
  the rolling speed window. Both process-scoped so tab switches don't reset them.
- **repo/**: profiles/subscriptions, per-app proxy, traffic history, config validation.
- **subscription/**: `SubscriptionService` downloads configs; `AutoUpdateSchedule` decides
  when a URL subscription is due. `SubscriptionUpdateWorker` (in `:mobile`) is one hourly,
  network-constrained unique periodic WorkManager job, enqueued from `App.onCreate` in the UI
  process only (WorkManager is never initialised in `:vpn`), that refreshes due profiles
  through `ProfileRepository.refresh` — the manual refresh path. Store a download with
  `ProfileDao.storeFetched`, never `update(row)`: writing back the row read before the fetch
  reverts whatever changed meanwhile, e.g. a profile switch (`selected` is per row).
  Local edits (`yamlContent != yamlBackup`) pause auto-update; on a subscription these can
  only be edits saved before configs became read-only, kept until a refresh or revert.
- **update/**: `GitHubReleases` reads the latest GitHub release for Settings' "Check for
  updates". Only the `playRelease` build type sets `BuildConfig.PLAY_STORE`, and there the
  row just opens the Play listing: Play policy allows no other update channel, so keep every
  GitHub/APK path behind that constant (R8 then strips it) and never add
  `REQUEST_INSTALL_PACKAGES`.

### Compose UI (`mobile/src/main/java/io/github/madeye/meow/ui/`)

- **MeowApp.kt**: navigation-compose host. Four tabs as on meow-ios (Home — VPN
  switch, route mode, exit IP —, Proxy Groups, Utility, Settings);
  Subscriptions/Rules (from Home), the YAML editor (from Subscriptions; read-only
  for a subscription, with a banner whose "Create a copy" opens the copy's editor in
  its place), Connections/DNS/Traffic/Statistics/Logs (from Utility) and Per-App Proxy (from
  Settings) are pushed routes. `SubscriptionsRoute` owns the subscription dialogs and
  launchers and answers `clash://install-config` links, which `MeowNavHost`
  routes to it; its ViewModel lives on Home's back-stack entry, so an add or
  refresh survives leaving the page. A profile whose YAML has top-level
  `rule-providers:` gets an "Update rule sets" action (`subscribe/RuleSets.kt`,
  like Surge's "Update External Resources"): while connected it PUTs every HTTP
  rule provider of the running engine, four at a time, and reports the result in
  one snackbar. The Rules page itself only lists rules, `RULE-SET` ones included.
  Sets `testTagsAsResourceId` so `test-e2e.sh` can match on stable resource ids.
- **theme/**: brand tokens ported from meow-ios (`GlassCard.swift`). Fixed palette —
  no Material You dynamic color.
- **components/**: `GlassCard` (the universal surface), `SectionHeader`, `NavRow`,
  `DelayBadge`, `MeowScaffold` (gradient background + transparent app bar).
- **charts/**: hand-drawn `Canvas` charts — 30-day stacked bars with tap-to-select,
  and the live dual-series speed chart.
- **screens/**: one package per screen, each a stateless composable plus a ViewModel.
- Strings live in `res/values/strings.xml` + `values-{zh-rCN,ru,fa,ar,vi,tr,my}` (and
  the same set in `:core` for the notification); `StringsParityTest` fails the build
  if a translation drifts from English, if a `values-xx` locale is missing from
  `localeFilters` (it would be stripped from the APK), or if an RTL locale (fa, ar)
  leaves a `%s` argument outside `\u2068…\u2069` isolates or opens a sentence with a
  Latin word (prefix `\u200F`).
- Text direction follows content (`MeowTypography` sets `TextDirection.Content`);
  numeric readouts use `MeowTextStyles.monoDigits`, which falls back to LTR, so a
  bare `1.2 MB` or IP list is not reordered under an RTL locale.

### Key Data Flow

1. User taps VPN switch → `HomeViewModel` → `VpnService.prepare()` (consent, via `rememberLauncherForActivityResult`) → `startForegroundService(VpnService)` → `MeowInstance.start()` writes config.yaml → JNI `nativeStartEngine()` → Rust starts tokio runtime, tunnel, API server → JNI `nativeStartTun2Socks(vpnService, fd, 1053)` → Rust installs the `SocketProtector` (JNI shim around `VpnService.protect`) into meow-common, then starts the lwip netstack reading from TUN fd.

2. App traffic → TUN → tun2socks intercepts: UDP port 53 → in-process DNS (engine `meow_dns::Resolver` for every qtype); TCP → lwip netstack accepts → `meow_tunnel::tcp::handle_tcp(&inner, NetstackConn(stream), metadata)` → meow routes via rules → proxy adapter (SS/Trojan/Direct) dials via `meow_common::connect_tcp` → installed `SocketProtector` fires `VpnService.protect(fd)` → connect bypasses VPN → remote server.

## Module Dependencies

```
mobile → core (Compose lives only in :mobile; :core stays UI-free)
core → rust (via rust-android-gradle cargo plugin)
meow-android-ffi → meow-{tunnel,config,dns,api,common,transport,proxy} (git dep, tag-pinned, currently v0.24.0)
                   → lwip (patched madeye/lwip rev), jni, android_logger, redb, mimalloc
```

meow-rs crates are pinned by git **tag** in `Cargo.toml` — bumping the engine means changing the tag on every `meow-*` line. The full upstream protocol set is enabled explicitly (mirrors meow-app's `full` bundle): `ss`, `trojan`, `vless` (+`vless-vision`, +`vless-encryption`, REALITY), `vmess`, `snell`, `hysteria2`, `anytls`, `ech-tls-tunnel`, `mux`, `kcptun` (config/proxy) and `tls,ws,grpc,h2,httpupgrade,xhttp,reality` (transport; `tls` is vendored BoringSSL with ECH + uTLS fingerprinting built in). Supported proxy protocols: Shadowsocks (with built-in `simple-obfs` and `v2ray-plugin`), Trojan, VLESS, VMess, Snell, Hysteria2, AnyTLS, Direct.

## E2E Test Structure

`test-e2e.sh` runs 5 tests: tun0 exists, DNS resolution, TCP 1.1.1.1:80, TCP 8.8.8.8:443, HTTP GET to Google generate_204. Uses `ssserver` on host (plain SS, no plugin) plus `python3 -m http.server` on `$SUB_PORT` serving the subscription config; on-device probes use Android's built-in `nc`/`ping` — no external binaries are pushed. Injects the Room database via sqlite3 + `run-as` (under the legacy `mihomo.db` name, doubling as the mihomo→meow migration test), triggers VPN via `am start --ez auto_connect true`, accepts VPN consent dialog via uiautomator. `TUN_WAIT_SECS` bounds the tun0 wait (default 60s).
