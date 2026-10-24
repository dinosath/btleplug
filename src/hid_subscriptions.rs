//! Bonded GATT CCCDs belong to each client and survive server/process restarts.
use std::{
    fs, io,
    path::{Path, PathBuf},
};

fn path(root: &Path, address: &str) -> io::Result<PathBuf> {
    let octets: Vec<_> = address.split(':').collect();
    if octets.len() != 6
        || octets
            .iter()
            .any(|o| o.len() != 2 || !o.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid Bluetooth address",
        ));
    }
    Ok(root
        .join("btleplug-hid-cccd-v1")
        .join(address.replace(':', "").to_ascii_uppercase()))
}

pub(crate) fn load(root: &Path, address: &str, bonded: bool) -> io::Result<u8> {
    let file = path(root, address)?;
    if !bonded {
        match fs::remove_file(file) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
        return Ok(0);
    }
    match fs::read(&file) {
        Ok(bytes) if bytes.len() == 1 && bytes[0] & !3 == 0 => Ok(bytes[0]),
        Ok(_) => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid HID subscriptions",
        )),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(0),
        Err(e) => Err(e),
    }
}

pub(crate) fn save(root: &Path, address: &str, bonded: bool, mask: u8) -> io::Result<()> {
    if mask & !3 != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unknown HID report",
        ));
    }
    if !bonded {
        return load(root, address, false).map(|_| ());
    }
    let file = path(root, address)?;
    fs::create_dir_all(file.parent().unwrap())?;
    // Callbacks are serialized by the peripheral lock. Rename publishes a complete record.
    let pending = file.with_extension("tmp");
    fs::write(&pending, [mask])?;
    fs::File::open(&pending)?.sync_all()?;
    fs::rename(pending, file)
}

#[cfg(test)]
mod tests {
    use super::*;
    const HOST: &str = "01:23:45:67:89:AB";
    struct Storage(PathBuf);
    impl Storage {
        fn new() -> Self {
            static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
            Self(std::env::temp_dir().join(format!(
                "btleplug-hid-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            )))
        }
    }
    impl Drop for Storage {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    #[test]
    fn bonded_reports_survive_new_server_and_disabled_reports_stay_disabled() {
        let dir = Storage::new();
        assert_eq!(load(&dir.0, HOST, true).unwrap(), 0);
        save(&dir.0, HOST, true, 3).unwrap();
        assert_eq!(load(&dir.0, HOST, true).unwrap(), 3);
        save(&dir.0, HOST, true, 2).unwrap();
        assert_eq!(load(&dir.0, HOST, true).unwrap(), 2);
        save(&dir.0, HOST, true, 0).unwrap();
        assert_eq!(load(&dir.0, HOST, true).unwrap(), 0);
    }
    #[test]
    fn subscriptions_do_not_leak_between_clients_or_after_unpairing() {
        let dir = Storage::new();
        save(&dir.0, HOST, true, 3).unwrap();
        assert_eq!(load(&dir.0, "AA:BB:CC:DD:EE:FF", true).unwrap(), 0);
        assert_eq!(load(&dir.0, HOST, false).unwrap(), 0);
        assert_eq!(load(&dir.0, HOST, true).unwrap(), 0);
        save(&dir.0, HOST, false, 3).unwrap();
        assert_eq!(load(&dir.0, HOST, true).unwrap(), 0);
    }
    #[test]
    fn invalid_state_cannot_enable_reports_or_escape_storage() {
        let dir = Storage::new();
        assert!(save(&dir.0, HOST, true, 4).is_err());
        assert!(load(&dir.0, "../../outside", true).is_err());
        let file = path(&dir.0, HOST).unwrap();
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(file, [255]).unwrap();
        assert!(load(&dir.0, HOST, true).is_err());
    }
}
