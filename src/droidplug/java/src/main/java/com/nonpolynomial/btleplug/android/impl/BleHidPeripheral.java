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
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.content.Intent;
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

/** Minimal Bluetooth HID over GATT (HOGP) peripheral for mouse, keyboard, and gamepad input. */
@SuppressWarnings({"unused", "deprecation"}) // GATT compatibility APIs support the configured minimum Android version.
public final class BleHidPeripheral extends BluetoothHidDevice.Callback
        implements BluetoothProfile.ServiceListener {
    private static final String LOG_TAG = "OpenWASD-HID";
    private static final UUID HID_SERVICE = uuid(0x1812);
    private static final UUID DEVICE_INFORMATION_SERVICE = uuid(0x180A);
    private static final UUID BATTERY_SERVICE = uuid(0x180F);
    private static final UUID MANUFACTURER_NAME = uuid(0x2A29);
    private static final UUID MODEL_NUMBER = uuid(0x2A24);
    private static final UUID PNP_ID = uuid(0x2A50);
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
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00, (byte) 0xC0,
            0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x03, 0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x10, (byte) 0x81, 0x02,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01,
            0x65, 0x14, 0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x42,
            0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x04, (byte) 0x81, 0x02,
            (byte) 0xC0
    };
    private static native int loadSubscriptions(String root, String address, boolean bonded);
    private static native boolean saveSubscriptions(String root, String address, boolean bonded, int mask);
    private BluetoothHidDevice systemDevice;
    private BluetoothDevice systemHost;
    private BluetoothDevice connectingHost;
    private final List<BluetoothDevice> systemHostCandidates = new ArrayList<>();
    private int nextSystemHostCandidate;
    // Lifecycle policy is implemented and regression tested in Rust (hid_lifecycle.rs).
    private static native int systemHidTransition(int state, int event);
    private int systemState; // stopped=0, waiting proxy=1, registering=2, ready=3, suspended=4
    private boolean discoverabilityRequested;
    private BluetoothAdapter systemAdapter;

    /** Android's native HID profile shares the adapter identity; no private BLE advertiser. */
    @SuppressLint("MissingPermission")
    public synchronized boolean startSystemHid() {
        if (systemState != 0) {
            if (systemState == 4 && systemDevice != null) {
                systemState = systemHidTransition(systemState, 4);
                registerSystemApp();
            }
            return true;
        }
        if (Build.VERSION.SDK_INT < 28) {
            status = "Android system HID requires Android 9 or newer";
            return false;
        }
        stop();
        systemAdapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (systemAdapter == null || !systemAdapter.isEnabled()) {
            status = "Bluetooth is unavailable or turned off";
            return false;
        }
        systemState = systemHidTransition(systemState, 0);
        status = "Registering system HID service";
        Log.i(LOG_TAG, "System HID session starting; no BLE advertiser");
        if (!systemAdapter.getProfileProxy(context, this, BluetoothProfile.HID_DEVICE)) {
            systemState = systemHidTransition(systemState, 5);
            status = "Android system HID profile is unavailable";
            return false;
        }
        return true;
    }

    @Override @SuppressLint("MissingPermission")
    public synchronized void onServiceConnected(int profile, BluetoothProfile proxy) {
        if (profile != BluetoothProfile.HID_DEVICE) return;
        Log.d(LOG_TAG, "System HID proxy ready; state=" + systemState);
        if (systemState == 0) {
            if (systemAdapter != null) systemAdapter.closeProfileProxy(profile, proxy);
            return;
        }
        if (systemState != 1) return; // Ignore repeated proxy callbacks.
        systemDevice = (BluetoothHidDevice) proxy;
        systemState = systemHidTransition(systemState, 1);
        registerSystemApp();
    }

    @SuppressLint("MissingPermission")
    private void registerSystemApp() {
        status = "Registering system HID service";
        BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                deviceName, "OpenWASD gamepad, mouse, and keyboard", "OpenWASD",
                BluetoothHidDevice.SUBCLASS1_COMBO, REPORT_MAP_VALUE);
        boolean accepted = systemDevice.registerApp(settings, null, null, context.getMainExecutor(), this);
        Log.d(LOG_TAG, "System HID registration requested; state=" + systemState + " accepted=" + accepted);
        if (!accepted) {
            systemState = systemHidTransition(systemState, 3);
            status = "Android rejected system HID registration";
        }
    }

    @Override
    public synchronized void onServiceDisconnected(int profile) {
        if (profile != BluetoothProfile.HID_DEVICE) return;
        systemDevice = null;
        systemHost = null;
        connectingHost = null;
        systemHostCandidates.clear();
        nextSystemHostCandidate = 0;
        systemState = systemHidTransition(systemState, 5);
        status = "Android system HID service disconnected";
        Log.i(LOG_TAG, "System HID service disconnected");
    }

    @Override @SuppressLint("MissingPermission")
    public synchronized void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
        Log.d(LOG_TAG, "System HID registration callback; state=" + systemState + " registered=" + registered
                + " pluggedHost=" + safeName(pluggedDevice)
                + " profileState=" + (pluggedDevice == null || systemDevice == null
                        ? "none" : systemDevice.getConnectionState(pluggedDevice)));
        if (systemState == 0) return;
        if (registered && systemState != 2 && systemState != 4) return;
        systemState = systemHidTransition(systemState, registered ? 2 : 3);
        if (!registered) {
            systemHost = null;
            connectingHost = null;
            systemHostCandidates.clear();
            nextSystemHostCandidate = 0;
            Log.i(LOG_TAG, "System HID registration stopped");
            status = "Android system HID registration stopped; keep OpenWASD in the foreground";
            return;
        }
        status = "System HID ready as " + systemAdapter.getName();
        Log.i(LOG_TAG, "System HID registered; identity=" + systemAdapter.getName());
        if (pluggedDevice != null
                && systemDevice.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTED) {
            systemHost = pluggedDevice;
            status = "Connected to " + safeName(pluggedDevice) + "; system HID mouse and keyboard ready";
            Log.i(LOG_TAG, "Keeping active HID virtual cable; host=" + safeName(pluggedDevice));
            return;
        }
        if (pluggedDevice != null
                && systemDevice.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTING) {
            Log.i(LOG_TAG, "Canceling reconnect to stale HID host=" + safeName(pluggedDevice));
            systemDevice.disconnect(pluggedDevice);
        }

        systemHostCandidates.clear();
        nextSystemHostCandidate = 0;
        Set<String> activeComputerAddresses = activeComputerProfileAddresses();
        List<BluetoothDevice> otherComputerBonds = new ArrayList<>();
        for (BluetoothDevice device : systemAdapter.getBondedDevices()) {
            android.bluetooth.BluetoothClass klass = device.getBluetoothClass();
            if (klass == null || klass.getMajorDeviceClass() != android.bluetooth.BluetoothClass.Device.Major.COMPUTER) {
                continue;
            }
            if (activeComputerAddresses.contains(device.getAddress().toUpperCase(java.util.Locale.ROOT))) {
                systemHostCandidates.add(device);
            } else if (pluggedDevice == null || !pluggedDevice.equals(device)) {
                otherComputerBonds.add(device);
            }
        }
        if (systemHostCandidates.isEmpty() && otherComputerBonds.size() == 1) {
            // Auto-connect only when there is one unambiguous computer bond.
            // A stale pluggedDevice from a previous registration is not evidence
            // that the host is connected or should take priority.
            systemHostCandidates.addAll(otherComputerBonds);
        }
        for (BluetoothDevice candidate : systemHostCandidates) {
            Log.i(LOG_TAG, "System HID connection candidate=" + safeName(candidate)
                    + " activeOtherProfile="
                    + activeComputerAddresses.contains(candidate.getAddress().toUpperCase(java.util.Locale.ROOT)));
        }
        if (systemHostCandidates.isEmpty()) {
            Log.w(LOG_TAG, "No unambiguous paired computer host is available for system HID");
            status = "System HID is ready; select or connect the target computer in Bluetooth settings";
        } else {
            connectNextSystemHost();
        }
        if (systemHostCandidates.isEmpty() && !discoverabilityRequested) {
            discoverabilityRequested = true;
            Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            discoverable.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(discoverable);
        }
    }

    @Override @SuppressLint("MissingPermission")
    public synchronized void onConnectionStateChanged(BluetoothDevice device, int newState) {
        if (systemState == 0) return;
        Log.i(LOG_TAG, "System HID host connection changed; host=" + safeName(device) + " state=" + newState);
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            systemHost = device;
            connectingHost = null;
            systemHostCandidates.clear();
            status = "Connected to " + safeName(device) + "; system HID mouse and keyboard ready";
        } else if (newState == BluetoothProfile.STATE_CONNECTING) {
            connectingHost = device;
            status = "Connecting system HID to " + safeName(device);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            boolean failedCandidate = connectingHost != null && connectingHost.equals(device);
            if (systemHost != null && systemHost.equals(device)) systemHost = null;
            if (failedCandidate) connectingHost = null;
            status = "System HID ready as " + systemAdapter.getName();
            if (failedCandidate && systemState == 3) connectNextSystemHost();
        }
    }

    @SuppressLint("MissingPermission")
    private Set<String> activeComputerProfileAddresses() {
        Set<String> addresses = new HashSet<>();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        for (int profile : new int[] {BluetoothProfile.HEADSET, BluetoothProfile.A2DP}) {
            try {
                for (BluetoothDevice device : manager.getConnectedDevices(profile)) {
                    android.bluetooth.BluetoothClass klass = device.getBluetoothClass();
                    if (klass != null && klass.getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.COMPUTER) {
                        addresses.add(device.getAddress().toUpperCase(java.util.Locale.ROOT));
                    }
                }
            } catch (RuntimeException error) {
                Log.w(LOG_TAG, "Could not inspect active Bluetooth profile " + profile, error);
            }
        }
        return addresses;
    }

    @SuppressLint("MissingPermission")
    private synchronized void connectNextSystemHost() {
        if (systemState != 3 || systemDevice == null || systemHost != null || connectingHost != null) return;
        while (nextSystemHostCandidate < systemHostCandidates.size()) {
            BluetoothDevice candidate = systemHostCandidates.get(nextSystemHostCandidate++);
            int state = systemDevice.getConnectionState(candidate);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                systemHost = candidate;
                systemHostCandidates.clear();
                status = "Connected to " + safeName(candidate) + "; system HID mouse and keyboard ready";
                Log.i(LOG_TAG, "System HID host already connected=" + safeName(candidate));
                return;
            }
            if (state == BluetoothProfile.STATE_CONNECTING) {
                connectingHost = candidate;
                Log.i(LOG_TAG, "System HID connection already in progress; host=" + safeName(candidate));
                return;
            }
            boolean accepted = systemDevice.connect(candidate);
            Log.i(LOG_TAG, "System HID connect requested; host=" + safeName(candidate)
                    + " accepted=" + accepted + " previousState=" + state);
            if (accepted) {
                connectingHost = candidate;
                status = "Connecting system HID to " + safeName(candidate);
                return;
            }
        }
        status = "System HID is ready, but no paired computer accepted the connection";
        Log.w(LOG_TAG, status);
    }

    @Override @SuppressLint("MissingPermission")
    public synchronized void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
        if (systemDevice == null) return;
        if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && (id == 1 || id == 2)) {
            systemDevice.replyReport(device, type, id, id == 1 ? new byte[4] : new byte[8]);
        } else {
            systemDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
        }
    }

    @Override
    public synchronized void onVirtualCableUnplug(BluetoothDevice device) {
        if (device.equals(systemHost)) systemHost = null;
        status = "System HID host disconnected";
    }

    @SuppressLint("MissingPermission")
    private synchronized boolean sendSystemReport(int reportId, byte[] report) {
        if (systemState != 3 || systemDevice == null || systemHost == null) return false;
        boolean sent = systemDevice.sendReport(systemHost, reportId, report);
        if (sent) Log.d(LOG_TAG, "HID system report queued; host=" + safeName(systemHost)
                + " reportId=" + reportId + " payload=" + bytesToHex(report));
        return sent;
    }

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
    private BluetoothGattCharacteristic gamepadReport;
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
        if (systemState != 0) {
            status = "System HID already owns this peripheral; stop it before starting BLE HID";
            return false;
        }
        if (server != null) return true;
        Log.i(LOG_TAG, "BLE HID session starting");
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
        if (systemState != 0 || !hidServiceReady || server == null || advertiser == null || !connectedHosts.isEmpty()) {
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
        gamepadReport = inputReport(3);
        service.addCharacteristic(mouseReport);
        service.addCharacteristic(keyboardReport);
        service.addCharacteristic(gamepadReport);
        return service;
    }

    private BluetoothGattService createDeviceInformationService() {
        BluetoothGattService service = new BluetoothGattService(
                DEVICE_INFORMATION_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(publicReadCharacteristic(
                MANUFACTURER_NAME, "OpenWASD".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.addCharacteristic(publicReadCharacteristic(
                MODEL_NUMBER, "BLE HID Controller".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // HOGP requires PnP ID. 0xFFFF is the development/default vendor,
        // not an assigned third-party vendor identity.
        service.addCharacteristic(publicReadCharacteristic(PNP_ID,
                new byte[] {0x01, (byte) 0xFF, (byte) 0xFF, 0x01, 0x00, 0x01, 0x00}));
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
        characteristic.setValue(reportId == 1 ? new byte[] {0, 0, 0, 0}
                : reportId == 3 ? new byte[7] : new byte[8]);
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
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) return;
                    connectedHosts.put(address, device);
                    int mask = loadSubscriptions(context.getFilesDir().getAbsolutePath(), address,
                            device.getBondState() == BluetoothDevice.BOND_BONDED);
                    Set<Integer> restored = new HashSet<>();
                    if ((mask & 1) != 0) restored.add(1);
                    if ((mask & 2) != 0) restored.add(2);
                    reportSubscriptions.put(address, restored);
                    Log.i(LOG_TAG, "HID report subscriptions restored; host=" + safeName(device)
                            + " mask=" + mask);
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
            byte[] value;
            synchronized (BleHidPeripheral.this) {
                if (CLIENT_CONFIGURATION.equals(descriptor.getUuid())) {
                    boolean enabled = reportSubscriptions.getOrDefault(device.getAddress(), new HashSet<>())
                            .contains(reportId(descriptor.getCharacteristic()));
                    value = new byte[] {(byte) (enabled ? 1 : 0), 0};
                } else {
                    value = descriptor.getValue();
                }
            }
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
                if (preparedWrite || offset != 0 || value == null || value.length != 2
                        || value[1] != 0 || (value[0] != 0 && value[0] != 1)) {
                    if (responseNeeded) server.sendResponse(device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null);
                    return;
                }
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
                    int mask = (subscribed.contains(1) ? 1 : 0) | (subscribed.contains(2) ? 2 : 0);
                    if (!saveSubscriptions(context.getFilesDir().getAbsolutePath(), device.getAddress(),
                            device.getBondState() == BluetoothDevice.BOND_BONDED, mask)) {
                        if (enabled) subscribed.remove(reportId); else subscribed.add(reportId);
                        if (responseNeeded) server.sendResponse(device, requestId,
                                BluetoothGatt.GATT_FAILURE, offset, null);
                        return;
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
        byte[] report = new byte[] {buttons, dx, dy, wheel};
        return systemState != 0 ? sendSystemReport(1, report) : notifyHosts(mouseReport, report);
    }

    public boolean sendKeyboardReport(byte[] report) {
        if (report == null || report.length != 8) return false;
        return systemState != 0 ? sendSystemReport(2, report.clone()) : notifyHosts(keyboardReport, report.clone());
    }

    public boolean sendGamepadReport(byte[] report) {
        if (report == null || report.length != 7) return false;
        return systemState != 0 ? sendSystemReport(3, report.clone()) : notifyHosts(gamepadReport, report.clone());
    }

    @SuppressLint("MissingPermission")
    private synchronized boolean notifyHosts(BluetoothGattCharacteristic characteristic, byte[] report) {
        if (!hidServiceReady || server == null || characteristic == null || connectedHosts.isEmpty()) return false;
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
        systemState = systemHidTransition(systemState, 5);
        if (systemDevice != null) {
            if (systemHost != null) {
                systemDevice.sendReport(systemHost, 1, new byte[4]);
                systemDevice.sendReport(systemHost, 2, new byte[8]);
                systemDevice.disconnect(systemHost);
            }
            systemDevice.unregisterApp();
            if (systemAdapter != null) systemAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, systemDevice);
        }
        systemDevice = null;
        systemHost = null;
        connectingHost = null;
        systemHostCandidates.clear();
        nextSystemHostCandidate = 0;
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
        Log.i(LOG_TAG, "BLE HID session stopped");
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

    public synchronized int getConnectedReportMask() {
        if (systemState != 0) return systemState == 3 && systemHost != null ? 7 : 0;
        if (!hidServiceReady || connectedHosts.isEmpty()) return 0;
        String host = connectedHosts.keySet().iterator().next();
        Set<Integer> reports = reportSubscriptions.getOrDefault(host, new HashSet<>());
        return (reports.contains(1) ? 1 : 0) | (reports.contains(2) ? 2 : 0)
                | (reports.contains(3) ? 4 : 0);
    }

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
        if (systemState != 0) return systemHost == null ? null : safeName(systemHost);
        if (connectedHosts.isEmpty()) return null;
        return safeName(connectedHosts.values().iterator().next());
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        if (device == null) return "none";
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
