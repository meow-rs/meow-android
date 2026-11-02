package io.github.madeye.meow.core

object MeowCore {
    init {
        System.loadLibrary("meow_android_ffi")
        nativeInit()
    }

    external fun nativeInit()
    external fun nativeSetHomeDir(dir: String)
    /**
     * [secret] guards the controller API unless the profile sets its own
     * `secret:`; [nativeApiSecret] says which one the engine runs with.
     * [mode] overrides the config's `mode:` (`rule`/`global`/`direct`); empty keeps it.
     * [builtinDns] drops the profile's DNS upstreams for the built-in pool;
     * [nativeSetBuiltinDns] flips it on the running engine.
     */
    external fun nativeStartEngine(addr: String, secret: String, mode: String, builtinDns: Boolean): Int

    /**
     * Puts the running engine on the built-in pool ([builtinDns]) or the
     * profile's DNS, without a restart. 0 when applied or no engine runs;
     * -1 with [nativeGetLastError] set when the new DNS did not build, and
     * the engine keeps the one it had.
     */
    external fun nativeSetBuiltinDns(builtinDns: Boolean): Int

    /** The running engine's API secret; empty when it is stopped or has none. */
    external fun nativeApiSecret(): String?
    /**
     * Whether the VPN routes IPv6 for the running engine's profile: off only
     * for an explicit `ipv6: false`, as in mihomo. False when it is stopped.
     */
    external fun nativeTunIpv6(): Boolean
    external fun nativeStopEngine()
    external fun nativeStartTun2Socks(vpnService: Any, fd: Int, dnsPort: Int): Int
    external fun nativeIsRunning(): Boolean
    external fun nativeGetUploadTraffic(): Long
    external fun nativeGetDownloadTraffic(): Long
    external fun nativeValidateConfig(yaml: String): Int
    external fun nativeGetLastError(): String
    external fun nativeVersion(): String
    external fun nativeGetLogs(): String
    external fun nativeTestDirectTcp(host: String, port: Int): String
    external fun nativeTestDnsResolver(dnsAddr: String): String

    /**
     * Runs one proxy-detail probe through [proxy] (empty: wherever the
     * engine's routing sends it) and returns its JSON answer, blocking until
     * it ends; null only when the JVM could not take the answer.
     * [nativeCancelProxyProbe] with the same [id] ends it early.
     */
    external fun nativeProbeProxy(id: Long, proxy: String, kind: String, arg: String): String?
    external fun nativeCancelProxyProbe(id: Long)

    /**
     * Where [ip] is registered, from the GeoX databases in [dir] (the engine
     * home): `{"country": "CN", "asn": 136958, "org": "…"}`, leaving out what
     * they do not know. Offline; needs no running engine. Null only when the
     * JVM could not take the answer.
     */
    external fun nativeLookupIp(dir: String, ip: String): String?

    /**
     * A page of the engine's logbook in [dir] (the engine home), newest
     * first: `{"entries": [...], "rev": 7, "more": true}`, or
     * `{"error": "…"}`. [before] pages back from an entry id, [since] polls
     * for what was written after a page's `rev`, 0 leaving either unset;
     * [limit] 0 is the engine's default page; [level] is the lowest level
     * returned, [kind] a kind or its area (`config` matches `config.reload`),
     * empty for all. Read-only and offline: needs no running engine. Null
     * only when the JVM could not take the answer.
     */
    external fun nativeReadLogbook(
        dir: String,
        before: Long,
        since: Long,
        limit: Int,
        level: String,
        kind: String,
    ): String?

    /**
     * Records a `network.change` entry in the engine's logbook: [message]
     * is one English sentence, [fields] a JSON object of its details, and
     * [warning] picks the level over info. Dropped while no engine has
     * started in this process or its profile turned the logbook off.
     */
    external fun nativeRecordNetworkChange(warning: Boolean, message: String, fields: String)

    /**
     * The network the engine's sockets go out on changed to [network]
     * (`Wi-Fi 324`): closes the engine's connections and UDP sessions, drops
     * its pooled DNS upstream connections and resets its proxies' pooled
     * sessions, in the background, and records it as a `network.change`.
     * Returns at once; nothing happens while no engine runs.
     */
    external fun nativeResetOutbound(network: String)
}
