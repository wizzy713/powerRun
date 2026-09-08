package com.powerrun.dashboard;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.webkit.WebView;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Connects to the ESP32 "PowerRun-BLE" Nordic UART Service and streams the
 * newline-delimited JSON telemetry into the WebView.
 *
 * Permissions are checked by MainActivity before any method here is called.
 */
@SuppressLint("MissingPermission")
class BleLink {

    static final UUID NUS_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID NUS_TX       = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID CCCD         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final String DEVICE_NAME = "PowerRun-BLE";
    private static final long SCAN_TIMEOUT_MS = 20_000L;

    private final Context ctx;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder rxBuf = new StringBuilder();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;

    BleLink(Context ctx, WebView web) {
        this.ctx = ctx;
        this.web = web;
    }

    // ---- public API (called from MainActivity, on the UI thread) --------

    void startScanAndConnect() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) { emitStatus("unsupported"); return; }
        if (!adapter.isEnabled()) { emitStatus("bt_off"); return; }

        disconnect();               // drop any previous link
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { emitStatus("bt_off"); return; }

        // Unfiltered scan + match in code: a small ESP32 advert may carry the
        // 128-bit service UUID only in the scan response, which some filters miss.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        emitStatus("scanning");
        scanner.startScan(null, settings, scanCallback);
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    void disconnect() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        rxBuf.setLength(0);
    }

    // ---- scanning ------------------------------------------------------

    private final Runnable scanTimeout = () -> {
        if (scanning) {
            stopScan();
            emitStatus("not_found");
        }
    };

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning || !matches(result)) return;
            stopScan();
            emitStatus("connecting");
            gatt = result.getDevice().connectGatt(
                    ctx, false, gattCallback, BluetoothDevice_TRANSPORT_LE());
        }

        @Override
        public void onScanFailed(int errorCode) {
            stopScan();
            emitStatus("scan_failed");
        }
    };

    private boolean matches(ScanResult r) {
        if (r == null) return false;
        if (r.getScanRecord() != null) {
            List<ParcelUuid> uuids = r.getScanRecord().getServiceUuids();
            if (uuids != null && uuids.contains(new ParcelUuid(NUS_SERVICE))) return true;
            String n = r.getScanRecord().getDeviceName();
            if (DEVICE_NAME.equals(n)) return true;
        }
        try {
            if (DEVICE_NAME.equals(r.getDevice().getName())) return true;
        } catch (SecurityException ignored) {}
        return false;
    }

    private static int BluetoothDevice_TRANSPORT_LE() {
        return 2; // android.bluetooth.BluetoothDevice.TRANSPORT_LE
    }

    // ---- GATT --------------------------------------------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(247);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitStatus("disconnected");
                try { g.close(); } catch (Exception ignored) {}
                if (g == gatt) gatt = null;
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(NUS_SERVICE);
            if (svc == null) { emitStatus("no_service"); return; }
            BluetoothGattCharacteristic tx = svc.getCharacteristic(NUS_TX);
            if (tx == null) { emitStatus("no_service"); return; }

            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            }
            emitStatus("connected");
        }

        // API 33+
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (NUS_TX.equals(ch.getUuid())) ingest(value);
        }

        // pre-33
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33 && NUS_TX.equals(ch.getUuid())) {
                ingest(ch.getValue());
            }
        }
    };

    // ---- reassembly + hand-off to the page --------------------------

    private void ingest(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        rxBuf.append(new String(bytes, StandardCharsets.UTF_8));

        int nl;
        while ((nl = rxBuf.indexOf("\n")) >= 0) {
            String line = rxBuf.substring(0, nl).trim();
            rxBuf.delete(0, nl + 1);
            if (!line.isEmpty()) pushTelemetry(line);
        }
        if (rxBuf.length() > 4096) rxBuf.setLength(0);   // safety valve
    }

    private void pushTelemetry(String jsonLine) {
        final String js = "window.onTelemetry && window.onTelemetry(" + jsQuote(jsonLine) + ");";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    void emitStatus(String state) {
        final String js = "window.onBleStatus && window.onBleStatus(" + jsQuote(state) + ");";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static String jsQuote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
