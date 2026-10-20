pub mod adapter;
pub mod hid;
pub mod manager;
pub mod peripheral;

use ::jni::{Env, EnvUnowned, Outcome};
use once_cell::sync::OnceCell;

mod jni;
mod jni_utils;

static GLOBAL_ADAPTER: OnceCell<adapter::Adapter> = OnceCell::new();

pub fn init(env: &mut Env) -> crate::Result<()> {
    self::jni::init(env)?;
    GLOBAL_ADAPTER.get_or_try_init(adapter::Adapter::new)?;
    Ok(())
}

/// Initialize btleplug's Android JNI backend from an existing JNI environment
/// pointer, for integrations that use another version of the `jni` crate.
///
/// # Safety
/// `raw_env` must be a valid, non-null JNI environment pointer for the current
/// thread and must not outlive the Java call frame that owns it.
pub unsafe fn init_from_raw_env(raw_env: *mut ::jni::sys::JNIEnv) -> crate::Result<()> {
    // SAFETY: delegated to this function's caller.
    let mut unowned = unsafe { EnvUnowned::from_raw(raw_env) };
    match unowned.with_env(init).into_outcome() {
        Outcome::Ok(result) => result,
        Outcome::Err(error) => Err(error),
        Outcome::Panic(_) => Err(crate::Error::RuntimeError(
            "panic while initializing btleplug's Android JNI backend".into(),
        )),
    }
}

pub fn global_adapter() -> &'static adapter::Adapter {
    GLOBAL_ADAPTER.get().expect(
        "Droidplug has not been initialized. Please initialize it with btleplug::platform::init().",
    )
}
