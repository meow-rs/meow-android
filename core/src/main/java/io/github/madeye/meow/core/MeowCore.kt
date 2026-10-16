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
     * [builtinDns] drops the profile's DNS upstreams for the built-in pool.
     */
    external fun nativeStartEngine(addr: String, secret: String, mode: String, builtinDns: Boolean): Int

    /** The running engine's API secret; empty when it is stopped or has none. */
    external fun nativeApiSecret(): String?
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
     * Runs one proxy-detail probe through [proxy] and returns its JSON answer,
     * blocking until it ends; null only when the JVM could not take the
     * answer. [nativeCancelProxyProbe] with the same [id] ends it early.
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
}
