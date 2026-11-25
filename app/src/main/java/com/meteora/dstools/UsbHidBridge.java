package com.meteora.dstools;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Jembatan WebView -> Android USB Host API yang meniru subset WebHID
 * (navigator.hid) untuk kontroler Sony.
 */
public class UsbHidBridge {

    public interface Listener {
        void onWriteModeReset();
    }

    private static final String TAG = "DSTools";
    private static final String ACTION_PERM = "com.meteora.dstools.USB_PERMISSION";
    private static final String MSG_READONLY =
            "Mode BACA-SAJA aktif: operasi tulis/kalibrasi diblokir. Aktifkan Mode Tulis dari bar atas aplikasi.";

    private static final int RT_IN = 0xA1;   // device-to-host | class | interface
    private static final int RT_OUT = 0x21;  // host-to-device | class | interface
    private static final int REQ_GET_REPORT = 0x01;
    private static final int REQ_SET_REPORT = 0x09;
    private static final int RPT_OUTPUT = 2;
    private static final int RPT_FEATURE = 3;
    private static final int CTRL_TIMEOUT = 1000;
    private static final int MAX_LOG_LINES = 400;

    private static final class Dev {
        UsbDevice dev;
        UsbDeviceConnection conn;
        UsbInterface iface;
        UsbEndpoint epIn;
        UsbEndpoint epOut;
        HidDescriptor desc;
        volatile boolean open;
        Thread reader;
    }

    private static final class PermWait {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean granted;
    }

    private final Context ctx;
    private final WebView web;
    private final Listener listener;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Map<String, Dev> devs = new ConcurrentHashMap<>();
    private final Map<String, PermWait> perms = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ArrayDeque<String> logBuf = new ArrayDeque<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private volatile boolean writeEnabled = false;
    private volatile boolean destroyed = false;

    public UsbHidBridge(Context context, WebView webView, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.web = webView;
        this.listener = listener;
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        log("Bridge siap");
    }

    // ------------------------------------------------------------------ public

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean on) {
        writeEnabled = on;
        log(on ? "MODE TULIS: AKTIF" : "MODE TULIS: nonaktif (baca-saja)");
    }

    public synchronized String dumpLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : logBuf) sb.append(s).append('\n');
        return sb.toString();
    }

    public void shutdown() {
        destroyed = true;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        for (Dev d : devs.values()) {
            stopReader(d);
            if (d.conn != null) d.conn.close();
        }
        devs.clear();
        exec.shutdownNow();
    }

    // -------------------------------------------------------------- JS bridge

    @JavascriptInterface
    public void call(final String method, final String argsJson, final int id) {
        if (destroyed) return;
        try {
            exec.execute(() -> {
                try {
                    String res = dispatch(method, new JSONObject(argsJson));
                    deliver(id, true, res);
                } catch (Throwable t) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    log("ERROR " + method + ": " + msg);
                    deliver(id, false, msg);
                }
            });
        } catch (Exception e) {
            deliver(id, false, "Bridge tidak tersedia");
        }
    }

    private String dispatch(String method, JSONObject a) throws Exception {
        switch (method) {
            case "getDevices": return getDevices();
            case "requestDevice": return requestDevice(a.optJSONArray("filters"));
            case "open": return openDev(a.getString("key"));
            case "close": return closeDev(a.getString("key"));
            case "sendReport": return sendOutput(a);
            case "sendFeatureReport": return sendFeature(a);
            case "receiveFeatureReport": return receiveFeature(a);
            default: throw new IOException("Method tidak dikenal: " + method);
        }
    }

    // --------------------------------------------------------------- discovery

    private String getDevices() throws Exception {
        JSONArray out = new JSONArray();
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (findHidInterface(d) == null) continue;
            if (!usb.hasPermission(d)) continue;
            out.put(info(d));
        }
        return out.toString();
    }

    private String requestDevice(JSONArray filters) throws Exception {
        JSONArray out = new JSONArray();
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (findHidInterface(d) == null) continue;
            if (!matches(d, filters)) continue;
            if (!usb.hasPermission(d)) {
                if (!askPermission(d)) throw new IOException("NotAllowedError: izin USB ditolak");
            }
            out.put(info(d));
            break;
        }
        return out.toString();
    }

    private boolean askPermission(UsbDevice d) throws Exception {
        PermWait w = new PermWait();
        perms.put(d.getDeviceName(), w);
        try {
            Intent i = new Intent(ACTION_PERM).setPackage(ctx.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, flags);
            usb.requestPermission(d, pi);
            return w.latch.await(60, TimeUnit.SECONDS) && w.granted;
        } finally {
            perms.remove(d.getDeviceName());
        }
    }

    private static boolean matches(UsbDevice d, JSONArray filters) {
        if (filters == null || filters.length() == 0) return true;
        for (int i = 0; i < filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) continue;
            boolean ok = true;
            if (f.has("vendorId") && f.optInt("vendorId") != d.getVendorId()) ok = false;
            if (f.has("productId") && f.optInt("productId") != d.getProductId()) ok = false;
            if (ok) return true;
        }
        return false;
    }

    private static UsbInterface findHidInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    return itf;
                }
            }
        }
        return null;
    }

    private Dev ensureDev(UsbDevice d) throws Exception {
        Dev dev = devs.get(d.getDeviceName());
        if (dev != null) return dev;

        dev = new Dev();
        dev.dev = d;
        dev.iface = findHidInterface(d);
        if (dev.iface == null) throw new IOException("Interface HID tidak ditemukan");
        for (int e = 0; e < dev.iface.getEndpointCount(); e++) {
            UsbEndpoint ep = dev.iface.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && dev.epIn == null) dev.epIn = ep;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && dev.epOut == null) dev.epOut = ep;
        }
        dev.conn = usb.openDevice(d);
        if (dev.conn == null) throw new IOException("Gagal membuka perangkat USB");

        byte[] buf = new byte[4096];
        int n = -1;
        for (int attempt = 0; attempt < 2 && n <= 0; attempt++) {
            // GET_DESCRIPTOR (HID report descriptor), standard | interface
            n = dev.conn.controlTransfer(0x81, 0x06, 0x2200, dev.iface.getId(), buf, buf.length, 2000);
        }
        if (n <= 0) {
            dev.conn.close();
            throw new IOException("Gagal membaca HID report descriptor");
        }
        dev.desc = HidDescriptor.parse(buf, n);
        if (dev.desc.collections.isEmpty()) {
            dev.conn.close();
            throw new IOException("HID report descriptor tidak valid");
        }
        devs.put(d.getDeviceName(), dev);
        log(String.format(Locale.US, "Perangkat %04x:%04x (%s), iface %d, descriptor %d byte",
                d.getVendorId(), d.getProductId(), d.getDeviceName(), dev.iface.getId(), n));
        return dev;
    }

    private JSONObject info(UsbDevice d) throws Exception {
        Dev dev = ensureDev(d);
        JSONObject o = new JSONObject();
        o.put("key", d.getDeviceName());
        o.put("vendorId", d.getVendorId());
        o.put("productId", d.getProductId());
        String name = d.getProductName();
        o.put("productName", name != null ? name : "");
        JSONArray colls = new JSONArray();
        for (HidDescriptor.Coll c : dev.desc.collections) {
            JSONObject co = new JSONObject();
            co.put("usagePage", c.usagePage);
            co.put("usage", c.usage);
            co.put("inputReports", reports(c.input));
            co.put("outputReports", reports(c.output));
            co.put("featureReports", reports(c.feature));
            colls.put(co);
        }
        o.put("collections", colls);
        return o;
    }

    private static JSONArray reports(Map<Integer, HidDescriptor.Report> m) throws Exception {
        JSONArray arr = new JSONArray();
        for (HidDescriptor.Report r : m.values()) {
            JSONObject ro = new JSONObject();
            ro.put("reportId", r.id);
            JSONArray items = new JSONArray();
            for (HidDescriptor.Item it : r.items) {
                JSONObject io = new JSONObject();
                io.put("reportSize", it.reportSize);
                io.put("reportCount", it.reportCount);
                items.put(io);
            }
            ro.put("items", items);
            arr.put(ro);
        }
        return arr;
    }

    // ------------------------------------------------------------- open/close

    private Dev need(String key) throws Exception {
        Dev d = devs.get(key);
        if (d == null) throw new IOException("Perangkat tidak ditemukan / sudah dicabut");
        return d;
    }

    private Dev needOpen(String key) throws Exception {
        Dev d = need(key);
        if (!d.open) throw new IOException("Perangkat belum dibuka");
        return d;
    }

    private String openDev(String key) throws Exception {
        final Dev d = need(key);
        if (d.open) return "{}";
        if (!d.conn.claimInterface(d.iface, true)) throw new IOException("claimInterface gagal");
        d.open = true;
        d.reader = new Thread(() -> readLoop(key, d), "hid-read");
        d.reader.start();
        log("OPEN " + key);
        return "{}";
    }

    private String closeDev(String key) {
        Dev d = devs.get(key);
        if (d == null) return "{}";
        stopReader(d);
        try {
            d.conn.releaseInterface(d.iface);
        } catch (Exception ignored) {
        }
        log("CLOSE " + key);
        resetWrite();
        return "{}";
    }

    private void stopReader(Dev d) {
        d.open = false;
        Thread t = d.reader;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
            d.reader = null;
        }
    }

    private void handleDetached(String name) {
        Dev d = devs.remove(name);
        if (d == null) return;
        log("DICABUT " + name);
        stopReader(d);
        try {
            if (d.conn != null) d.conn.close();
        } catch (Exception ignored) {
        }
        evalJs("window.__hidDisconnect(" + JSONObject.quote(name) + ")", null);
        resetWrite();
    }

    private void resetWrite() {
        if (writeEnabled) {
            writeEnabled = false;
            log("MODE TULIS dikembalikan ke baca-saja");
            if (listener != null) listener.onWriteModeReset();
        }
    }

    // ------------------------------------------------------------------ input

    private void readLoop(String key, Dev d) {
        byte[] buf = new byte[Math.max(64, d.epIn.getMaxPacketSize())];
        boolean ids = d.desc.hasReportIds;
        while (d.open && !destroyed) {
            long t0 = System.currentTimeMillis();
            int n = d.conn.bulkTransfer(d.epIn, buf, buf.length, 100);
            if (n > 0) {
                int rid = 0, off = 0;
                if (ids) {
                    rid = buf[0] & 0xFF;
                    off = 1;
                }
                if (n - off <= 0) continue;
                if (inFlight.get() > 3) continue; // buang frame bila JS tertinggal
                final String b64 = Base64.encodeToString(buf, off, n - off, Base64.NO_WRAP);
                inFlight.incrementAndGet();
                final String js = "window.__hidInput(" + JSONObject.quote(key) + "," + rid + ",'" + b64 + "')";
                main.post(() -> {
                    if (destroyed) {
                        inFlight.decrementAndGet();
                        return;
                    }
                    web.evaluateJavascript(js, v -> inFlight.decrementAndGet());
                });
            } else if (n < 0 && System.currentTimeMillis() - t0 < 20) {
                try {
                    Thread.sleep(20); // hindari busy-loop saat error langsung
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- output

    private static boolean isMutating(int rid, byte[] data) {
        int b0 = data.length > 0 ? data[0] & 0xFF : -1;
        int b1 = data.length > 1 ? data[1] & 0xFF : -1;
        if (rid == 0x90) return true;                         // DS4: kalibrasi
        if (rid == 0xA0 && b0 == 10 && b1 == 2) return true;  // DS4: NVS unlock
        if (rid == 0x82) return true;                         // DS5/Edge: kalibrasi
        if (rid == 0x80 && b0 == 3 && b1 == 2) return true;   // DS5/Edge: NVS unlock
        if (rid == 0x80 && b0 == 12 && b1 == 1) return true;  // DS5: tulis finetune
        return false;
    }

    private static byte[] frame(Dev d, int rid, byte[] data, int expected) throws IOException {
        boolean withId = d.desc.hasReportIds;
        byte[] raw = new byte[(withId ? 1 : 0) + data.length];
        if (withId) raw[0] = (byte) rid;
        System.arraycopy(data, 0, raw, withId ? 1 : 0, data.length);
        if (expected > 0) {
            if (raw.length > expected) {
                throw new IOException("Panjang report " + raw.length + " melebihi descriptor (" + expected + ")");
            }
            if (raw.length < expected) raw = Arrays.copyOf(raw, expected);
        }
        return raw;
    }

    private String sendFeature(JSONObject a) throws Exception {
        Dev d = needOpen(a.getString("key"));
        int rid = a.getInt("reportId");
        byte[] data = Base64.decode(a.optString("data", ""), Base64.DEFAULT);

        if (!writeEnabled && isMutating(rid, data)) {
            log("BLOKIR feature 0x" + hex(rid) + " " + hexDump(data, 16) + " (mode baca-saja)");
            throw new IOException(MSG_READONLY);
        }

        byte[] buf = frame(d, rid, data, d.desc.featureTotal(rid));
        log("FEAT-TX 0x" + hex(rid) + " len=" + buf.length + " " + hexDump(buf, 24));
        int n = d.conn.controlTransfer(RT_OUT, REQ_SET_REPORT, (RPT_FEATURE << 8) | rid,
                d.iface.getId(), buf, buf.length, CTRL_TIMEOUT);
        if (n < 0) throw new IOException("SET_REPORT gagal (0x" + hex(rid) + ")");
        return "{}";
    }

    private String receiveFeature(JSONObject a) throws Exception {
        Dev d = needOpen(a.getString("key"));
        int rid = a.getInt("reportId");
        int size = d.desc.featureTotal(rid);
        if (size <= 0) size = 64;
        byte[] buf = new byte[size];
        int n = d.conn.controlTransfer(RT_IN, REQ_GET_REPORT, (RPT_FEATURE << 8) | rid,
                d.iface.getId(), buf, size, CTRL_TIMEOUT);
        if (n < 0) throw new IOException("GET_REPORT gagal (0x" + hex(rid) + ")");
        JSONObject o = new JSONObject();
        o.put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
        return o.toString();
    }

    private String sendOutput(JSONObject a) throws Exception {
        Dev d = needOpen(a.getString("key"));
        int rid = a.getInt("reportId");
        byte[] data = Base64.decode(a.optString("data", ""), Base64.DEFAULT);
        byte[] buf = frame(d, rid, data, d.desc.outputTotal(rid));
        int n;
        if (d.epOut != null) {
            n = d.conn.bulkTransfer(d.epOut, buf, buf.length, CTRL_TIMEOUT);
        } else {
            n = d.conn.controlTransfer(RT_OUT, REQ_SET_REPORT, (RPT_OUTPUT << 8) | rid,
                    d.iface.getId(), buf, buf.length, CTRL_TIMEOUT);
        }
        if (n < 0) throw new IOException("Output report gagal (0x" + hex(rid) + ")");
        return "{}";
    }

    // ---------------------------------------------------------------- helpers

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            UsbDevice d = deviceExtra(intent);
            if (d == null) return;
            String action = intent.getAction();
            if (ACTION_PERM.equals(action)) {
                PermWait w = perms.get(d.getDeviceName());
                if (w != null) {
                    w.granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    w.latch.countDown();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                final String name = d.getDeviceName();
                exec.execute(() -> handleDetached(name));
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) {
            return i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private void deliver(int id, boolean ok, String payload) {
        evalJs("window.__hidResult(" + id + "," + ok + "," + JSONObject.quote(payload == null ? "" : payload) + ")", null);
    }

    private void evalJs(final String js, final ValueCallback<String> cb) {
        main.post(() -> {
            if (!destroyed) web.evaluateJavascript(js, cb);
        });
    }

    private synchronized void log(String line) {
        String s = timeFmt.format(new Date()) + " " + line;
        Log.i(TAG, line);
        logBuf.addLast(s);
        while (logBuf.size() > MAX_LOG_LINES) logBuf.removeFirst();
    }

    private static String hex(int v) {
        return String.format(Locale.US, "%02x", v & 0xFF);
    }

    private static String hexDump(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < max; i++) sb.append(hex(b[i])).append(' ');
        if (b.length > max) sb.append("...");
        return sb.toString().trim();
    }
}
