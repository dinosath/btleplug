package com.nonpolynomial.btleplug.android.impl;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Queries connected peers through Android's public per-profile proxy API. */
public final class BluetoothProfileQuery {
    private static final String TAG = "btleplug-profile";
    private BluetoothProfileQuery() {}

    @SuppressLint("MissingPermission")
    public static boolean isDeviceConnected(Context context, String address, int profile) {
        Log.d(TAG, "querying profile=" + profile + " address=" + address);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return false;
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean(false);
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            private BluetoothProfile proxy;

            @Override public void onServiceConnected(int connectedProfile, BluetoothProfile service) {
                if (connectedProfile != profile) return;
                proxy = service;
                try {
                    for (BluetoothDevice device : service.getConnectedDevices()) {
                        if (address.equalsIgnoreCase(device.getAddress())) {
                            connected.set(true);
                            break;
                        }
                    }
                } finally {
                    adapter.closeProfileProxy(connectedProfile, service);
                    ready.countDown();
                }
            }

            @Override public void onServiceDisconnected(int disconnectedProfile) {
                if (proxy != null) adapter.closeProfileProxy(disconnectedProfile, proxy);
                ready.countDown();
            }
        };
        if (!adapter.getProfileProxy(context, listener, profile)) return false;
        try {
            ready.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "query result profile=" + profile + " address=" + address + " connected=" + connected.get());
        return connected.get();
    }
}
