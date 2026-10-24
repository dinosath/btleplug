//! Live identity regression check for an Android system HID peripheral.
//! Make the Android device discoverable, then run on macOS:
//! cargo run --example android_hid_identity -- RP5 22:22:AA:29:73:F2
//! Requires macOS Bluetooth permission. Does not pair, connect, or send input.
#[cfg(target_os = "macos")]
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    use btleplug::{
        api::{Central, Manager as _, Peripheral as _, ScanFilter},
        platform::Manager,
    };
    use objc2::{class, msg_send, rc::Retained, runtime::AnyObject};
    use objc2_foundation::{NSArray, NSString};
    use std::{
        collections::BTreeSet,
        time::{Duration, Instant},
    };
    #[link(name = "IOBluetooth", kind = "framework")]
    unsafe extern "C" {}
    #[link(name = "CoreFoundation", kind = "framework")]
    unsafe extern "C" {
        static kCFRunLoopDefaultMode: *const std::ffi::c_void;
        fn CFRunLoopRunInMode(
            mode: *const std::ffi::c_void,
            seconds: f64,
            return_after_source: bool,
        ) -> i32;
    }
    let args: Vec<_> = std::env::args().collect();
    let name = args
        .get(1)
        .ok_or("Supply the Android device name and public adapter address")?;
    let expected = args
        .get(2)
        .ok_or("Supply the Android public adapter address")?
        .replace('-', ":")
        .to_uppercase();
    // IOBluetooth classic inquiry returns devices actually discovered in this inquiry;
    // system_profiler alone also contains saved/stale identities and is insufficient.
    let inquiry: Retained<AnyObject> = unsafe {
        msg_send![class!(IOBluetoothDeviceInquiry), inquiryWithDelegate: std::ptr::null::<AnyObject>()]
    };
    unsafe {
        let _: () = msg_send![&*inquiry, setInquiryLength: 10u8];
        let result: i32 = msg_send![&*inquiry, start];
        if result != 0 {
            return Err(format!("Classic inquiry failed: {result}").into());
        }
    }
    let deadline = Instant::now() + Duration::from_secs(18);
    while Instant::now() < deadline {
        unsafe {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.1, false);
        }
    }
    let mut classic = BTreeSet::new();
    unsafe {
        let _: i32 = msg_send![&*inquiry, stop];
        let devices: Retained<NSArray<AnyObject>> = msg_send![&*inquiry, foundDevices];
        for device in devices.iter() {
            let found_name: Option<Retained<NSString>> = msg_send![&*device, name];
            if found_name
                .as_ref()
                .is_some_and(|value| value.to_string() == *name)
            {
                let address: Retained<NSString> = msg_send![&*device, addressString];
                classic.insert(address.to_string().replace('-', ":").to_uppercase());
            }
        }
    }
    let manager = tokio::time::timeout(Duration::from_secs(20), Manager::new()).await??;
    let adapters = tokio::time::timeout(Duration::from_secs(20), manager.adapters()).await??;
    let adapter = adapters.first().ok_or("No Bluetooth adapter")?;
    adapter.start_scan(ScanFilter::default()).await?;
    tokio::time::sleep(Duration::from_secs(15)).await;
    let mut ble = BTreeSet::new();
    for peripheral in adapter.peripherals().await? {
        if peripheral
            .properties()
            .await?
            .is_some_and(|p| p.local_name.as_deref() == Some(name))
        {
            ble.insert(format!("{:?}", peripheral.id()));
        }
    }
    adapter.stop_scan().await?;
    println!("Classic identities for {name}: {classic:?}; BLE identities: {ble:?}");
    if classic != BTreeSet::from([expected]) || !ble.is_empty() {
        return Err(
            "Expected exactly the public adapter identity and no extra BLE identity".into(),
        );
    }
    println!("PASS: one public Bluetooth identity, zero additional BLE identities");
    Ok(())
}
#[cfg(not(target_os = "macos"))]
fn main() {
    eprintln!("This live Android HID identity check requires a macOS observer.");
}
