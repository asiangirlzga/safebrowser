package com.lite.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Environment;
import android.text.format.Formatter;
import android.webkit.URLUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Lite Browser: ONE WebView shared by all tabs (background tabs are just a URL + small
 * back/forward state in memory), nothing is written to disk that survives exit.
 */
public class MainActivity extends Activity {

    static final String HOME = "https://www.google.com";
    static final int MAX_IMG_WIDTH = 720;       // data saver: downscale images wider than this
    static final int WEBP_QUALITY = 40;
    static final int SKIP_BELOW = 20 * 1024;    // tiny images are passed through untouched
    static final int MAX_IMG_BYTES = 8 * 1024 * 1024;

    static final String[] BLOCKED = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.com", "2mdn.net",
            "connect.facebook.net", "scorecardresearch.com", "quantserve.com", "taboola.com",
            "outbrain.com", "criteo.com", "criteo.net", "adnxs.com", "rubiconproject.com",
            "pubmatic.com", "openx.net", "moatads.com", "amazon-adsystem.com", "hotjar.com",
            "mixpanel.com", "segment.io", "adsrvr.org", "advertising.com", "popads.net",
            "mgid.com", "revcontent.com", "smartadserver.com", "casalemedia.com", "yieldmo.com"
    };

    static class Tab {
        Bundle state;
        String url = HOME;
        String title = "New tab";
    }

    final ArrayList<Tab> tabs = new ArrayList<>();
    int cur = -1;
    WebView wv;
    EditText urlBar;
    TextView tabBtn;
    ProgressBar bar;
    FrameLayout holder;
    boolean dataSaver = true, imagesOff = false;
    SharedPreferences prefs;            // only settings + bookmarks live here (no browsing data)
    int darkMode = 0;                   // 0 = follow system, 1 = on, 2 = off
    boolean dark;
    LinearLayout root, top;
    TextView menuBtn;
    String pDlUrl, pDlUa, pDlCd, pDlMime;
    static volatile boolean alive = false;
    static boolean processUsed = false;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Semaphore imgSlots = new Semaphore(3);   // cap parallel image recompression (RAM)

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // hides page thumbnail in recent apps + blocks screenshots (privacy)
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        alive = true;
        prefs = getSharedPreferences("lb", MODE_PRIVATE);
        darkMode = prefs.getInt("dark", 0);
        dataSaver = prefs.getBoolean("saver", true);
        dark = computeDark();
        if (!processUsed) {               // fresh process: delete leftover cache files before WebView starts
            processUsed = true;
            WipeService.deleteContents(getCacheDir());
            WipeService.deleteContents(getExternalCacheDir());
        }
        buildUi();
        applyTheme();
        WipeService.wipe(this, wv); // anything left from a previous run
        try { startService(new Intent(this, WipeService.class)); } catch (Throwable ignored) { }
        Tab t = new Tab();
        String in = intentUrl(getIntent());
        if (in != null) t.url = in;
        tabs.add(t);
        cur = -1;
        switchTo(0);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String u = intentUrl(i);
        if (u != null) newTab(u);
    }

    private String intentUrl(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            return i.getData().toString();
        }
        return null;
    }

    @Override protected void onPause() { super.onPause(); wv.onPause(); }
    @Override protected void onResume() { super.onResume(); wv.onResume(); }
    @Override protected void onStop() { super.onStop(); if (isFinishing()) wipe(); }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        wv.clearCache(true);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        wipe();
        holder.removeAllViews();
        wv.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else if (tabs.size() > 1) closeTab(cur);
        else exitAndClear();
    }

    /** Erase cookies, storage, cache, history, form data. */
    void wipe() { WipeService.wipe(this, wv); }

    void exitAndClear() {
        wipe();
        if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish();
    }

    // ------------------------------------------------------------------ UI
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    TextView button(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(dark ? 0xFFE8EAED : 0xFF202124);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMinWidth(dp(44));
        t.setClickable(true);
        return t;
    }

    void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.parseColor("#F1F3F4"));
        top.setPadding(dp(6), 0, dp(2), 0);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(15);
        urlBar.setHint("Search or type address");
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | 0x1000000 /* IME_FLAG_NO_PERSONALIZED_LEARNING */);
        urlBar.setBackgroundColor(Color.WHITE);
        urlBar.setPadding(dp(12), 0, dp(12), 0);
        urlBar.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        top.addView(urlBar, new LinearLayout.LayoutParams(0, dp(40), 1f));

        tabBtn = button("1");
        tabBtn.setOnClickListener(v -> showTabs());
        top.addView(tabBtn, new LinearLayout.LayoutParams(dp(46), dp(48)));

        menuBtn = button("⋮");
        menuBtn.setOnClickListener(v -> showMenu());
        top.addView(menuBtn, new LinearLayout.LayoutParams(dp(40), dp(48)));

        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        holder = new FrameLayout(this);
        root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        createWebView();
    }

    void createWebView() {
        wv = new WebView(this);
        holder.addView(wv, new FrameLayout.LayoutParams(-1, -1));

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setSaveFormData(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, false);

        wv.setDownloadListener((url, ua, cd, mime, len) -> askDownload(url, ua, cd, mime, len));

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                return !("http".equals(sc) || "https".equals(sc)); // ignore app/intent/tel links
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                bar.setProgress(5);
                bar.setVisibility(View.VISIBLE);
                if (!urlBar.hasFocus() && url != null) urlBar.setText(url);
                // data saver: text first, images after the page is mostly there (no "stuck" feeling)
                v.getSettings().setBlockNetworkImage(imagesOff || dataSaver);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                if (!imagesOff) v.getSettings().setBlockNetworkImage(false);
                if (dataSaver) v.evaluateJavascript(LAZY_JS, null);
                if (dark) v.evaluateJavascript(DARK_JS, null);
                if (cur >= 0 && url != null) tabs.get(cur).url = url;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                if (host != null && isBlocked(host.toLowerCase())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                if (dataSaver && !r.isForMainFrame() && "GET".equals(r.getMethod())) {
                    Map<String, String> h = r.getRequestHeaders();
                    String acc = h == null ? null : h.get("Accept");
                    String path = r.getUrl().getPath();
                    String sc = r.getUrl().getScheme();
                    if (acc != null && acc.startsWith("image/") && path != null
                            && ("http".equals(sc) || "https".equals(sc))) {
                        String p = path.toLowerCase();
                        if (!p.endsWith(".svg") && !p.endsWith(".gif") && !p.endsWith(".ico")) {
                            return shrinkImage(r);
                        }
                    }
                }
                return null;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                if (p >= 60 && !imagesOff) v.getSettings().setBlockNetworkImage(false);
                if (dark && p >= 30) v.evaluateJavascript(DARK_JS, null);
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                if (cur >= 0 && title != null) tabs.get(cur).title = title;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
                final WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    boolean done;
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                        open(r.getUrl().toString(), w);
                        return true;
                    }
                    @Override
                    public void onPageStarted(WebView w, String url, Bitmap i) {
                        if (url != null && !"about:blank".equals(url)) open(url, w);
                    }
                    void open(String url, WebView w) {
                        if (done) return;
                        done = true;
                        w.stopLoading();
                        newTab(url);
                        ui.post(w::destroy);
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback c) { c.invoke(o, false, false); }
        });
    }

    /** Make pages lighter: lazy-load images, never autoplay / preload video. */
    static final String LAZY_JS =
            "(function(){function f(){document.querySelectorAll('img').forEach(function(i){" +
            "if(!i.loading)i.loading='lazy';i.decoding='async';});" +
            "document.querySelectorAll('video').forEach(function(v){v.autoplay=false;v.preload='none';});}" +
            "f();if(!window.__lb){window.__lb=1;new MutationObserver(f).observe(document.documentElement," +
            "{childList:true,subtree:true});}})();";

    /** Dark mode for web pages: invert page colours, keep images/video natural. */
    static final String DARK_JS =
            "(function(){if(document.getElementById('__lbd'))return;var s=document.createElement('style');" +
            "s.id='__lbd';s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff}" +
            "img,video,picture,canvas{filter:invert(1) hue-rotate(180deg)!important}';" +
            "(document.head||document.documentElement).appendChild(s);})();";

    static boolean isBlocked(String host) {
        for (String d : BLOCKED) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ image data saver
    WebResourceResponse shrinkImage(WebResourceRequest r) {
        boolean got = false;
        HttpURLConnection c = null;
        try {
            imgSlots.acquire();
            got = true;
            c = (HttpURLConnection) new URL(r.getUrl().toString()).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> e : r.getRequestHeaders().entrySet()) {
                String k = e.getKey();
                if (!"Range".equalsIgnoreCase(k)) c.setRequestProperty(k, e.getValue());
            }
            if (c.getResponseCode() != 200) return null;
            String ct = c.getContentType();
            if (ct == null || !ct.startsWith("image/") || ct.contains("svg") || ct.contains("gif")) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_IMG_BYTES) return null; // too big, let WebView handle it
            }
            byte[] orig = bos.toByteArray();
            String mime = ct.split(";")[0].trim();
            if (orig.length < SKIP_BELOW) return respond(mime, orig);

            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(orig, 0, orig.length, o);
            if (o.outWidth <= 0) return respond(mime, orig);
            int ss = 1;
            while (o.outWidth / (ss * 2) >= MAX_IMG_WIDTH) ss *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = ss;
            Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(orig, 0, orig.length, o);
            if (bm == null) return respond(mime, orig);
            if (bm.getWidth() > MAX_IMG_WIDTH) {
                int h = Math.max(1, bm.getHeight() * MAX_IMG_WIDTH / bm.getWidth());
                Bitmap sc = Bitmap.createScaledBitmap(bm, MAX_IMG_WIDTH, h, true);
                if (sc != bm) bm.recycle();
                bm = sc;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(orig.length / 3);
            Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            bm.compress(fmt, WEBP_QUALITY, out);
            bm.recycle();
            if (out.size() >= orig.length) return respond(mime, orig);
            return respond("image/webp", out.toByteArray());
        } catch (Throwable t) {
            return null; // on any problem fall back to a normal load (never block the page)
        } finally {
            if (c != null) c.disconnect();
            if (got) imgSlots.release();
        }
    }

    WebResourceResponse respond(String mime, byte[] data) {
        WebResourceResponse res = new WebResourceResponse(mime, null, new ByteArrayInputStream(data));
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        res.setResponseHeaders(h);
        return res;
    }

    // ------------------------------------------------------------------ navigation
    void go(String input) {
        input = input.trim();
        if (input.isEmpty()) return;
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (!input.contains(" ") && input.contains(".")) {
            url = "https://" + input;
        } else {
            try {
                url = "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8");
            } catch (Exception e) {
                url = HOME;
            }
        }
        urlBar.clearFocus();
        hideKeyboard();
        wv.loadUrl(url, headers());
    }

    Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        if (dataSaver) h.put("Save-Data", "on");
        h.put("Sec-GPC", "1");
        h.put("DNT", "1");
        return h;
    }

    void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ tabs
    void saveCurrent() {
        if (cur < 0 || cur >= tabs.size()) return;
        Tab t = tabs.get(cur);
        Bundle b = new Bundle();
        wv.saveState(b);
        t.state = b;
        String u = wv.getUrl();
        if (u != null) t.url = u;
    }

    void switchTo(int i) {
        saveCurrent();
        cur = i;
        Tab t = tabs.get(i);
        wv.stopLoading();
        if (t.state == null || wv.restoreState(t.state) == null) {
            wv.loadUrl(t.url, headers());
            wv.clearHistory();
        }
        urlBar.setText(t.url);
        urlBar.clearFocus();
        updateTabCount();
    }

    void newTab(String url) {
        Tab t = new Tab();
        if (url != null) t.url = url;
        tabs.add(t);
        switchTo(tabs.size() - 1);
    }

    void closeTab(int i) {
        if (tabs.size() == 1) {
            Tab t = tabs.get(0);
            t.state = null; t.url = HOME; t.title = "New tab";
            cur = -1;
            switchTo(0);
            return;
        }
        tabs.remove(i);
        if (i < cur) {
            cur--;
        } else if (i == cur) {
            cur = -1; // do not save the closed tab
            switchTo(Math.min(i, tabs.size() - 1));
        }
        updateTabCount();
    }

    void updateTabCount() { tabBtn.setText(String.valueOf(tabs.size())); }

    void showTabs() {
        saveCurrent();
        final AlertDialog[] dlg = new AlertDialog[1];
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText((i == cur ? "● " : "") + t.title);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextSize(16);
            title.setTextColor(dark ? 0xFFE8EAED : 0xFF202124);
            title.setPadding(dp(20), dp(14), dp(8), dp(14));
            title.setOnClickListener(v -> { dlg[0].dismiss(); if (idx != cur) switchTo(idx); });
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = button("✕");
            x.setOnClickListener(v -> { dlg[0].dismiss(); closeTab(idx); showTabs(); });
            row.addView(x, new LinearLayout.LayoutParams(dp(52), dp(48)));
            list.addView(row);
        }
        dlg[0] = new AlertDialog.Builder(this, dialogTheme())
                .setTitle(tabs.size() + " tab" + (tabs.size() == 1 ? "" : "s"))
                .setView(sv)
                .setPositiveButton("New tab", (d, w) -> newTab(null))
                .setNegativeButton("Close", null)
                .create();
        dlg[0].show();
    }

    // ------------------------------------------------------------------ theme
    boolean computeDark() {
        if (darkMode == 1) return true;
        if (darkMode == 2) return false;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    int dialogTheme() {
        return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    void applyTheme() {
        dark = computeDark();
        int bg = dark ? 0xFF202124 : 0xFFFFFFFF;
        int barC = dark ? 0xFF303134 : 0xFFF1F3F4;
        int fg = dark ? 0xFFE8EAED : 0xFF202124;
        root.setBackgroundColor(bg);
        top.setBackgroundColor(barC);
        urlBar.setBackgroundColor(dark ? 0xFF3C4043 : 0xFFFFFFFF);
        urlBar.setTextColor(fg);
        urlBar.setHintTextColor(dark ? 0xFF9AA0A6 : 0xFF80868B);
        tabBtn.setTextColor(fg);
        menuBtn.setTextColor(fg);
        wv.setBackgroundColor(dark ? 0xFF121212 : 0xFFFFFFFF);
        getWindow().setStatusBarColor(barC);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = root.getSystemUiVisibility();
            root.setSystemUiVisibility(dark ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                    : (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean was = dark;
        applyTheme();
        if (was != dark) wv.reload();
    }

    // ------------------------------------------------------------------ downloads
    void askDownload(final String url, final String ua, final String cd, final String mime, long len) {
        String name = URLUtil.guessFileName(url, cd, mime);
        String size = len > 0 ? Formatter.formatFileSize(this, len) : "unknown size";
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Download file?")
                .setMessage(name + "\n" + size)
                .setPositiveButton("Download", (d, w) -> startDownload(url, ua, cd, mime))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void startDownload(String url, String ua, String cd, String mime) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "This download type is not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pDlUrl = url; pDlUa = ua; pDlCd = cd; pDlMime = mime;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 77 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && pDlUrl != null) {
            startDownload(pDlUrl, pDlUa, pDlCd, pDlMime);
        }
        pDlUrl = null;
    }

    // ------------------------------------------------------------------ bookmarks
    JSONArray bookmarks() {
        try { return new JSONArray(prefs.getString("bm", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    void addBookmark() {
        String u = wv.getUrl();
        if (u == null || cur < 0) return;
        try {
            JSONArray a = bookmarks();
            for (int i = 0; i < a.length(); i++) {
                if (u.equals(a.getJSONObject(i).getString("u"))) {
                    Toast.makeText(this, "Already bookmarked", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("t", tabs.get(cur).title);
            o.put("u", u);
            a.put(o);
            prefs.edit().putString("bm", a.toString()).apply();
            Toast.makeText(this, "Bookmark added", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save bookmark", Toast.LENGTH_SHORT).show();
        }
    }

    void removeBookmark(int idx) {
        try {
            JSONArray a = bookmarks(), n = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (i != idx) n.put(a.get(i));
            prefs.edit().putString("bm", n.toString()).apply();
        } catch (Exception ignored) { }
    }

    void showBookmarks() {
        final AlertDialog[] dlg = new AlertDialog[1];
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        final JSONArray a = bookmarks();
        for (int i = 0; i < a.length(); i++) {
            final int idx = i;
            String t, u;
            try { t = a.getJSONObject(i).getString("t"); u = a.getJSONObject(i).getString("u"); }
            catch (Exception e) { continue; }
            final String url = u;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText(t.isEmpty() ? u : t);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextSize(16);
            title.setTextColor(dark ? 0xFFE8EAED : 0xFF202124);
            title.setPadding(dp(20), dp(14), dp(8), dp(14));
            title.setOnClickListener(v -> { dlg[0].dismiss(); wv.loadUrl(url, headers()); });
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = button("✕");
            x.setOnClickListener(v -> { removeBookmark(idx); dlg[0].dismiss(); showBookmarks(); });
            row.addView(x, new LinearLayout.LayoutParams(dp(52), dp(48)));
            list.addView(row);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, dialogTheme()).setTitle("Bookmarks");
        if (a.length() == 0) b.setMessage("No bookmarks yet. Menu → Add bookmark.");
        else b.setView(sv);
        dlg[0] = b.setPositiveButton("Add this page", (d, w) -> addBookmark())
                .setNegativeButton("Close", null).create();
        dlg[0].show();
    }

    // ------------------------------------------------------------------ menu
    void showMenu() {
        String[] dm = {"Auto", "ON", "OFF"};
        String[] items = {
                "New tab",
                "Home (google.com)",
                "Add bookmark",
                "Bookmarks",
                "Reload",
                "Forward",
                "Data saver: " + (dataSaver ? "ON" : "OFF"),
                "Images: " + (imagesOff ? "OFF (text only)" : "ON"),
                "Dark mode: " + dm[darkMode],
                "Clear cache & data now",
                "Clear data & exit"
        };
        new AlertDialog.Builder(this, dialogTheme()).setItems(items, (d, which) -> {
            switch (which) {
                case 0: newTab(null); break;
                case 1: wv.loadUrl(HOME, headers()); break;
                case 2: addBookmark(); break;
                case 3: showBookmarks(); break;
                case 4: wv.reload(); break;
                case 5: if (wv.canGoForward()) wv.goForward(); break;
                case 6:
                    dataSaver = !dataSaver;
                    prefs.edit().putBoolean("saver", dataSaver).apply();
                    Toast.makeText(this, "Data saver " + (dataSaver ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                    wv.reload();
                    break;
                case 7:
                    imagesOff = !imagesOff;
                    wv.getSettings().setBlockNetworkImage(imagesOff);
                    if (imagesOff) Toast.makeText(this, "Images off", Toast.LENGTH_SHORT).show();
                    else wv.reload();
                    break;
                case 8:
                    darkMode = (darkMode + 1) % 3;
                    prefs.edit().putInt("dark", darkMode).apply();
                    applyTheme();
                    Toast.makeText(this, "Dark mode: " + dm[darkMode], Toast.LENGTH_SHORT).show();
                    wv.reload();
                    break;
                case 9:
                    wipe();
                    Toast.makeText(this, "Cache, cookies & storage cleared", Toast.LENGTH_SHORT).show();
                    break;
                case 10: exitAndClear(); break;
            }
        }).show();
    }
}
