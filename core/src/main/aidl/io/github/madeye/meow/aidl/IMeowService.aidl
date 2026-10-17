package io.github.madeye.meow.aidl;
import io.github.madeye.meow.aidl.IMeowServiceCallback;
import io.github.madeye.meow.aidl.SessionStats;
import io.github.madeye.meow.aidl.TrafficStats;

interface IMeowService {
    int getState();
    String getProfileName();
    void registerCallback(in IMeowServiceCallback cb);
    void startListeningForBandwidth(in IMeowServiceCallback cb, long timeout);
    void stopListeningForBandwidth(in IMeowServiceCallback cb);
    void unregisterCallback(in IMeowServiceCallback cb);

    // One proxy-detail probe through the named proxy of the running engine
    // (see the FFI crate's src/probe). Blocks until the probe ends and
    // returns its JSON answer; cancelProxyProbe(id) ends it early.
    String probeProxy(long id, String proxy, String kind, String arg);
    oneway void cancelProxyProbe(long id);

    // Settings' "Built-in DNS" switch, applied to the running engine (see
    // the FFI crate's src/live_dns.rs). Blocks until it is; returns null
    // then or when no VPN runs, else why the engine kept the DNS it had.
    String setBuiltinDns(boolean enabled);

    // The running session's start time and its traffic per kind of network;
    // null while no engine is up.
    SessionStats getSessionStats();
}
