package com.irstudio.pro;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.ConsumerIrManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ConsumerIrManager irManager;
    private UsbManager usbManager;
    private UsbDeviceConnection usbConnection;
    private UsbEndpoint usbInEndpoint;
    private boolean readingUsb = false;
    private static final String ACTION_USB_PERMISSION = "com.irstudio.pro.USB_PERMISSION";

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        openUsbDevice(device);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new NativeIRBridge(), "AndroidIR");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class NativeIRBridge {
        @JavascriptInterface
        public boolean hasIrEmitter() {
            return irManager != null && irManager.hasIrEmitter();
        }

        @JavascriptInterface
        public void transmit(int frequency, String rawJsonArray) {
            if (irManager == null || !irManager.hasIrEmitter()) return;
            try {
                JSONArray arr = new JSONArray(rawJsonArray);
                int[] pattern = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    pattern[i] = arr.getInt(i);
                }
                irManager.transmit(frequency, pattern);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @JavascriptInterface
        public String connectUsbReceiver() {
            if (usbManager == null) return "NO_USB_SERVICE";
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            if (deviceList.isEmpty()) return "NO_DEVICE";

            UsbDevice device = deviceList.values().iterator().next();
            if (!usbManager.hasPermission(device)) {
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, 0, new Intent(ACTION_USB_PERMISSION), flags);
                usbManager.requestPermission(device, pi);
                return "PERMISSION_REQUESTED";
            } else {
                openUsbDevice(device);
                return "CONNECTED";
            }
        }
    }

    private void openUsbDevice(UsbDevice device) {
        try {
            UsbInterface intf = device.getInterface(device.getInterfaceCount() > 1 ? 1 : 0);
            for (int i = 0; i < intf.getEndpointCount(); i++) {
                UsbEndpoint ep = intf.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    usbInEndpoint = ep;
                    break;
                }
            }
            if (usbInEndpoint == null) return;
            usbConnection = usbManager.openDevice(device);
            if (usbConnection == null) return;
            usbConnection.claimInterface(intf, true);

            // Configura baud rate 115200 padrão CDC/ACM
            byte[] lineCoding = new byte[] {
                (byte) 0x00, (byte) 0xC2, (byte) 0x01, (byte) 0x00, // 115200
                (byte) 0x00, (byte) 0x00, (byte) 0x08
            };
            usbConnection.controlTransfer(0x21, 0x20, 0, 0, lineCoding, lineCoding.length, 500);
            usbConnection.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 500);

            runOnUiThread(() -> webView.evaluateJavascript("onNativeUsbConnected()", null));

            if (!readingUsb) {
                readingUsb = true;
                new Thread(() -> {
                    byte[] buffer = new byte[256];
                    StringBuilder sb = new StringBuilder();
                    while (readingUsb && usbConnection != null) {
                        int len = usbConnection.bulkTransfer(usbInEndpoint, buffer, buffer.length, 200);
                        if (len > 0) {
                            String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
                            sb.append(chunk);
                            int nl;
                            while ((nl = sb.indexOf("\n")) != -1) {
                                final String line = sb.substring(0, nl).trim();
                                sb.delete(0, nl + 1);
                                if (line.startsWith("{") && line.endsWith("}")) {
                                    final String escaped = line.replace("\\", "\\\\").replace("'", "\\'");
                                    runOnUiThread(() -> webView.evaluateJavascript("onNativeIrReceived('" + escaped + "')", null));
                                }
                            }
                        }
                    }
                }).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        readingUsb = false;
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
