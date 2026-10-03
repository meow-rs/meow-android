use log::info;

static INIT: std::sync::Once = std::sync::Once::new();

/// Initialize android_logger. Safe to call multiple times.
pub fn init_android_logger() {
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("meow-ffi"),
        );
        // stderr goes nowhere on Android, and a panic that reaches a JNI entry
        // point aborts the process: without this a crash leaves only a
        // tombstone, never the panic message.
        std::panic::set_hook(Box::new(|info| {
            log::error!(
                "panic: {info}\n{}",
                std::backtrace::Backtrace::force_capture()
            );
        }));
    });
}

pub fn bridge_log(msg: &str) {
    info!("{}", msg);
}
