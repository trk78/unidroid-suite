package app.unidroid.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Patterns;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote-driven browser. The projector shows nothing but the page and a pointer: the
 * address bar, tabs, history and scrolling live on the phone (UniDroid remote, "Web").
 * Commands arrive through {@link BrowserControl}; the state goes back as JSON.
 *
 * Commands (one per line): open &lt;url or search&gt; · new [url] · tab &lt;id&gt; · close &lt;id&gt; ·
 * back · forward · reload · stop · move &lt;dx&gt; &lt;dy&gt; · click · scroll &lt;dx&gt; &lt;dy&gt; ·
 * zoom in|out|reset · mode desktop|mobile · type &lt;text&gt; · del &lt;n&gt; · enter · exit · state
 *
 * Typing goes straight into the page (no injected key strokes): injected keys land in
 * whatever window has focus and pass through the projector's keyboard, which dropped
 * or mangled them.
 */
public final class BrowserActivity extends Activity implements BrowserControl.Commands {
    private static final int MAX_TABS = 6;
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.120 Safari/537.36";
    private static final String SEARCH = "https://www.google.com/search?q=";

    private static final class Tab {
        final int id;
        final WebView view;
        int progress = 100;

        Tab(int id, WebView view) {
            this.id = id;
            this.view = view;
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private final android.view.KeyCharacterMap keyMap = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);
    private Tab current;
    private int nextId = 1;
    private FrameLayout pages;
    private FrameLayout root;
    private Pointer pointer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private BrowserControl control;
    private SharedPreferences prefs;
    private boolean desktop;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Typing happens on the phone: the projector's keyboard would only cover the page and grab the keys.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        desktop = prefs.getBoolean("desktop", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);
        pages = new FrameLayout(this);
        root.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        pointer = new Pointer(this);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        control = new BrowserControl(this);
        control.start();

        // Reopen the last tabs, or a start page.
        String saved = prefs.getString("tabs", "");
        for (String url : saved.split("\n")) {
            if (!url.isEmpty() && tabs.size() < MAX_TABS) newTab(url, false);
        }
        if (tabs.isEmpty()) newTab(null, false);
        select(tabs.get(Math.min(prefs.getInt("current", 0), tabs.size() - 1)));
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        String url = intent != null ? intent.getStringExtra("url") : null;
        if (url != null && !url.isEmpty()) open(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current != null) current.view.onResume();
        publish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current != null) current.view.onPause();
        save();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        control.stop();
        for (Tab t : tabs) t.view.destroy();
        tabs.clear();
        super.onDestroy();
    }

    /**
     * Back on the TV remote leaves the browser (after a full-screen video). Page history
     * is on the phone's back button. Handled before the page so nothing can swallow it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) leave();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    private void leave() {
        if (fullscreenView != null) {
            exitFullscreen();
            return;
        }
        finish();
        // Back to the launcher, not to whatever app was open before the browser.
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public void command(String line) {
        String cmd = line, raw = "", arg = "";
        int sp = line.indexOf(' ');
        if (sp > 0) {
            cmd = line.substring(0, sp);
            raw = line.substring(sp + 1);
            arg = raw.trim();
        }
        switch (cmd) {
            case "open": open(arg); break;
            case "new": select(newTab(arg.isEmpty() ? null : arg, true)); break;
            case "tab": selectId(parseInt(arg)); break;
            case "close": closeTab(parseInt(arg)); break;
            case "back": if (current.view.canGoBack()) current.view.goBack(); break;
            case "forward": if (current.view.canGoForward()) current.view.goForward(); break;
            case "reload": current.view.reload(); break;
            case "stop": current.view.stopLoading(); break;
            case "move": move(arg); break;
            case "click": click(); break;
            case "scroll": scroll(arg); break;
            case "zoom": zoom(arg); break;
            case "mode": setDesktop("desktop".equals(arg)); break;
            case "type": type(raw); return;
            case "del": delete(Math.max(1, Math.min(200, parseInt(arg)))); return;
            case "enter": key(KeyEvent.KEYCODE_ENTER); return;
            case "exit": exitFullscreen(); leave(); return;
            default: break; // "state" and unknown: just publish
        }
        publish();
    }

    private void open(String input) {
        if (input.isEmpty()) return;
        current.view.loadUrl(toUrl(input));
    }

    /** Address, bare domain, or search words. */
    static String toUrl(String input) {
        String s = input.trim();
        if (s.matches("(?i)^(https?|file|about|data):.*")) return s;
        if (!s.contains(" ") && (Patterns.WEB_URL.matcher(s).matches() || s.matches("^[\\w.-]+:\\d+(/.*)?$"))) {
            return "https://" + s;
        }
        try {
            return SEARCH + URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return SEARCH + s;
        }
    }

    /**
     * Inserts text in the focused field of the page (or of a same-origin frame) like a
     * paste: fires input events, takes accents and emoji. Pages without an editable focus
     * get the characters as key strokes instead (shortcuts, games).
     */
    private void type(String text) {
        if (text.isEmpty()) return;
        current.view.evaluateJavascript(EDIT_JS + "edit('insertText'," + JSONObject.quote(text) + ",1)", ok -> {
            if (!"true".equals(ok)) {
                KeyEvent[] events = keyMap.getEvents(text.toCharArray());
                if (events != null) for (KeyEvent e : events) current.view.dispatchKeyEvent(e);
            }
        });
    }

    private void delete(int n) {
        current.view.evaluateJavascript(EDIT_JS + "edit('delete',null," + n + ")", ok -> {
            if (!"true".equals(ok)) for (int i = 0; i < n; i++) key(KeyEvent.KEYCODE_DEL);
        });
    }

    /** A key stroke delivered to the page itself, as from a keyboard plugged into the projector. */
    private void key(int code) {
        long t = SystemClock.uptimeMillis();
        current.view.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        current.view.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private static final String EDIT_JS = "function edit(c,v,n){var d=document;"
            + "while(d.activeElement&&/^i?frame$/i.test(d.activeElement.tagName)){try{d=d.activeElement.contentDocument}catch(x){break}}"
            + "var e=d.activeElement;if(!e||!(e.isContentEditable||/^(input|textarea)$/i.test(e.tagName)))return false;"
            + "var r=true;for(var i=0;i<n;i++)r=d.execCommand(c,false,v)&&r;return r}";

    private void move(String arg) {
        String[] p = arg.split(" ");
        if (p.length < 2) return;
        pointer.moveBy(parseFloat(p[0]), parseFloat(p[1]));
        hover();
    }

    /** A real mouse click at the pointer (no tap-to-zoom, hover states work). */
    private void click() {
        WebView v = current.view;
        if (pointer.x < 0) pointer.moveBy(0, 0); // never moved yet: click in the middle, not off screen
        float x = pointer.x, y = pointer.y;
        long t = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= 23) {
            dispatchMouse(v, MotionEvent.ACTION_DOWN, t, x, y, MotionEvent.BUTTON_PRIMARY);
            dispatchMouse(v, MotionEvent.ACTION_BUTTON_PRESS, t, x, y, MotionEvent.BUTTON_PRIMARY);
            dispatchMouse(v, MotionEvent.ACTION_BUTTON_RELEASE, t + 40, x, y, 0);
            dispatchMouse(v, MotionEvent.ACTION_UP, t + 40, x, y, 0);
        } else {
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0);
            v.dispatchTouchEvent(down);
            v.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        }
        v.requestFocus();
        pointer.pulse();
        hideKeyboard();
    }

    /** A click in a text field opens the projector's keyboard: close it, the phone types. */
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        Runnable hide = () -> imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        root.postDelayed(hide, 150);
        root.postDelayed(hide, 600);
    }

    private void hover() {
        if (Build.VERSION.SDK_INT < 23) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent e = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, t, pointer.x, pointer.y, 0, 0, 0);
        current.view.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /** Mouse wheel at the pointer: scrolls whatever is under it (inner panels too). */
    private void scroll(String arg) {
        String[] p = arg.split(" ");
        if (p.length < 2) return;
        float dx = parseFloat(p[0]), dy = parseFloat(p[1]);
        long t = SystemClock.uptimeMillis();
        // One wheel "notch" is ~64 px in Chromium: phone pixels → notches.
        MotionEvent e = mouseEvent(MotionEvent.ACTION_SCROLL, t, pointer.x, pointer.y, 0, -dx / 40f, -dy / 40f);
        if (!current.view.dispatchGenericMotionEvent(e)) current.view.scrollBy((int) dx * 2, (int) dy * 2);
        e.recycle();
    }

    private void zoom(String arg) {
        WebView v = current.view;
        if ("in".equals(arg)) v.zoomBy(1.25f);
        else if ("out".equals(arg)) v.zoomBy(0.8f);
        else v.reload();
    }

    private void setDesktop(boolean on) {
        desktop = on;
        prefs.edit().putBoolean("desktop", on).apply();
        for (Tab t : tabs) applyMode(t.view);
        current.view.reload();
    }

    private static void dispatchMouse(WebView v, int action, long time, float x, float y, int buttons) {
        MotionEvent e = mouseEvent(action, time, x, y, buttons, 0, 0);
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            v.dispatchGenericMotionEvent(e);
        } else {
            v.dispatchTouchEvent(e);
        }
        e.recycle();
    }

    private static MotionEvent mouseEvent(int action, long time, float x, float y, int buttons, float hScroll, float vScroll) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = buttons != 0 ? 1f : 0f;
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        MotionEvent e = MotionEvent.obtain(time, time, action, 1, new MotionEvent.PointerProperties[] {props},
                new MotionEvent.PointerCoords[] {coords}, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (Build.VERSION.SDK_INT >= 23 && (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
            try {
                MotionEvent.class.getMethod("setActionButton", int.class).invoke(e, MotionEvent.BUTTON_PRIMARY);
            } catch (Exception ignored) {
                // Hidden setter: Chromium also reads buttonState.
            }
        }
        return e;
    }

    // ------------------------------------------------------------------ tabs

    @SuppressLint("SetJavaScriptEnabled")
    private Tab newTab(String url, boolean focus) {
        if (tabs.size() >= MAX_TABS) {
            // Reuse the oldest tab rather than run out of memory on 1 GB devices.
            Tab oldest = tabs.get(0);
            if (url != null) oldest.view.loadUrl(toUrl(url));
            return oldest;
        }
        WebView v = new WebView(this);
        v.setBackgroundColor(0xFFFFFFFF);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        applyMode(v);
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);

        Tab tab = new Tab(nextId++, v);
        v.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                publish();
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                publish();
                save();
            }
        });
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                tab.progress = p;
                if (p == 100 || p % 25 == 0) publish();
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                publish();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                enterFullscreen(view, cb);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
        tabs.add(tab);
        if (url != null) v.loadUrl(toUrl(url));
        else v.loadDataWithBaseURL("about:blank", startPage(), "text/html", "UTF-8", null);
        if (focus) select(tab);
        return tab;
    }

    private void applyMode(WebView v) {
        v.getSettings().setUserAgentString(desktop ? DESKTOP_UA : null);
    }

    private void select(Tab tab) {
        if (current != null && current != tab) {
            current.view.onPause();
            pages.removeView(current.view);
        }
        current = tab;
        if (tab.view.getParent() == null) pages.addView(tab.view, new FrameLayout.LayoutParams(-1, -1));
        tab.view.onResume();
        tab.view.requestFocus();
        save();
    }

    private void selectId(int id) {
        for (Tab t : tabs) {
            if (t.id == id) {
                select(t);
                return;
            }
        }
    }

    private void closeTab(int id) {
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            if (t.id != id) continue;
            tabs.remove(i);
            if (t == current) {
                pages.removeView(t.view);
                current = null;
                if (tabs.isEmpty()) newTab(null, false);
                select(tabs.get(Math.max(0, Math.min(i, tabs.size()) - 1)));
            }
            t.view.destroy();
            save();
            return;
        }
    }

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback cb) {
        exitFullscreen();
        fullscreenView = view;
        fullscreenCallback = cb;
        root.addView(view, 1, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Tab t : tabs) {
            String u = t.view.getUrl();
            if (u != null && u.startsWith("http")) sb.append(u).append('\n');
        }
        prefs.edit().putString("tabs", sb.toString()).putInt("current", Math.max(0, tabs.indexOf(current))).apply();
    }

    /** Sends tabs, address and navigation state to the phones. */
    private void publish() {
        if (current == null || control == null) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) {
                JSONObject j = new JSONObject();
                String u = t.view.getUrl();
                j.put("id", t.id);
                j.put("title", t.view.getTitle() == null || "about:blank".equals(u) ? "" : t.view.getTitle());
                j.put("url", u == null || "about:blank".equals(u) ? "" : u);
                j.put("active", t == current);
                arr.put(j);
            }
            o.put("tabs", arr);
            o.put("back", current.view.canGoBack());
            o.put("forward", current.view.canGoForward());
            o.put("progress", current.progress);
            o.put("desktop", desktop);
            control.publish(o.toString());
        } catch (Exception ignored) {
            // JSON of plain values cannot fail.
        }
    }

    private String startPage() {
        return "<html><head><meta name=viewport content='width=device-width'><style>"
                + "body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;"
                + "background:#05070c;color:#f4f6fb;font-family:sans-serif}h1{font-size:44px;margin:0 0 12px}"
                + "p{color:#9aa3b5;font-size:22px;margin:0}</style></head><body>"
                + "<h1>" + escape(getString(R.string.browser_start_title)) + "</h1>"
                + "<p>" + escape(getString(R.string.browser_start_sub)) + "</p></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static float parseFloat(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ pointer

    /** Arrow pointer drawn over the page; hidden after a few seconds without movement. */
    private static final class Pointer extends View {
        float x = -1, y = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float scale;
        private float ring;
        private final Runnable hide = () -> animate().alpha(0f).setDuration(400).start();

        Pointer(Activity a) {
            super(a);
            scale = Math.max(a.getResources().getDisplayMetrics().widthPixels,
                    a.getResources().getDisplayMetrics().heightPixels) / 1280f;
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xFF000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f);
            edge.setStrokeJoin(Paint.Join.ROUND);
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 22);
            arrow.lineTo(6, 17);
            arrow.lineTo(10, 26);
            arrow.lineTo(14, 24);
            arrow.lineTo(10, 15);
            arrow.lineTo(17, 15);
            arrow.close();
            setAlpha(0f);
        }

        void moveBy(float dx, float dy) {
            if (x < 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            x = Math.max(0, Math.min(getWidth() - 1, x + dx * scale));
            y = Math.max(0, Math.min(getHeight() - 1, y + dy * scale));
            show();
        }

        void pulse() {
            ring = 1f;
            show();
            invalidate();
            postDelayed(() -> {
                ring = 0;
                invalidate();
            }, 220);
        }

        private void show() {
            if (x < 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            animate().cancel();
            setAlpha(1f);
            removeCallbacks(hide);
            postDelayed(hide, 4000);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (x < 0) return;
            if (ring > 0) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0x660A84FF);
                c.drawCircle(x, y, 18 * scale, p);
            }
            c.save();
            c.translate(x, y);
            c.scale(scale * 1.3f, scale * 1.3f);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
            c.restore();
        }
    }
}
