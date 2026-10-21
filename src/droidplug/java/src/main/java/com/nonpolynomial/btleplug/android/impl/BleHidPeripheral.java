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
import android.util.Log;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minimal Bluetooth HID over GATT (HOGP) peripheral for mouse and keyboard input. */
@SuppressWarnings("unused")
public final class BleHidPeripheral {
    private static final String LOG_TAG = "OpenWASD-HID";
    private static final UUID HID_SERVICE = uuid(0x1812);
    private static final UUID DEVICE_INFORMATION_SERVICE = uuid(0x180A);
    private static final UUID BATTERY_SERVICE = uuid(0x180F);
    private static final UUID MANUFACTURER_NAME = uuid(0x2A29);
    private static final UUID MODEL_NUMBER = uuid(0x2A24);
    private static final UUID BATTERY_LEVEL = uuid(0x2A19);
    private static final UUID HID_INFORMATION = uuid(0x2A4A);
    private static final UUID REPORT_MAP = uuid(0x2A4B);
    private static final UUID HID_CONTROL_POINT = uuid(0x2A4C);
    private static final UUID REPORT = uuid(0x2A4D);
    private static final UUID PROTOCOL_MODE = uuid(0x2A4E);
    private static final UUID REPORT_REFERENCE = uuid(0x2908);
    private static final UUID CLIENT_CONFIGURATION = uuid(0x2902);
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
    private final Map<String, Deque<String>> pendingReports = new HashMap<>();
    private BluetoothManager manager;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic mouseReport;
    private BluetoothGattCharacteristic keyboardReport;
    private AdvertiseCallback advertiseCallback;
    // Android's service-registration callbacks can race with host disconnect
    // callbacks. Keep one advertiser request in flight at a time.
    private boolean advertising;
    private boolean hidServiceReady;
    private final List<BluetoothGattService> startupServices = new ArrayList<>();
    private int nextStartupService;
    private volatile String status = "Stopped";
    private String lastGattEvent = "no GATT requests observed";

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
            hidServiceReady = false;
            server = manager.openGattServer(context, gattCallback);
            if (server == null) {
                status = "Android could not open a GATT server";
                return false;
            }
            // Android's Bluetooth stack owns Generic Access (0x1800) for a
            // local GATT server. Adding a second GAP service fails with
            // GATT_FAILURE and prevents the HID service from being registered.
            startupServices.clear();
            // HOGP requires Device Information and Battery services alongside
            // HID. Some hosts reject or ignore a HID-only GATT database.
            startupServices.add(createHidService());
            startupServices.add(createDeviceInformationService());
            startupServices.add(createBatteryService());
            nextStartupService = 0;
            if (!server.addService(startupServices.get(nextStartupService))) {
                stop();
                status = "Android could not add a required BLE HID service";
                return false;
            }
            status = "Registering BLE HID service";
            return true;
        } catch (SecurityException exception) {
            stop();
            status = "Bluetooth permission is required";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void beginAdvertising() {
        if (!hidServiceReady || server == null || advertiser == null) {
            Log.i(LOG_TAG, "Skipping BLE HID advertising until the HID service is ready");
            return;
        }
        if (advertising || advertiseCallback != null) {
            Log.i(LOG_TAG, "Skipping duplicate BLE HID advertising request; already active or starting");
            return;
        }
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    // This runs while the control surface is foregrounded. Use
                    // the fastest discovery interval so a host can find and
                    // connect to the HID peripheral promptly.
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(HID_SERVICE))
                    .build();
            // Put the Android device's local Bluetooth name in scan response
            // data. Without it macOS can miss or show an anonymous HID
            // peripheral in its Bluetooth device picker.
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            AdvertiseCallback callback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    synchronized (BleHidPeripheral.this) {
                        if (advertiseCallback != this) {
                            Log.i(LOG_TAG, "Ignoring success from stale BLE HID advertising request");
                            return;
                        }
                        Log.i(LOG_TAG, "BLE HID advertising started; name=" + deviceName);
                        refreshConnectionStatus();
                    }
                }

                @Override public void onStartFailure(int errorCode) {
                    synchronized (BleHidPeripheral.this) {
                        if (advertiseCallback != this) return;
                        advertising = false;
                        advertiseCallback = null;
                        status = "BLE HID advertising failed (" + errorCode + ")";
                        Log.e(LOG_TAG, "BLE HID advertising failed; error=" + errorCode);
                    }
                }
            };
            advertiseCallback = callback;
            // Mark the request before calling into Android because callbacks
            // may be delivered immediately on some Bluetooth stack versions.
            advertising = true;
            Log.i(LOG_TAG, "Starting connectable BLE HID advertising; "
                    + "mode=low_latency tx=high scan_response_name=true");
            advertiser.startAdvertising(settings, data, scanResponse, callback);
        } catch (SecurityException exception) {
            advertising = false;
            advertiseCallback = null;
            status = "Bluetooth advertising permission is required";
        }
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

        // HOGP hosts (including macOS) use Protocol Mode to select the Report
        // protocol. A keyboard/mouse that only exposes Report characteristics
        // may pair but never enable their notifications on some hosts.
        BluetoothGattCharacteristic protocolMode = new BluetoothGattCharacteristic(
                PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        protocolMode.setValue(new byte[] {0x01}); // Report Protocol
        service.addCharacteristic(protocolMode);

        mouseReport = inputReport(1);
        keyboardReport = inputReport(2);
        service.addCharacteristic(mouseReport);
        service.addCharacteristic(keyboardReport);
        return service;
    }

    private BluetoothGattService createDeviceInformationService() {
        BluetoothGattService service = new BluetoothGattService(
                DEVICE_INFORMATION_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(publicReadCharacteristic(
                MANUFACTURER_NAME, "OpenWASD".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.addCharacteristic(publicReadCharacteristic(
                MODEL_NUMBER, "BLE HID Controller".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return service;
    }

    private BluetoothGattService createBatteryService() {
        BluetoothGattService service = new BluetoothGattService(
                BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(publicReadCharacteristic(BATTERY_LEVEL, new byte[] {100}));
        return service;
    }

    private BluetoothGattCharacteristic publicReadCharacteristic(UUID uuid, byte[] value) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        characteristic.setValue(value);
        return characteristic;
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
        @Override public void onNotificationSent(BluetoothDevice device, int statusCode) {
            String key = device.getAddress();
            String report;
            synchronized (BleHidPeripheral.this) {
                Deque<String> queued = pendingReports.get(key);
                report = queued == null ? "unknown report" : queued.pollFirst();
                if (queued != null && queued.isEmpty()) pendingReports.remove(key);
            }
            Log.d(LOG_TAG, "HID BLE report acknowledged; host=" + safeName(device)
                    + " report=" + report + " status=" + statusCode);
        }

        @Override public void onServiceAdded(int statusCode, BluetoothGattService service) {
            Log.i(LOG_TAG, "GATT service added; uuid=" + service.getUuid() + " status=" + statusCode);
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status = "Android could not register a BLE HID service (GATT status "
                        + statusCode + ")";
                stop();
                status = "Android could not register a required BLE HID service (GATT status "
                        + statusCode + ")";
                return;
            }
            synchronized (BleHidPeripheral.this) {
                if (server == null) return;
                nextStartupService++;
                if (nextStartupService < startupServices.size()) {
                    BluetoothGattService next = startupServices.get(nextStartupService);
                    if (!server.addService(next)) {
                        Log.e(LOG_TAG, "Could not queue required GATT service " + next.getUuid());
                        stop();
                        status = "Android could not register a required BLE HID service";
                    }
                    return;
                }
                if (nextStartupService >= startupServices.size()) {
                    hidServiceReady = true;
                    Log.i(LOG_TAG, "Required HOGP services registered; HID GATT database is ready");
                }
                if (hidServiceReady && server != null && advertiser != null) {
                    if (connectedHosts.isEmpty()) {
                        beginAdvertising();
                    } else {
                        refreshConnectionStatus();
                    }
                }
            }
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int statusCode, int newState) {
            String address = device.getAddress();
            Log.i(LOG_TAG, "GATT host connection changed; host=" + safeName(device)
                    + " address=" + address + " status=" + statusCode + " state=" + newState);
            synchronized (BleHidPeripheral.this) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connectedHosts.put(address, device);
                    if (!hidServiceReady) {
                        // Bonded hosts can reconnect as soon as Android opens
                        // the GATT server, a few milliseconds before its
                        // services are registered. Keep that link alive while
                        // addService callbacks finish so the host discovers
                        // the complete HID table rather than caching an empty
                        // database after an immediate disconnect.
                        status = "Connected to " + safeName(device)
                                + "; registering BLE HID services";
                        Log.i(LOG_TAG, "Keeping bonded host connected while HID services register; host="
                                + safeName(device));
                    } else {
                        // Avoid showing both the paired device and its active
                        // BLE advertisement as separate RP5 entries on macOS.
                        stopAdvertising();
                    }
                    refreshConnectionStatus();
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    connectedHosts.remove(address);
                    reportSubscriptions.remove(address);
                    pendingReports.remove(address);
                    if (connectedHosts.isEmpty() && hidServiceReady && server != null && advertiser != null) {
                        beginAdvertising();
                    }
                    refreshConnectionStatus();
                }
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            Log.i(LOG_TAG, "GATT characteristic read; host=" + safeName(device)
                    + " uuid=" + characteristic.getUuid() + " offset=" + offset);
            synchronized (BleHidPeripheral.this) {
                lastGattEvent = "read characteristic " + characteristic.getUuid();
                refreshConnectionStatus();
            }
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
            synchronized (BleHidPeripheral.this) {
                lastGattEvent = "write characteristic " + characteristic.getUuid()
                        + " value=" + bytesToHex(value)
                        + " responseNeeded=" + responseNeeded;
                refreshConnectionStatus();
            }
            Log.i(LOG_TAG, "GATT characteristic write; host=" + safeName(device)
                    + " uuid=" + characteristic.getUuid() + " value=" + bytesToHex(value));
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattDescriptor descriptor) {
            byte[] value = descriptor.getValue();
            if (value == null) value = new byte[] {0, 0};
            Log.i(LOG_TAG, "GATT descriptor read; host=" + safeName(device)
                    + " uuid=" + descriptor.getUuid() + " offset=" + offset
                    + " value=" + bytesToHex(value));
            synchronized (BleHidPeripheral.this) {
                lastGattEvent = "read descriptor " + descriptor.getUuid()
                        + " value=" + bytesToHex(value);
                refreshConnectionStatus();
            }
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
                BluetoothGattCharacteristic parent = descriptor.getCharacteristic();
                int reportId = reportId(descriptor.getCharacteristic());
                boolean enabled = value != null && value.length >= 2 && (value[0] & 0x01) != 0;
                synchronized (BleHidPeripheral.this) {
                    lastGattEvent = "write CCCD reportId=" + reportId
                            + " value=" + bytesToHex(value)
                            + " responseNeeded=" + responseNeeded;
                    Set<Integer> subscribed = reportSubscriptions.computeIfAbsent(
                            device.getAddress(), ignored -> new HashSet<>());
                    if (reportId != 0 && enabled) {
                        subscribed.add(reportId);
                    } else if (reportId != 0) {
                        subscribed.remove(reportId);
                    }
                    refreshConnectionStatus();
                }
                Log.i(LOG_TAG, "HID report subscription changed; host=" + safeName(device)
                        + " reportId=" + reportId + " enabled=" + enabled);
            } else {
                synchronized (BleHidPeripheral.this) {
                    lastGattEvent = "write descriptor " + descriptor.getUuid()
                            + " value=" + bytesToHex(value)
                            + " responseNeeded=" + responseNeeded;
                    refreshConnectionStatus();
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
                    if (result == BluetoothStatusCodes.SUCCESS) {
                        queueReport(entry.getKey(), reportId, report);
                        Log.d(LOG_TAG, "HID BLE report queued; host=" + safeName(entry.getValue())
                                + " reportId=" + reportId + " payload=" + bytesToHex(report));
                    }
                } else {
                    characteristic.setValue(report);
                    boolean accepted = server.notifyCharacteristicChanged(entry.getValue(), characteristic, false);
                    sent |= accepted;
                    if (accepted) {
                        queueReport(entry.getKey(), reportId, report);
                        Log.d(LOG_TAG, "HID BLE report queued; host=" + safeName(entry.getValue())
                                + " reportId=" + reportId + " payload=" + bytesToHex(report));
                    }
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

    private void queueReport(String host, int reportId, byte[] report) {
        pendingReports.computeIfAbsent(host, ignored -> new ArrayDeque<>())
                .addLast("reportId=" + reportId + " payload=" + bytesToHex(report));
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        stopAdvertising();
        if (server != null) server.close();
        server = null;
        advertiser = null;
        connectedHosts.clear();
        reportSubscriptions.clear();
        pendingReports.clear();
        hidServiceReady = false;
        startupServices.clear();
        nextStartupService = 0;
        status = "Stopped";
    }

    @SuppressLint("MissingPermission")
    private synchronized void stopAdvertising() {
        AdvertiseCallback callback = advertiseCallback;
        advertiseCallback = null;
        advertising = false;
        if (advertiser != null && callback != null) {
            try { advertiser.stopAdvertising(callback); }
            catch (SecurityException ignored) { }
            Log.i(LOG_TAG, "Stopped BLE HID advertising while host is connected");
        }
    }

    public String getStatus() { return status; }

    private void refreshConnectionStatus() {
        if (connectedHosts.isEmpty()) {
            status = "Advertising BLE HID as " + deviceName;
            return;
        }
        Map.Entry<String, BluetoothDevice> host = connectedHosts.entrySet().iterator().next();
        Set<Integer> reports = reportSubscriptions.getOrDefault(host.getKey(), new HashSet<>());
        status = "Connected to " + safeName(host.getValue()) + "; subscribed HID report IDs="
                + reports + "; last GATT event=" + lastGattEvent;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format("%02X", value & 0xFF));
        }
        return result.toString();
    }

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
