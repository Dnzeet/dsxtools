package com.meteora.dstools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";

    private WebView web;
    private UsbHidBridge bridge;
    private LinearLayout bar;
    private TextView statusView;
    private Switch writeSwitch;
    private boolean ignoreSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Layar tetap menyala supaya proses kalibrasi tidak terputus
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setupWebView();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(4), dp(10), dp(4));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(0xFF000000);
        bar.addView(statusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button logBtn = new Button(this);
        logBtn.setText("Log");
        logBtn.setAllCaps(false);
        logBtn.setOnClickListener(v -> showLog());
        bar.addView(logBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        writeSwitch = new Switch(this);
        writeSwitch.setText("Tulis ");
        writeSwitch.setTextColor(0xFF000000);
        writeSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (ignoreSwitch) return;
            if (checked) confirmWriteMode();
            else setWrite(false);
        });
        bar.addView(writeSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        updateBar(false);
    }

    private void updateBar(boolean on) {
        statusView.setText(on ? "MODE TULIS AKTIF - jangan cabut kabel" : "Mode baca-saja (aman)");
        bar.setBackgroundColor(on ? 0xFFFFCDD2 : 0xFFC8E6C9);
    }

    private void setSwitchSilently(boolean checked) {
        ignoreSwitch = true;
        writeSwitch.setChecked(checked);
        ignoreSwitch = false;
    }

    private void setWrite(boolean on) {
        if (bridge != null) bridge.setWriteEnabled(on);
        updateBar(on);
    }

    private void confirmWriteMode() {
        new AlertDialog.Builder(this)
                .setTitle("Aktifkan Mode Tulis?")
                .setMessage("Mode ini mengizinkan kalibrasi yang menulis ke memori kontroler.\n\n"
                        + "- Pakai kabel USB/OTG yang kokoh\n"
                        + "- Baterai HP dan kontroler cukup\n"
                        + "- Jangan cabut kabel atau pindah aplikasi saat proses berjalan\n\n"
                        + "Mode tulis otomatis mati jika kabel dicabut atau koneksi ditutup.")
                .setPositiveButton("Aktifkan", (d, w) -> setWrite(true))
                .setNegativeButton("Batal", (d, w) -> setSwitchSilently(false))
                .setOnCancelListener(d -> setSwitchSilently(false))
                .show();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String p = u.getPath();
                if (p == null || p.equals("/") || p.equals("/index.html")) {
                    WebResourceResponse r = indexResponse();
                    if (r != null) return r;
                }
                return loader.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setNegativeButton("Batal", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("DSToolsWeb", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        bridge = new UsbHidBridge(this, web, () -> runOnUiThread(() -> {
            setSwitchSilently(false);
            updateBar(false);
        }));
        web.addJavascriptInterface(bridge, "AndroidHID");
        web.loadUrl(START_URL);
    }

    /** Menyajikan index.html dengan polyfill navigator.hid disisipkan paling awal. */
    private WebResourceResponse indexResponse() {
        try {
            String html = new String(readAll(getAssets().open("index.html")), StandardCharsets.UTF_8);
            String poly = "<script>" + new String(readAll(getResources().openRawResource(R.raw.hid_polyfill)),
                    StandardCharsets.UTF_8) + "</script>";
            Matcher m = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (!m.find()) m = Pattern.compile("<!doctype[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (!m.find(0)) m = Pattern.compile("<html[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find(0)) {
                html = html.substring(0, m.end()) + poly + html.substring(m.end());
            } else {
                html = poly + html;
            }
            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            Log.e("DSTools", "Gagal membaca index.html", e);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void showLog() {
        final String text = bridge.dumpLog();
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setTextIsSelectable(true);
        tv.setText(text.isEmpty() ? "(log kosong)" : text);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Log USB")
                .setView(sv)
                .setPositiveButton("Salin", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("log", text));
                    Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (bridge != null && bridge.isWriteEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Mode tulis masih aktif")
                    .setMessage("Keluar sekarang bisa memutus proses kalibrasi. Yakin keluar?")
                    .setPositiveButton("Keluar", (d, w) -> finish())
                    .setNegativeButton("Tetap di sini", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
