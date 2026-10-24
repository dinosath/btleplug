//! Android Bluetooth HID peripheral support (system HID and BLE GATT).
//!
//! This exposes the Android GATT-server implementation bundled with btleplug.
//! It is intentionally Android-only: btleplug's existing cross-platform API is
//! for BLE central/client operations, and other platforms need separate GATT
//! server backends before they can offer the same HID peripheral API.

use super::jni::jvm;
use crate::Result;
use jni::{
    Env, jni_sig, jni_str,
    objects::{Global, JObject, JString},
};
use std::{fmt, sync::Arc};

/// A Bluetooth LE HID-over-GATT mouse, keyboard, and gamepad peripheral on Android 9+ (API 28).
///
/// The Android host must grant `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE`
/// permissions on Android 12 (API 31) and newer. The device must support BLE
/// peripheral advertising. Call [`crate::platform::init`] before constructing
/// this type so btleplug's Android JNI support is initialized.
#[derive(Clone)]
pub struct BleHidPeripheral {
    internal: Arc<Global<JObject<'static>>>,
}

impl fmt::Debug for BleHidPeripheral {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("BleHidPeripheral").finish_non_exhaustive()
    }
}

impl BleHidPeripheral {
    /// Construct a peripheral using the current Android application context.
    pub fn new(env: &mut Env<'_>, context: JObject<'_>, device_name: &str) -> Result<Self> {
        let name = env.new_string(device_name)?;
        let class = jni::objects::LoaderContext::default().load_class(
            env,
            jni_str!("com.nonpolynomial.btleplug.android.impl.BleHidPeripheral"),
            false,
        )?;
        let object = env.new_object(
            &class,
            jni_sig!("(Landroid/content/Context;Ljava/lang/String;)V"),
            &[(&context).into(), (&name).into()],
        )?;
        Ok(Self {
            internal: Arc::new(env.new_global_ref(&object)?),
        })
    }

    /// Construct from an Android context supplied by an integration using a
    /// different JNI wrapper version than btleplug.
    ///
    /// # Safety
    /// `context` must be a live local or global JNI reference on the current
    /// thread, and must remain valid for the duration of this call. Call
    /// [`crate::platform::init_from_raw_env`] first on that thread or an earlier
    /// thread in the same Android process.
    pub unsafe fn new_from_raw_context(
        context: jni::sys::jobject,
        device_name: &str,
    ) -> Result<Self> {
        jvm()?.attach_current_thread(|env| {
            // SAFETY: guaranteed by this method's contract; the reference is
            // used synchronously and promoted to a global ref by `new`.
            let context = unsafe { JObject::from_raw(env, context) };
            Self::new(env, context, device_name)
        })
    }

    /// Start the HID GATT service and begin advertising after Android registers it.
    pub fn start(&self) -> Result<bool> {
        jvm()?.attach_current_thread(|env| {
            Ok(env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("start"),
                    jni_sig!("()Z"),
                    &[],
                )?
                .z()?)
        })
    }

    /// Stop advertising and close the GATT server.
    pub fn stop(&self) -> Result<()> {
        jvm()?.attach_current_thread(|env| {
            env.call_method(
                self.internal.as_obj(),
                jni_str!("stop"),
                jni_sig!("()V"),
                &[],
            )?;
            Ok(())
        })
    }

    /// Send a relative mouse movement and button state.
    ///
    /// `buttons` uses bit 0 for left, bit 1 for right, and bit 2 for middle;
    /// movement and wheel deltas are signed 8-bit relative values.
    pub fn send_mouse_report(&self, buttons: i8, dx: i8, dy: i8, wheel: i8) -> Result<bool> {
        jvm()?.attach_current_thread(|env| {
            Ok(env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("sendMouseReport"),
                    jni_sig!("(BBBB)Z"),
                    &[buttons.into(), dx.into(), dy.into(), wheel.into()],
                )?
                .z()?)
        })
    }

    /// Send an eight-byte boot-format keyboard report.
    pub fn send_keyboard_report(&self, report: [u8; 8]) -> Result<bool> {
        jvm()?.attach_current_thread(|env| {
            let array = env.new_byte_array(report.len())?;
            let bytes: Vec<_> = report.into_iter().map(|byte| byte as i8).collect();
            array.set_region(env, 0, &bytes)?;
            Ok(env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("sendKeyboardReport"),
                    jni_sig!("([B)Z"),
                    &[(&array).into()],
                )?
                .z()?)
        })
    }

    /// Send a seven-byte gamepad report (16 buttons, hat switch, and four axes).
    pub fn send_gamepad_report(&self, report: [u8; 7]) -> Result<bool> {
        jvm()?.attach_current_thread(|env| {
            let array = env.new_byte_array(report.len())?;
            let bytes: Vec<_> = report.into_iter().map(|byte| byte as i8).collect();
            array.set_region(env, 0, &bytes)?;
            Ok(env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("sendGamepadReport"),
                    jni_sig!("([B)Z"),
                    &[(&array).into()],
                )?
                .z()?)
        })
    }

    /// Return the current advertising/connection state reported by Android.
    pub fn status(&self) -> Result<String> {
        jvm()?.attach_current_thread(|env| {
            let value = env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("getStatus"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let value = env.cast_local::<JString>(value)?;
            Ok(value.try_to_string(env)?)
        })
    }

    /// Return subscribed report bits for the first connected host (mouse=1, keyboard=2, gamepad=4).
    pub fn connected_host_report_mask(&self) -> Result<u8> {
        jvm()?.attach_current_thread(|env| {
            Ok(env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("getConnectedReportMask"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()? as u8)
        })
    }

    /// Return the name of the first connected host, if one is connected.
    pub fn connected_host_name(&self) -> Result<Option<String>> {
        jvm()?.attach_current_thread(|env| {
            let value = env
                .call_method(
                    self.internal.as_obj(),
                    jni_str!("getConnectedDeviceName"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            if value.is_null() {
                return Ok(None);
            }
            let value = env.cast_local::<JString>(value)?;
            Ok(Some(value.try_to_string(env)?))
        })
    }
}

/// Android system Bluetooth HID profile (API 28+). Uses the existing adapter
/// identity instead of a separate BLE advertiser. Android owns report routing.
/// Requires BLUETOOTH_CONNECT on Android 12+. Keep the application in the foreground.
/// Pair using the system Bluetooth settings. Previously cached BLE identities are
/// separate host records; this profile cannot remove them from a remote host.
#[derive(Clone, Debug)]
pub struct HidPeripheral(BleHidPeripheral);

impl HidPeripheral {
    /// # Safety
    /// Same context lifetime and thread requirements as `BleHidPeripheral::new_from_raw_context`.
    pub unsafe fn new_from_raw_context(
        context: jni::sys::jobject,
        device_name: &str,
    ) -> Result<Self> {
        Ok(Self(unsafe {
            BleHidPeripheral::new_from_raw_context(context, device_name)?
        }))
    }
    pub fn start(&self) -> Result<bool> {
        jvm()?.attach_current_thread(|env| {
            Ok(env
                .call_method(
                    self.0.internal.as_obj(),
                    jni_str!("startSystemHid"),
                    jni_sig!("()Z"),
                    &[],
                )?
                .z()?)
        })
    }
    pub fn stop(&self) -> Result<()> {
        self.0.stop()
    }
    pub fn status(&self) -> Result<String> {
        self.0.status()
    }
    pub fn connected_host_name(&self) -> Result<Option<String>> {
        self.0.connected_host_name()
    }
    pub fn connected_host_report_mask(&self) -> Result<u8> {
        self.0.connected_host_report_mask()
    }
    pub fn send_mouse_report(&self, buttons: i8, dx: i8, dy: i8, wheel: i8) -> Result<bool> {
        self.0.send_mouse_report(buttons, dx, dy, wheel)
    }
    pub fn send_keyboard_report(&self, report: [u8; 8]) -> Result<bool> {
        self.0.send_keyboard_report(report)
    }
    pub fn send_gamepad_report(&self, report: [u8; 7]) -> Result<bool> {
        self.0.send_gamepad_report(report)
    }
}

// Native callbacks use Rust storage so subscription lifecycle policy stays outside Java.
pub(super) fn load_subscriptions<'local>(
    env: &mut Env<'local>,
    _class: jni::objects::JClass<'local>,
    root: JString<'local>,
    address: JString<'local>,
    bonded: jni::sys::jboolean,
) -> jni::errors::Result<jni::sys::jint> {
    let root = root.try_to_string(env)?;
    let address = address.try_to_string(env)?;
    Ok(
        match crate::hid_subscriptions::load(std::path::Path::new(&root), &address, bonded) {
            Ok(mask) => i32::from(mask),
            Err(error) => {
                log::warn!("Could not restore HID subscriptions: {error}");
                0
            }
        },
    )
}

pub(super) fn save_subscriptions<'local>(
    env: &mut Env<'local>,
    _class: jni::objects::JClass<'local>,
    root: JString<'local>,
    address: JString<'local>,
    bonded: jni::sys::jboolean,
    mask: jni::sys::jint,
) -> jni::errors::Result<jni::sys::jboolean> {
    let root = root.try_to_string(env)?;
    let address = address.try_to_string(env)?;
    let Ok(mask) = u8::try_from(mask) else {
        return Ok(false);
    };
    Ok(
        match crate::hid_subscriptions::save(std::path::Path::new(&root), &address, bonded, mask) {
            Ok(()) => true,
            Err(error) => {
                log::warn!("Could not persist HID subscriptions: {error}");
                false
            }
        },
    )
}

pub(super) fn system_hid_transition<'local>(
    _env: &mut Env<'local>,
    _class: jni::objects::JClass<'local>,
    state: jni::sys::jint,
    event: jni::sys::jint,
) -> jni::errors::Result<jni::sys::jint> {
    Ok(crate::hid_lifecycle::transition(state, event))
}
