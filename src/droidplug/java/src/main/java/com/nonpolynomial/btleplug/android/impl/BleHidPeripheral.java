package com.nonpolynomial.btleplug.android.impl;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minimal Bluetooth HID over GATT (HOGP) peripheral for mouse and keyboard input. */
@SuppressWarnings("unused")
public final class BleHidPeripheral {
    private static final UUID HID_SERVICE = uuid(0x1812);
    private static final UUID HID_INFORMATION = uuid(0x2A4A);
    private static final UUID REPORT_MAP = uuid(0x2A4B);
    private static final UUID HID_CONTROL_POINT = uuid(0x2A4C);
    private static final UUID REPORT = uuid(0x2A4D);
    private static final UUID REPORT_REFERENCE = uuid(0x2908);
    private static final UUID CLIENT_CONFIGURATION = uuid(0x2902);
    private static final UUID GAP_SERVICE = uuid(0x1800);
    private static final UUID DEVICE_NAME = uuid(0x2A00);
    private static final UUID APPEARANCE = uuid(0x2A01);
    private static final byte[] HID_INFO_VALUE = new byte[] {0x11, 0x01, 0x00, 0x02};
    private static final byte[] REPORT_MAP_VALUE = new byte[] {
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x01, 0x09, 0x01, (byte) 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03,
            (byte) 0x81, 0x06, (byte) 0xC0, (byte) 0xC0,
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x02, 0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00, (byte) 0xC0
    };

    private final Context context;
    private final String deviceName;
    private final Map<String, BluetoothDevice> connectedHosts = new HashMap<>();
    private final Map<String, Set<Integer>> reportSubscriptions = new HashMap<>();
    private BluetoothManager manager;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic mouseReport;
    private BluetoothGattCharacteristic keyboardReport;
    private AdvertiseCallback advertiseCallback;
    private volatile String status = "Stopped";

    public BleHidPeripheral(Context context, String deviceName) {
        this.context = context.getApplicationContext();
        this.deviceName = deviceName == null || deviceName.isEmpty() ? "OpenWASD" : deviceName;
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        if (server != null) return true;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status = "Bluetooth is unavailable or turned off";
            return false;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            status = "This adapter cannot advertise BLE peripherals";
            return false;
        }
        manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            status = "Android Bluetooth service is unavailable";
            return false;
        }
        try {
            server = manager.openGattServer(context, gattCallback);
            if (server == null) {
                status = "Android could not open a GATT server";
                return false;
            }
            if (!server.addService(createGapService())) {
                stop();
                status = "Android could not add the Generic Access service";
                return false;
            }
            status = "Starting BLE HID service";
            return true;
        } catch (SecurityException exception) {
            stop();
            status = "Bluetooth permission is required";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void beginAdvertising() {
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(HID_SERVICE))
                    .build();
            advertiseCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    status = "Advertising BLE HID (" + deviceName + ")";
                }

                @Override public void onStartFailure(int errorCode) {
                    status = "BLE HID advertising failed (" + errorCode + ")";
                }
            };
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException exception) {
            status = "Bluetooth advertising permission is required";
        }
    }

    private BluetoothGattService createGapService() {
        BluetoothGattService gap = new BluetoothGattService(
                GAP_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic name = new BluetoothGattCharacteristic(
                DEVICE_NAME,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        name.setValue(deviceName);
        gap.addCharacteristic(name);
        BluetoothGattCharacteristic appearance = new BluetoothGattCharacteristic(
                APPEARANCE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        // Generic HID appearance, little-endian 0x03C0.
        appearance.setValue(new byte[] {(byte) 0xC0, 0x03});
        gap.addCharacteristic(appearance);
        return gap;
    }

    private BluetoothGattService createHidService() {
        BluetoothGattService service = new BluetoothGattService(
                HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(readCharacteristic(HID_INFORMATION, HID_INFO_VALUE));
        service.addCharacteristic(readCharacteristic(REPORT_MAP, REPORT_MAP_VALUE));

        BluetoothGattCharacteristic controlPoint = new BluetoothGattCharacteristic(
                HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        service.addCharacteristic(controlPoint);

        mouseReport = inputReport(1);
        keyboardReport = inputReport(2);
        service.addCharacteristic(mouseReport);
        service.addCharacteristic(keyboardReport);
        return service;
    }

    private BluetoothGattCharacteristic readCharacteristic(UUID uuid, byte[] value) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        characteristic.setValue(value);
        return characteristic;
    }

    private BluetoothGattCharacteristic inputReport(int reportId) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        characteristic.setValue(reportId == 1 ? new byte[] {0, 0, 0, 0} : new byte[8]);
        BluetoothGattDescriptor reference = new BluetoothGattDescriptor(
                REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED);
        reference.setValue(new byte[] {(byte) reportId, 0x01});
        characteristic.addDescriptor(reference);
        characteristic.addDescriptor(new BluetoothGattDescriptor(
                CLIENT_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED));
        return characteristic;
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int statusCode, BluetoothGattService service) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status = "Android could not register a BLE HID GATT service";
            } else if (GAP_SERVICE.equals(service.getUuid())) {
                synchronized (BleHidPeripheral.this) {
                    if (server != null && !server.addService(createHidService())) {
                        status = "Android could not add the HID service";
                    }
                }
            } else if (HID_SERVICE.equals(service.getUuid())) {
                synchronized (BleHidPeripheral.this) {
                    if (server != null && advertiser != null) beginAdvertising();
                }
            }
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int statusCode, int newState) {
            String address = device.getAddress();
            synchronized (BleHidPeripheral.this) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connectedHosts.put(address, device);
                    status = "Connected to " + safeName(device);
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    connectedHosts.remove(address);
                    reportSubscriptions.remove(address);
                    status = connectedHosts.isEmpty() ? "Advertising BLE HID as " + deviceName
                            : "Connected to " + connectedHosts.size() + " host(s)";
                }
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null || offset > value.length) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));
            }
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattDescriptor descriptor) {
            byte[] value = descriptor.getValue();
            if (value == null) value = new byte[] {0, 0};
            if (offset > value.length) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));
            }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (CLIENT_CONFIGURATION.equals(descriptor.getUuid())) {
                descriptor.setValue(value);
                int reportId = reportId(descriptor.getCharacteristic());
                synchronized (BleHidPeripheral.this) {
                    Set<Integer> subscribed = reportSubscriptions.computeIfAbsent(
                            device.getAddress(), ignored -> new HashSet<>());
                    if (reportId != 0 && value != null && value.length >= 2 && (value[0] & 0x01) != 0) {
                        subscribed.add(reportId);
                    } else if (reportId != 0) {
                        subscribed.remove(reportId);
                    }
                }
            }
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    @SuppressLint("MissingPermission")
    public boolean sendMouseReport(byte buttons, byte dx, byte dy, byte wheel) {
        return notifyHosts(mouseReport, new byte[] {buttons, dx, dy, wheel});
    }

    public boolean sendKeyboardReport(byte[] report) {
        if (report == null || report.length != 8) return false;
        return notifyHosts(keyboardReport, report.clone());
    }

    @SuppressLint("MissingPermission")
    private synchronized boolean notifyHosts(BluetoothGattCharacteristic characteristic, byte[] report) {
        if (server == null || characteristic == null || connectedHosts.isEmpty()) return false;
        boolean sent = false;
        int reportId = reportId(characteristic);
        for (Map.Entry<String, BluetoothDevice> entry : connectedHosts.entrySet()) {
            if (!reportSubscriptions.getOrDefault(entry.getKey(), new HashSet<>()).contains(reportId)) continue;
            try {
                int result;
                if (Build.VERSION.SDK_INT >= 33) {
                    result = server.notifyCharacteristicChanged(entry.getValue(), characteristic, false, report);
                } else {
                    characteristic.setValue(report);
                    sent |= server.notifyCharacteristicChanged(entry.getValue(), characteristic, false);
                    continue;
                }
                sent |= result == BluetoothStatusCodes.SUCCESS;
            } catch (SecurityException exception) {
                status = "Bluetooth connect permission is required";
                return false;
            }
        }
        return sent;
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        if (advertiser != null && advertiseCallback != null) {
            try { advertiser.stopAdvertising(advertiseCallback); }
            catch (SecurityException ignored) { }
        }
        advertiseCallback = null;
        if (server != null) server.close();
        server = null;
        advertiser = null;
        connectedHosts.clear();
        reportSubscriptions.clear();
        status = "Stopped";
    }

    public String getStatus() { return status; }

    private int reportId(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return 0;
        BluetoothGattDescriptor reference = characteristic.getDescriptor(REPORT_REFERENCE);
        byte[] value = reference == null ? null : reference.getValue();
        return value == null || value.length < 2 ? 0 : value[0] & 0xff;
    }

    @SuppressLint("MissingPermission")
    public synchronized String getConnectedDeviceName() {
        if (connectedHosts.isEmpty()) return null;
        return safeName(connectedHosts.values().iterator().next());
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException ignored) {
            return "Bluetooth host";
        }
    }

    private static UUID uuid(int shortUuid) {
        return UUID.fromString(String.format(java.util.Locale.ROOT,
                "0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
    }
}
