package com.powerrun.dashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Native shell around the Power Run dashboard (assets/dashboard.html).
 *
 * Two transports:
 *   - WiFi:  the HTML opens ws://<ip>:81 itself (a native WebView has no
 *            mixed-content restriction, unlike a hosted web page).
 *   - BLE:   this class scans for the ESP32's Nordic UART Service, subscribes
 *            to notifications, and feeds each JSON line into the page via
 *            window.onTelemetry(...). The page drives it through window.PowerRunBle.
 */
public class MainActivity extends Activity {

    private WebView web;
    private BleLink ble;

    private static final int REQ_BLE_PERMS = 42;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());

        ble = new BleLink(this, web);
        web.addJavascriptInterface(new Bridge(), "PowerRunBle");

        web.loadUrl("file:///android_asset/dashboard.html");
        setContentView(web);
    }

    // ---- JS <-> native bridge --------------------------------------------

    private class Bridge {
        @JavascriptInterface
        public boolean isSupported() {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        }

        @JavascriptInterface
        public void connect() {
            runOnUiThread(() -> {
                if (ensureBlePermissions()) {
                    ble.startScanAndConnect();
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(() -> ble.disconnect());
        }
    }

    // ---- Runtime permissions --------------------------------------------

    private boolean ensureBlePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), REQ_BLE_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMS) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) ok &= (r == PackageManager.PERMISSION_GRANTED);
            if (ok) {
                ble.startScanAndConnect();
            } else {
                ble.emitStatus("no_permission");
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (ble != null) ble.disconnect();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
