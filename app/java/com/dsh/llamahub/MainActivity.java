package com.dsh.llamahub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.OsConstants;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String TAG = "llama-server";
    private static final String PREFS = "cfg";
    private static final int REQ_PICK = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final int REQ_EXPORT = 1003;
    private static final int TAB_CHAT = 0;
    private static final int TAB_SETTINGS = 1;
    private static final int TAB_HISTORY = 2;
    private static final int TAB_LOG = 3;

    private WebView web;
    private final TextView[] navItems = new TextView[4];
    private boolean serverRunning;
    private TextView segStart;
    private TextView segStop;
    private TextView status;
    private TextView logView;
    private ScrollView logScroll;
    private ScrollView settingsScroll;

    private LinearLayout historyPage;
    private LinearLayout historyListBox;
    private EditText historySearch;
    private TextView historyHeader;
    private final List<JSONObject> history = new ArrayList<JSONObject>();
    private final List<JSONObject> incomingChats = new ArrayList<JSONObject>();
    private boolean pendingCollect;
    private String pendingOpenTitle;
    private String pendingJs;
    private boolean pendingNewChat;
    private String pendingExport;

    private TextView sysText;
    private TextView ramText;
    private TextView rtText;
    private ProgressBar ramBar;

    private Spinner modeSpin;
    private Spinner ctxSpin;
    private SeekBar nglBar;
    private TextView nglLabel;
    private Spinner faSpin;
    private Spinner ctkSpin;
    private Spinner ctvSpin;
    private Spinner threadsSpin;
    private Spinner batchSpin;
    private Spinner ubatchSpin;
    private Spinner npSpin;
    private Spinner lmSpin;
    private Spinner lvSpin;
    private Spinner reasoningSpin;
    private CheckBox cbCont;
    private CheckBox cbKvOffload;
    private CheckBox cbRepack;
    private CheckBox cbWarmup;
    private CheckBox cbJinja;
    private CheckBox cbMetrics;
    private CheckBox cbStats;
    private CheckBox cbThought;
    private EditText etPort;
    private EditText etTemp;
    private EditText etTopK;
    private EditText etTopP;
    private EditText etMinP;
    private EditText etRepeat;
    private EditText etSeed;
    private EditText extraArgs;
    private TextView modelText;

    private File modelDir;
    private File currentModel;
    private Process proc;
    private volatile boolean stopping;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService dash = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuf = new StringBuilder();
    private boolean logFlushScheduled;

    private final Set<String> supported = new HashSet<String>();
    private final List<String> devices = new ArrayList<String>();
    private String htpLine = "";
    private String gpuLine = "";
    private volatile String placement = "";
    private volatile double tokPerSec = 0;
    private volatile long lastTokens = -1;
    private volatile double lastSeconds = -1;
    private volatile long lastTokensAt = 0;
    private volatile long lastActiveAt = 0;
    private volatile boolean metricsPolling = false;
    private volatile int serverPort = 8080;
    private int customCtx = 131072;
    private String lastStatus = "";

    private final Runnable logFlush = new Runnable() {
        @Override
        public void run() {
            logFlushScheduled = false;
            String text;
            synchronized (logBuf) {
                text = logBuf.toString();
            }
            logView.setText(text);
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    };

    private final Runnable dashTick = new Runnable() {
        @Override
        public void run() {
            updateDashboard();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        M3.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        File ext = getExternalFilesDir(null);
        modelDir = new File(ext != null ? ext : getFilesDir(), "models");
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            Log.w(TAG, "cannot create " + modelDir);
        }
        buildUi();
        getWindow().setStatusBarColor(M3.surface);
        getWindow().setNavigationBarColor(M3.surfaceContainer);
        int vis = getWindow().getDecorView().getSystemUiVisibility();
        if (M3.dark) {
            vis &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(vis);
        restoreState();
        scanModels();

        String intentArgsB64 = getIntent().getStringExtra("args_b64");
        if (intentArgsB64 != null) {
            try {
                extraArgs.setText(new String(android.util.Base64.decode(intentArgsB64, android.util.Base64.NO_WRAP), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        String rawB64 = getIntent().getStringExtra("raw_b64");
        if (rawB64 != null) {
            try {
                runRaw(new String(android.util.Base64.decode(rawB64, android.util.Base64.NO_WRAP), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        String jsB64 = getIntent().getStringExtra("js_b64");
        if (jsB64 != null) {
            try {
                pendingJs = new String(android.util.Base64.decode(jsB64, android.util.Base64.NO_WRAP), "UTF-8");
            } catch (Exception ignored) {
            }
        }

        probeBackend();

        if (getIntent().getBooleanExtra("autostart", false)) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startServer();
                }
            }, 1200);
        }

        ui.post(dashTick);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(M3.surface);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(M3.surface);
        top.setPadding(dp(20), dp(14), dp(8), dp(6));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("Hexagon Chat");
        appTitle.setTextSize(22);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setTextColor(M3.onSurface);
        titleRow.addView(appTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button bNew = M3.button(this, "新对话", M3.TONAL);
        Button bModel = M3.button(this, "模型", M3.TONAL);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(M3.stroke(0x00000000, M3.outline, 20, this));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        segStart = segButton("启动");
        segStop = segButton("停止");
        seg.addView(segStart, new LinearLayout.LayoutParams(0, dp(36), 1f));
        seg.addView(segStop, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(dp(150), dp(42));
        segLp.setMargins(dp(6), 0, dp(4), 0);
        seg.setLayoutParams(segLp);

        bNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_CHAT);
                newChatInWeb();
            }
        });
        bModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelPicker();
            }
        });
        segStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serverRunning) {
                    startServer();
                }
            }
        });
        segStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serverRunning) {
                    stopServerAsync();
                }
            }
        });

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(M3.onSurfaceVariant);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0, dp(2), 0, dp(6));
        status.setText("未启动 · 请选择 GGUF 模型");

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addTopAction(actionRow, bNew);
        addTopAction(actionRow, bModel);
        actionRow.addView(seg);

        top.addView(titleRow);
        top.addView(status);
        top.addView(actionRow);
        root.addView(top);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.addJavascriptInterface(new WebHistoryBridge(), "LlamaHub");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || !url.startsWith("http://127.0.0.1:")) {
                    return;
                }
                view.clearHistory();
                if (pendingCollect) {
                    pendingCollect = false;
                    collectWebChats();
                }
                if (pendingOpenTitle != null) {
                    String t = pendingOpenTitle;
                    pendingOpenTitle = null;
                    openInWeb(t);
                }
                if (pendingJs != null) {
                    String js = pendingJs;
                    pendingJs = null;
                    view.evaluateJavascript(js, null);
                }
                if (pendingNewChat) {
                    pendingNewChat = false;
                    newChatInWeb();
                }
                view.evaluateJavascript(COMPOSER_FIX_JS, null);
                view.evaluateJavascript(cbThought != null && cbThought.isChecked() ? THOUGHT_ON : THOUGHT_OFF, null);
                if (cbStats != null && !cbStats.isChecked()) {
                    view.evaluateJavascript(STATS_HIDE_JS, null);
                }
            }
        });
        web.setBackgroundColor(Color.parseColor("#0f172a"));
        WebView.setWebContentsDebuggingEnabled(true);
        web.loadDataWithBaseURL(null, PLACEHOLDER_HTML, "text/html", "utf-8", null);
        web.setVisibility(View.GONE);

        settingsScroll = new ScrollView(this);
        settingsScroll.setBackgroundColor(M3.surface);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(12), dp(4), dp(12), dp(24));
        buildSettings(settings);
        settingsScroll.addView(settings);

        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTextColor(Color.parseColor("#a7f3d0"));
        logView.setBackgroundColor(Color.parseColor("#0b1020"));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#0b1020"));
        logScroll.addView(logView);
        logScroll.setVisibility(View.GONE);

        historyPage = buildHistoryPage();
        historyPage.setVisibility(View.GONE);

        FrameLayout content = new FrameLayout(this);
        content.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(settingsScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(historyPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(logScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(M3.surfaceContainer);
        nav.setPadding(dp(8), dp(4), dp(8), dp(18));
        String[] labels = {"对话", "设置", "历史", "日志"};
        for (int i = 0; i < labels.length; i++) {
            navItems[i] = M3.navItem(this, labels[i]);
            final int idx = i;
            navItems[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            nav.addView(navItems[i], new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        setRunningUi(false);
        showTab(TAB_SETTINGS);
    }

    private TextView segButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(M3.onSurfaceVariant);
        return v;
    }

    private void setRunningUi(boolean running) {
        serverRunning = running;
        if (segStart == null || segStop == null) {
            return;
        }
        segStart.setBackground(running ? M3.bg(M3.primary, 18, this) : null);
        segStart.setTextColor(running ? M3.onPrimary : M3.onSurfaceVariant);
        segStop.setBackground(!running ? M3.bg(M3.primary, 18, this) : null);
        segStop.setTextColor(!running ? M3.onPrimary : M3.onSurfaceVariant);
    }

    private void addTopAction(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        row.addView(b);
    }

    private void showTab(int tab) {
        web.setVisibility(tab == TAB_CHAT ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        historyPage.setVisibility(tab == TAB_HISTORY ? View.VISIBLE : View.GONE);
        logScroll.setVisibility(tab == TAB_LOG ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.length; i++) {
            if (navItems[i] == null) {
                continue;
            }
            boolean sel = i == tab;
            navItems[i].setTextColor(sel ? M3.onSecondaryContainer : M3.onSurfaceVariant);
            navItems[i].setBackground(sel ? M3.bg(M3.secondaryContainer, 20, this) : null);
        }
        if (tab == TAB_CHAT && proc != null && proc.isAlive()) {
            String u = web.getUrl();
            String base = "http://127.0.0.1:" + serverPort;
            if (u == null || !u.startsWith(base)) {
                web.loadUrl(base + "/");
            } else {
                web.evaluateJavascript(COMPOSER_FIX_JS, null);
            }
        }
        if (tab == TAB_HISTORY) {
            refreshHistory();
            collectWebChats();
        }
    }

    private LinearLayout buildHistoryPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(M3.surface);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(2));
        historyHeader = new TextView(this);
        historyHeader.setTextSize(13);
        historyHeader.setTypeface(Typeface.DEFAULT_BOLD);
        historyHeader.setTextColor(M3.onSurface);
        historyHeader.setText("历史对话");
        header.addView(historyHeader, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(makeButton("导入", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importChats();
            }
        }));
        header.addView(makeButton("导出", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportAll();
            }
        }));
        header.addView(makeButton("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectWebChats();
            }
        }));
        page.addView(header);

        historySearch = new EditText(this);
        historySearch.setSingleLine(true);
        historySearch.setTextSize(12);
        historySearch.setHint("搜索对话内容…");
        historySearch.setInputType(InputType.TYPE_CLASS_TEXT);
        historySearch.setPadding(dp(14), 0, dp(14), 0);
        historySearch.setBackground(M3.bg(M3.surfaceContainerHigh, 12, this));
        historySearch.setTextColor(M3.onSurface);
        historySearch.setHintTextColor(M3.onSurfaceVariant);
        historySearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refreshHistory();
            }
        });
        page.addView(historySearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView hint = new TextView(this);
        hint.setText("点击打开并继续；长按可查看 / 删除");
        hint.setTextSize(11);
        hint.setTextColor(M3.onSurfaceVariant);
        hint.setPadding(dp(14), dp(2), dp(14), dp(4));
        page.addView(hint);

        ScrollView sv = new ScrollView(this);
        historyListBox = new LinearLayout(this);
        historyListBox.setOrientation(LinearLayout.VERTICAL);
        historyListBox.setPadding(dp(8), dp(4), dp(8), dp(16));
        sv.addView(historyListBox);
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void refreshHistory() {
        if (historyListBox == null) {
            return;
        }
        historyListBox.removeAllViews();
        String q = historySearch != null
                ? historySearch.getText().toString().trim().toLowerCase(Locale.US) : "";
        int shown = 0;
        int total;
        synchronized (history) {
            total = history.size();
            for (int i = 0; i < history.size(); i++) {
                final JSONObject e = history.get(i);
                StringBuilder hayB = new StringBuilder(e.optString("title", ""));
                JSONArray hm = e.optJSONArray("messages");
                if (hm != null) {
                    for (int hi = 0; hi < hm.length(); hi++) {
                        JSONObject hmo = hm.optJSONObject(hi);
                        if (hmo != null) {
                            hayB.append(' ').append(contentText(hmo.opt("content")));
                        }
                    }
                }
                String hay = hayB.toString().toLowerCase(Locale.US);
                if (!q.isEmpty() && !hay.contains(q)) {
                    continue;
                }
                shown++;
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setBackground(M3.bg(M3.surfaceContainer, 16, this));
                item.setPadding(dp(16), dp(12), dp(16), dp(12));
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ilp.setMargins(0, dp(4), 0, dp(4));
                item.setLayoutParams(ilp);

                TextView title = new TextView(this);
                title.setText(e.optString("title", "（空对话）"));
                title.setTextSize(13);
                title.setTextColor(M3.onSurface);
                title.setMaxLines(2);
                item.addView(title);

                TextView meta = new TextView(this);
                String mdl = e.optString("model", "");
                meta.setText(formatTime(e.optLong("updated", 0)) + " · " + turns(e) + " 轮"
                        + (mdl.isEmpty() ? "" : " · " + shorten(mdl, 34)));
                meta.setTextSize(10);
                meta.setTextColor(M3.onSurfaceVariant);
                meta.setPadding(0, dp(4), 0, 0);
                item.addView(meta);

                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTab(TAB_CHAT);
                        openInWeb(e.optString("title", ""));
                    }
                });
                item.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(e.optString("title", "对话"))
                                .setItems(new String[]{"查看对话", "导出对话", "删除对话"}, new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        if (which == 0) {
                                            showHistoryDetail(e);
                                        } else if (which == 1) {
                                            exportOne(e);
                                        } else {
                                            confirmDelete(e);
                                        }
                                    }
                                })
                                .show();
                        return true;
                    }
                });
                historyListBox.addView(item);
            }
        }
        historyHeader.setText("历史对话（" + shown + "/" + total + "）");
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("没有读到 WebUI 会话。先启动服务，在「对话」里聊几句，再回来点「刷新」。");
            empty.setTextSize(11);
            empty.setTextColor(M3.onSurfaceVariant);
            empty.setPadding(dp(12), dp(24), dp(12), dp(12));
            historyListBox.addView(empty);
        }
    }

    private JSONObject convJson(JSONObject e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", e.optString("title", ""));
        o.put("updated", e.optLong("updated", 0));
        o.put("model", e.optString("model", ""));
        JSONArray out = new JSONArray();
        JSONArray msgs = e.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                JSONObject x = new JSONObject();
                x.put("role", m.optString("role", "user"));
                x.put("content", m.optString("content", ""));
                out.put(x);
            }
        }
        o.put("messages", out);
        return o;
    }

    private void exportAll() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (history) {
                for (int i = 0; i < history.size(); i++) {
                    arr.put(convJson(history.get(i)));
                }
            }
            pendingExport = arr.toString(2);
            startCreateDocument("llama-chats.json");
        } catch (Exception e) {
            toast("导出失败: " + e.getMessage());
        }
    }

    private void exportOne(JSONObject e) {
        try {
            pendingExport = convJson(e).toString(2);
            String name = e.optString("title", "chat").replaceAll("[^A-Za-z0-9._-]", "_");
            if (name.isEmpty()) {
                name = "chat";
            }
            startCreateDocument(name + ".json");
        } catch (Exception ex) {
            toast("导出失败: " + ex.getMessage());
        }
    }

    private void startCreateDocument(String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("无法创建文件: " + e.getMessage());
        }
    }

    private void importChats() {
        if (!webReady()) {
            toast("启动服务后才能导入");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法打开文件: " + e.getMessage());
        }
    }

    private String readUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("open failed");
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void confirmDelete(final JSONObject e) {
        new AlertDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("从 WebUI 存储中删除「" + e.optString("title", "") + "」？此操作不可恢复。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (!webReady()) {
                            toast("启动服务后才能删除");
                            return;
                        }
                        web.evaluateJavascript(
                                DELETE_JS.replace("__ID__", JSONObject.quote(e.optString("id"))), null);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int turns(JSONObject e) {
        JSONArray m = e.optJSONArray("messages");
        if (m == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < m.length(); i++) {
            JSONObject o = m.optJSONObject(i);
            if (o != null && "user".equals(o.optString("role"))) {
                n++;
            }
        }
        return n;
    }

    private String formatTime(long ms) {
        if (ms <= 0) {
            return "";
        }
        return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date(ms));
    }

    private String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private void showHistoryDetail(JSONObject e) {
        StringBuilder sb = new StringBuilder();
        JSONArray msgs = e.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                String role = m.optString("role", "");
                String label = "user".equals(role) ? "用户" : ("assistant".equals(role) ? "助手" : "系统");
                sb.append(label).append(":\n").append(m.optString("content", "")).append("\n\n");
            }
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(M3.onSurface);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(e.optString("title", "对话"))
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
        if (!M3.dark && dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(M3.bg(M3.surfaceContainer, 28, this));
        }
    }

    private String contentText(Object c) {
        if (c == null || c == JSONObject.NULL) {
            return "";
        }
        if (c instanceof String) {
            return (String) c;
        }
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) c;
            for (int i = 0; i < a.length(); i++) {
                Object o = a.opt(i);
                if (o instanceof JSONObject) {
                    String t = ((JSONObject) o).optString("text", "");
                    if (!t.isEmpty()) {
                        sb.append(t);
                    }
                } else if (o instanceof String) {
                    sb.append((String) o);
                }
            }
            return sb.toString();
        }
        return String.valueOf(c);
    }

    private String answerText(String resp) {
        if (resp == null) {
            return "";
        }
        String t = resp.trim();
        if (t.isEmpty()) {
            return "";
        }
        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                JSONArray ch = o.optJSONArray("choices");
                if (ch != null && ch.length() > 0) {
                    JSONObject c0 = ch.getJSONObject(0);
                    JSONObject msg = c0.optJSONObject("message");
                    if (msg != null) {
                        return contentText(msg.opt("content"));
                    }
                    return c0.optString("text", "");
                }
            } catch (Exception ignored) {
            }
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = t.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String d = line.substring(5).trim();
            if (d.isEmpty() || "[DONE]".equals(d)) {
                continue;
            }
            try {
                JSONObject o = new JSONObject(d);
                JSONArray ch = o.optJSONArray("choices");
                if (ch == null || ch.length() == 0) {
                    continue;
                }
                JSONObject c0 = ch.getJSONObject(0);
                JSONObject delta = c0.optJSONObject("delta");
                if (delta != null) {
                    String s = contentText(delta.opt("content"));
                    if (!s.isEmpty()) {
                        sb.append(s);
                    }
                } else {
                    JSONObject msg = c0.optJSONObject("message");
                    if (msg != null) {
                        sb.append(contentText(msg.opt("content")));
                    } else {
                        sb.append(c0.optString("text", ""));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    private void recordChat(String reqBody, String respText) {
        try {
            if (reqBody == null || reqBody.isEmpty()) {
                return;
            }
            JSONObject req = new JSONObject(reqBody);
            JSONArray in = req.optJSONArray("messages");
            if (in == null) {
                return;
            }
            String model = req.optString("model", "");
            if (model.isEmpty() && currentModel != null) {
                model = currentModel.getName();
            }
            JSONArray conv = new JSONArray();
            StringBuilder search = new StringBuilder();
            String firstUser = "";
            for (int i = 0; i < in.length(); i++) {
                JSONObject m = in.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                String role = m.optString("role", "user");
                String content = contentText(m.opt("content"));
                if (content.isEmpty()) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("role", role);
                o.put("content", content);
                conv.put(o);
                search.append(content).append('\n');
                if (firstUser.isEmpty() && "user".equals(role)) {
                    firstUser = content;
                }
            }
            if (conv.length() == 0) {
                return;
            }
            String ans = answerText(respText);
            if (!ans.isEmpty()) {
                JSONObject last = conv.optJSONObject(conv.length() - 1);
                if (last == null || !"assistant".equals(last.optString("role"))) {
                    JSONObject o = new JSONObject();
                    o.put("role", "assistant");
                    o.put("content", ans);
                    conv.put(o);
                    search.append(ans);
                }
            }
            String id = Integer.toHexString((model + "|" + firstUser).hashCode());
            JSONObject entry = new JSONObject();
            entry.put("id", id);
            entry.put("title", titleOf(firstUser));
            entry.put("model", model);
            entry.put("updated", System.currentTimeMillis());
            entry.put("messages", conv);
            entry.put("search", search.toString().toLowerCase(Locale.US));
            HistoryStore.upsert(MainActivity.this, entry);
            loadHistory();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (historyPage != null && historyPage.getVisibility() == View.VISIBLE) {
                        refreshHistory();
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "recordChat: " + e);
        }
    }

    private String titleOf(String text) {
        String t = text == null ? "" : text.trim().replace('\n', ' ');
        if (t.length() > 42) {
            t = t.substring(0, 42) + "…";
        }
        return t.isEmpty() ? "（空对话）" : t;
    }

    private void loadHistory() {
        try {
            List<JSONObject> list = HistoryStore.load(this);
            synchronized (history) {
                history.clear();
                history.addAll(list);
            }
        } catch (Exception e) {
            Log.w(TAG, "loadHistory: " + e);
        }
    }

    private void saveHistory() {
        synchronized (history) {
            HistoryStore.save(this, new ArrayList<JSONObject>(history));
        }
    }

    private boolean webReady() {
        String u = web.getUrl();
        return u != null && u.startsWith("http://127.0.0.1:" + serverPort);
    }

    private void collectWebChats() {
        if (!webReady()) {
            pendingCollect = true;
            if (proc != null && proc.isAlive()) {
                web.loadUrl("http://127.0.0.1:" + serverPort + "/");
            } else {
                toast("启动服务后才能读取 WebUI 会话");
            }
            return;
        }
        incomingChats.clear();
        web.evaluateJavascript(COLLECT_JS, null);
    }

    private void newChatInWeb() {
        if (!webReady()) {
            pendingNewChat = true;
            if (proc != null && proc.isAlive()) {
                web.loadUrl("http://127.0.0.1:" + serverPort + "/");
            } else {
                toast("启动服务后才能新建对话");
            }
            return;
        }
        web.evaluateJavascript(NEW_CHAT_JS, null);
    }

    private void openInWeb(String title) {
        if (title == null || title.isEmpty()) {
            return;
        }
        if (!webReady()) {
            pendingOpenTitle = title;
            if (proc != null && proc.isAlive()) {
                web.loadUrl("http://127.0.0.1:" + serverPort + "/");
            } else {
                toast("启动服务后才能打开会话");
                pendingOpenTitle = null;
            }
            return;
        }
        web.evaluateJavascript(OPEN_JS.replace("__TITLE__", JSONObject.quote(title)), null);
    }

    public class WebHistoryBridge {
        @JavascriptInterface
        public void onChatRecord(String json) {
            try {
                incomingChats.add(new JSONObject(json));
            } catch (Exception e) {
                Log.w(TAG, "chat record: " + e);
            }
        }

        @JavascriptInterface
        public void onProbe(String s) {
            Log.i(TAG, "probe: " + s);
        }

        @JavascriptInterface
        public void onChatsDone(int count) {
            Log.i(TAG, "chats done: " + count);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (history) {
                        history.clear();
                        history.addAll(incomingChats);
                        incomingChats.clear();
                    }
                    refreshHistory();
                }
            });
        }

        @JavascriptInterface
        public void onOpenChat(String result) {
            if (!"ok".equals(result)) {
                toast("没能自动打开，请在 WebUI 左侧列表手动选择该会话");
            }
        }

        @JavascriptInterface
        public void onNewChat(String result) {
            if (!"ok".equals(result)) {
                toast("请在 WebUI 左侧点「New chat」");
            }
        }

        @JavascriptInterface
        public void onImported(String result) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    toast(result.startsWith("ok") ? "导入完成" : "导入失败: " + result);
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            collectWebChats();
                        }
                    }, 800);
                }
            });
        }

        @JavascriptInterface
        public void onDeleted(String result) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    toast("ok".equals(result) ? "已删除" : "删除失败");
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            collectWebChats();
                        }
                    }, 800);
                }
            });
        }
    }

    private Button makeButton(String text, View.OnClickListener l) {
        int style = M3.TONAL;
        if ("启动".equals(text) || "启动服务".equals(text)) {
            style = M3.FILLED;
        } else if ("停止".equals(text) || "停止服务".equals(text)) {
            style = M3.OUTLINED;
        } else if ("选择模型".equals(text) || "选择 GGUF 模型".equals(text)) {
            style = M3.TEXT;
        }
        Button b = M3.button(this, text, style);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private TextView cardTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(M3.primary);
        t.setPadding(dp(4), dp(16), dp(4), dp(6));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(M3.bg(M3.surfaceContainer, 16, this));
        c.setPadding(dp(16), dp(10), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10);
        t.setTextColor(M3.onSurfaceVariant);
        t.setPadding(dp(2), dp(2), dp(2), dp(4));
        return t;
    }

    private void addRow(LinearLayout parent, String label, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(M3.onSurface);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(control, new LinearLayout.LayoutParams(
                dp(170), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    private Spinner spinner(String[] items, int sel) {
        Spinner s = new Spinner(this);
        s.setBackground(M3.stroke(M3.surface, M3.outlineVariant, 12, this));
        s.setPadding(dp(12), 0, dp(8), 0);
        s.setMinimumHeight(dp(44));
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(M3.onSurface);
                v.setTextSize(14);
                return v;
            }

            @Override
            public android.view.View getDropDownView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(M3.onSurface);
                v.setTextSize(14);
                v.setBackgroundColor(M3.surfaceContainer);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        if (sel >= 0 && sel < items.length) {
            s.setSelection(sel);
        }
        return s;
    }

    private EditText edit(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setBackground(M3.bg(M3.surfaceContainerHigh, 12, this));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setMinimumHeight(dp(44));
        e.setTextColor(M3.onSurface);
        e.setHintTextColor(M3.onSurfaceVariant);
        return e;
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(14);
        c.setTextColor(M3.onSurface);
        c.setButtonTintList(ColorStateList.valueOf(M3.primary));
        c.setChecked(on);
        c.setPadding(0, dp(4), 0, dp(4));
        return c;
    }

    private void buildSettings(LinearLayout parent) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        parent.addView(cardTitle("仪表盘"));
        LinearLayout sysCard = card();
        sysText = new TextView(this);
        sysText.setTextSize(11);
        sysText.setTextColor(M3.onSurface);
        sysText.setLineSpacing(0, 1.15f);
        sysCard.addView(sysText);

        ramText = new TextView(this);
        ramText.setTextSize(11);
        ramText.setTextColor(M3.onSurface);
        ramText.setPadding(0, dp(6), 0, 0);
        sysCard.addView(ramText);
        ramBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        ramBar.setMax(100);
        sysCard.addView(ramBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        rtText = new TextView(this);
        rtText.setTextSize(11);
        rtText.setTextColor(M3.onSurface);
        rtText.setPadding(0, dp(6), 0, 0);
        sysCard.addView(rtText);
        parent.addView(sysCard);

        parent.addView(cardTitle("推理参数"));
        LinearLayout inf = card();

        modeSpin = spinner(new String[]{"自动混合", "NPU(HTP0)", "GPU(OpenCL)", "纯 CPU"},
                sp.getInt("mode", 0));
        addRow(inf, "后端模式", modeSpin);

        int savedCtx = sp.getInt("ctx", 4096);
        int ctxIdx = ctxIndex(savedCtx);
        if (ctxIdx < 0) {
            customCtx = savedCtx;
            ctxIdx = 6;
        }
        ctxSpin = spinner(new String[]{"2048", "4096", "8192", "16384", "32768", "65536", "自定义…"}, ctxIdx);
        addRow(inf, "上下文长度", ctxSpin);
        ctxSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 6) {
                    promptCustomCtx();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        inf.addView(note("超过模型训练长度时需配合 RoPE 缩放（额外参数 --rope-scale）。"));

        LinearLayout nglRow = new LinearLayout(this);
        nglRow.setOrientation(LinearLayout.VERTICAL);
        nglLabel = new TextView(this);
        nglLabel.setTextSize(12);
        nglLabel.setTextColor(M3.onSurface);
        nglLabel.setText("GPU/NPU 层数：99（全部）");
        nglRow.addView(nglLabel);
        nglBar = new SeekBar(this);
        nglBar.setProgressTintList(ColorStateList.valueOf(M3.primary));
        nglBar.setThumbTintList(ColorStateList.valueOf(M3.primary));
        nglBar.setMax(99);
        nglBar.setProgress(sp.getInt("ngl", 99));
        nglBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                nglLabel.setText("GPU/NPU 层数：" + p + (p >= 99 ? "（全部）" : ""));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        nglRow.addView(nglBar);
        inf.addView(nglRow);

        faSpin = spinner(new String[]{"auto", "on", "off"}, sp.getInt("fa", 0));
        addRow(inf, "Flash Attention", faSpin);

        ctkSpin = spinner(new String[]{"f16", "q8_0", "q4_0", "q4_1", "q5_1", "bf16", "f32"}, sp.getInt("ctk", 0));
        addRow(inf, "KV 类型 K", ctkSpin);
        ctvSpin = spinner(new String[]{"f16", "q8_0", "q4_0", "q4_1", "q5_1", "bf16", "f32"}, sp.getInt("ctv", 0));
        addRow(inf, "KV 类型 V", ctvSpin);
        inf.addView(note("KV 量化（q8_0/q4_0）显著省显存，长上下文收益最大。"));

        threadsSpin = spinner(new String[]{"自动", "2", "4", "6", "8"}, sp.getInt("threads", 0));
        addRow(inf, "CPU 线程", threadsSpin);
        batchSpin = spinner(new String[]{"256", "512", "1024", "2048", "4096"}, sp.getInt("batch", 3));
        addRow(inf, "batch（-b）", batchSpin);
        ubatchSpin = spinner(new String[]{"64", "128", "256", "512", "1024"}, sp.getInt("ubatch", 3));
        addRow(inf, "ubatch（-ub）", ubatchSpin);
        npSpin = spinner(new String[]{"1", "2", "3", "4", "6", "8"}, sp.getInt("np", 3));
        addRow(inf, "并行槽（-np）", npSpin);

        cbCont = check("连续批处理（cont-batching）", sp.getBoolean("cont", true));
        inf.addView(cbCont);
        parent.addView(inf);

        parent.addView(cardTitle("内存 / 加载"));
        LinearLayout mem = card();
        lmSpin = spinner(new String[]{"auto", "mmap", "mlock", "mmap+mlock", "none", "dio"}, sp.getInt("lm", 0));
        addRow(mem, "加载模式", lmSpin);
        mem.addView(note("auto=默认 mmap；mlock / mmap+mlock = 全部驻留内存并禁止换出/压缩（Android 受 RLIMIT_MEMLOCK 限制，失败会打印警告）；none=普通读入；dio=DirectIO。"));

        cbKvOffload = check("KV cache 卸载到设备", sp.getBoolean("kvoff", true));
        mem.addView(cbKvOffload);
        cbRepack = check("权重重排（repack，加速）", sp.getBoolean("repack", true));
        mem.addView(cbRepack);
        cbWarmup = check("启动时预热（warmup）", sp.getBoolean("warmup", true));
        mem.addView(cbWarmup);
        parent.addView(mem);

        parent.addView(cardTitle("采样 / 对话"));
        LinearLayout samp = card();
        etTemp = edit("0.80", sp.getString("temp", "0.80"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addRow(samp, "temperature", etTemp);
        etTopK = edit("40", sp.getString("topk", "40"), InputType.TYPE_CLASS_NUMBER);
        addRow(samp, "top-k", etTopK);
        etTopP = edit("0.95", sp.getString("topp", "0.95"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addRow(samp, "top-p", etTopP);
        etMinP = edit("0.05", sp.getString("minp", "0.05"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addRow(samp, "min-p", etMinP);
        etRepeat = edit("1.00", sp.getString("repeat", "1.00"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addRow(samp, "repeat-penalty", etRepeat);
        etSeed = edit("-1", sp.getString("seed", "-1"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        addRow(samp, "seed", etSeed);

        reasoningSpin = spinner(new String[]{"auto", "on", "off"}, sp.getInt("reasoning", 0));
        addRow(samp, "思考模式", reasoningSpin);
        cbJinja = check("使用 Jinja 模板（--jinja）", sp.getBoolean("jinja", true));
        samp.addView(cbJinja);
        parent.addView(samp);

        parent.addView(cardTitle("对话显示"));
        LinearLayout disp = card();
        cbStats = check("显示消息统计（tokens / 耗时 / 速度）", sp.getBoolean("stats", false));
        cbStats.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (!webReady()) {
                    return;
                }
                if (checked) {
                    web.reload();
                } else {
                    web.evaluateJavascript(STATS_HIDE_JS, null);
                }
            }
        });
        disp.addView(cbStats);
        disp.addView(note("统计来自 WebUI，数字是 prompt/生成 token 与耗时；关掉可避免把 prefill 速度误当成回答速度。"));
        cbThought = check("始终展开思考过程", sp.getBoolean("thought", true));
        disp.addView(cbThought);
        disp.addView(note("开启后，模型开始回答时思考块也不会自动折叠。"));
        parent.addView(disp);

        parent.addView(cardTitle("服务 / 日志"));
        LinearLayout srv = card();
        etPort = edit("8080", sp.getString("port", "8080"), InputType.TYPE_CLASS_NUMBER);
        addRow(srv, "端口", etPort);
        cbMetrics = check("启用 /metrics（仪表盘速率）", sp.getBoolean("metrics", true));
        srv.addView(cbMetrics);
        lvSpin = spinner(new String[]{"1", "2", "3", "4", "5"}, sp.getInt("lv", 2));
        addRow(srv, "日志级别 -lv", lvSpin);
        parent.addView(srv);

        parent.addView(cardTitle("模型"));
        LinearLayout mdl = card();
        modelText = new TextView(this);
        modelText.setTextSize(11);
        modelText.setTextColor(M3.onSurface);
        modelText.setText("未选择");
        mdl.addView(modelText);
        Button pick = makeButton("模型管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelPicker();
            }
        });
        mdl.addView(pick);
        mdl.addView(note("也可 adb push 到 /sdcard/Android/data/com.dsh.llamahub/files/models/"));
        parent.addView(mdl);

        parent.addView(cardTitle("额外参数"));
        LinearLayout ext = card();
        extraArgs = edit("例如 --rope-scale 2", sp.getString("extra", ""), InputType.TYPE_CLASS_TEXT);
        ext.addView(extraArgs);
        ext.addView(note("原样追加到命令行最后，可覆盖上面的选项。"));
        parent.addView(ext);


    }

    private int ctxIndex(int ctx) {
        int[] vals = {2048, 4096, 8192, 16384, 32768, 65536};
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == ctx) {
                return i;
            }
        }
        return -1;
    }

    private void promptCustomCtx() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(customCtx));
        new AlertDialog.Builder(this)
                .setTitle("自定义上下文长度")
                .setView(e)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            customCtx = Integer.parseInt(e.getText().toString().trim());
                            ctxSpin.setSelection(6);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreState() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = sp.getString("last_model", null);
        if (last != null) {
            File f = new File(last);
            if (f.isFile()) {
                currentModel = f;
                setStatus("就绪 · " + f.getName());
            }
        }
        if (modelText != null) {
            modelText.setText(currentModel != null ? currentModel.getAbsolutePath() : "未选择");
        }
    }

    private void scanModels() {
        File[] files = modelDir.listFiles();
        if (files == null) {
            return;
        }
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".gguf")) {
                list.add(f);
            }
        }
        if (list.isEmpty()) {
            return;
        }
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        if (currentModel == null) {
            currentModel = list.get(0);
            setStatus("就绪 · " + currentModel.getName());
        }
        if (modelText != null) {
            modelText.setText(currentModel != null ? currentModel.getAbsolutePath() : "未选择");
        }
    }

    private void setStatus(String s) {
        lastStatus = s;
        status.setText(s);
    }

    private void probeBackend() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<String> help = execCapture(new String[]{"--help"}, 8000);
                synchronized (supported) {
                    for (String line : help) {
                        String t = line.trim();
                        if (!t.startsWith("-")) {
                            continue;
                        }
                        for (String tok : t.split("[\\s,]+")) {
                            if (tok.startsWith("-")) {
                                supported.add(tok);
                            }
                        }
                    }
                }
                List<String> dev = execCapture(new String[]{"--list-devices"}, 8000);
                for (String line : dev) {
                    String t = line.trim();
                    if (t.contains(":")) {
                        String id = t.substring(0, t.indexOf(':')).trim();
                        if (!id.isEmpty() && !id.contains(" ")) {
                            synchronized (devices) {
                                devices.add(id);
                            }
                            if (id.startsWith("HTP")) {
                                htpLine = t;
                            } else if (id.toUpperCase(Locale.US).contains("GPU") || t.contains("Adreno")) {
                                gpuLine = t;
                            }
                        }
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyCaps();
                        updateSystemInfo();
                    }
                });
            }
        });
    }

    private List<String> execCapture(String[] args, int timeoutMs) {
        List<String> out = new ArrayList<String>();
        Process p = null;
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(getApplicationInfo().nativeLibraryDir + "/libllamaserver.so");
            cmd.addAll(Arrays.asList(args));
            p = newBuilder(cmd).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while ((line = br.readLine()) != null && System.currentTimeMillis() < deadline) {
                out.add(line);
            }
            if (System.currentTimeMillis() >= deadline) {
                p.destroyForcibly();
            }
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(TAG, "execCapture failed: " + e);
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        return out;
    }

    private boolean has(String flag) {
        synchronized (supported) {
            return supported.isEmpty() || supported.contains(flag);
        }
    }

    private void applyCaps() {
        hideControl(cbCont, !has("-cb") && !has("--cont-batching"));
        hideControl(lmSpin, !has("-lm") && !has("--load-mode"));
        hideControl(cbKvOffload, !has("-nkvo") && !has("--no-kv-offload"));
        hideControl(cbRepack, !has("-nr") && !has("--no-repack"));
        hideControl(cbWarmup, !has("--no-warmup"));
        hideControl(cbJinja, !has("--no-jinja"));
        hideControl(cbMetrics, !has("--metrics"));
        hideControl(faSpin, !has("-fa") && !has("--flash-attn"));
        hideControl(ctkSpin, !has("-ctk"));
        hideControl(ctvSpin, !has("-ctv"));
        hideControl(npSpin, !has("-np"));
        hideControl(reasoningSpin, !has("--reasoning"));
    }

    private void hideControl(View v, boolean hide) {
        if (v == null || !hide) {
            return;
        }
        if (v instanceof CheckBox) {
            v.setVisibility(View.GONE);
            return;
        }
        android.view.ViewParent p = v.getParent();
        if (p instanceof View) {
            ((View) p).setVisibility(View.GONE);
        } else {
            v.setVisibility(View.GONE);
        }
    }

    private void updateSystemInfo() {
        String soc = "";
        String socMan = "";
        if (Build.VERSION.SDK_INT >= 31) {
            soc = Build.SOC_MODEL;
            socMan = Build.SOC_MANUFACTURER;
        }
        if (soc == null || soc.isEmpty()) {
            soc = Build.BOARD;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("SoC: ").append(soc);
        if (socMan != null && !socMan.isEmpty()) {
            sb.append("（").append(socMan).append("）");
        }
        String arch = htpArch(soc);
        if (!arch.isEmpty()) {
            sb.append(" · HTP ").append(arch);
        }
        sb.append('\n');
        sb.append("NPU: ").append(htpLine.isEmpty() ? "未检测到 HTP" : htpLine).append('\n');
        sb.append("GPU: ").append(gpuLine.isEmpty() ? "未检测到 OpenCL" : gpuLine).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                .append(" · ").append(Build.SUPPORTED_ABIS[0]);
        long page = 0;
        try {
            page = Os.sysconf(OsConstants._SC_PAGESIZE);
        } catch (Exception ignored) {
        }
        if (page > 0) {
            sb.append(" · ").append(page / 1024).append(" KB page");
        }
        sysText.setText(sb.toString());
    }

    private String htpArch(String soc) {
        if (soc == null) {
            return "";
        }
        String s = soc.toUpperCase(Locale.US);
        if (s.contains("SM8750")) return "v79";
        if (s.contains("SM8650")) return "v75";
        if (s.contains("SM8550")) return "v73";
        if (s.contains("SM8850")) return "v81";
        if (s.contains("SM8450")) return "v69";
        if (s.contains("SM8475")) return "v69";
        return "";
    }

    private void updateDashboard() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long totalMb = mi.totalMem / (1024 * 1024);
        long availMb = mi.availMem / (1024 * 1024);
        int pct = totalMb > 0 ? (int) ((totalMb - availMb) * 100 / totalMb) : 0;
        ramBar.setProgress(pct);

        long swapTotal = 0, swapFree = 0, cached = 0;
        try {
            BufferedReader br = new BufferedReader(new java.io.FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    swapTotal = parseKb(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = parseKb(line);
                } else if (line.startsWith("Cached:")) {
                    cached = parseKb(line);
                }
            }
            br.close();
        } catch (Exception ignored) {
        }
        ramText.setText(String.format(Locale.US,
                "RAM: 已用 %d / %d MB（%d%%）· 可用 %d MB\nSwap/ZRAM: 已用 %d / %d MB · Cached %d MB",
                totalMb - availMb, totalMb, pct, availMb,
                swapTotal - swapFree, swapTotal, cached));

        StringBuilder rt = new StringBuilder();
        rt.append("服务: ").append(lastStatus).append('\n');
        if (proc != null && proc.isAlive()) {
            rt.append("速率: ").append((System.currentTimeMillis() - lastActiveAt < 30000 && tokPerSec > 0)
                    ? String.format(Locale.US, "%.1f tok/s", tokPerSec) : "空闲");
            if (!placement.isEmpty()) {
                rt.append('\n').append("落点: ").append(placement);
            }
        }
        rt.append('\n').append("热状态: ").append(thermalText());
        rt.append(" · 电池 ").append(batteryTemp()).append("°C");
        if (proc != null && proc.isAlive() && placement.isEmpty()) {
            rt.append('\n').append("落点: 将日志级别设为 4 后重启可查看");
        }
        rtText.setText(rt.toString());
    }

    private long parseKb(String line) {
        try {
            String[] p = line.trim().split("\\s+");
            return Long.parseLong(p[1]) / 1024;
        } catch (Exception e) {
            return 0;
        }
    }

    private String thermalText() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            int s = pm.getCurrentThermalStatus();
            String[] names = {"无", "轻", "中", "重", "严重", "危急", "关机"};
            return s >= 0 && s < names.length ? names[s] + "(" + s + ")" : String.valueOf(s);
        } catch (Exception e) {
            return "?";
        }
    }

    private double batteryTemp() {
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int t = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                return t / 10.0;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void showModelPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(8));

        TextView imp = new TextView(this);
        imp.setText("＋  导入模型");
        imp.setTextSize(15);
        imp.setTextColor(M3.primary);
        imp.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.addView(imp);

        View div = new View(this);
        div.setBackgroundColor(M3.outlineVariant);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.setMargins(dp(14), 0, dp(14), dp(6));
        box.addView(div, dlp);

        final List<File> models = new ArrayList<File>();
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".gguf")) {
                    models.add(f);
                }
            }
        }
        Collections.sort(models, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        if (models.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无模型，先点「导入模型」");
            empty.setTextSize(13);
            empty.setTextColor(M3.onSurfaceVariant);
            empty.setPadding(dp(14), dp(10), dp(14), dp(10));
            box.addView(empty);
        }

        final AlertDialog[] holder = new AlertDialog[1];
        for (final File f : models) {
            boolean selected = currentModel != null && currentModel.getAbsolutePath().equals(f.getAbsolutePath());
            TextView row = new TextView(this);
            row.setText((selected ? "✓  " : "    ") + f.getName());
            row.setTextSize(14);
            row.setTextColor(selected ? M3.onSecondaryContainer : M3.onSurface);
            row.setBackground(selected ? M3.bg(M3.secondaryContainer, 12, this) : null);
            row.setPadding(dp(14), dp(13), dp(14), dp(13));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectModel(f);
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                }
            });
            box.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("模型")
                .setView(sv)
                .setNegativeButton("取消", null)
                .create();
        holder[0] = dlg;
        dlg.show();
        if (!M3.dark && dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(M3.bg(M3.surfaceContainer, 28, this));
        }
        imp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                pickModel();
            }
        });
    }

    private void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("无法打开文件选择器: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    out.write(pendingExport.getBytes("UTF-8"));
                    out.flush();
                    out.close();
                }
                toast("已导出");
            } catch (Exception e) {
                toast("导出失败: " + e.getMessage());
            }
            return;
        }
        if (requestCode == REQ_IMPORT) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String text = readUri(uri);
                        final JSONArray probe = new JSONArray(text.trim().startsWith("[") ? text : "[" + text + "]");
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                String js = IMPORT_JS.replace("__DATA__", JSONObject.quote(text));
                                web.evaluateJavascript(js, null);
                                toast("正在导入 " + probe.length() + " 个对话…");
                            }
                        });
                    } catch (Exception e) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("不是有效的 JSON 对话文件");
                            }
                        });
                    }
                }
            });
            return;
        }
        if (requestCode != REQ_PICK) {
            return;
        }
        setStatus("复制模型中…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                copyModel(uri);
            }
        });
    }

    private void copyModel(Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            String name = queryName(uri);
            long size = querySize(uri);
            File target = new File(modelDir, sanitize(name));
            if (target.isFile() && size > 0 && target.length() == size) {
                selectModel(target);
                return;
            }
            File part = new File(modelDir, target.getName() + ".part");
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("openInputStream returned null");
            }
            out = new FileOutputStream(part);
            byte[] buf = new byte[1024 * 1024];
            long done = 0;
            long lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > 64L * 1024 * 1024) {
                    lastReport = done;
                    final String msg = String.format(Locale.US, "复制中 %d / %d MB",
                            done / (1024 * 1024), size / (1024 * 1024));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(msg);
                        }
                    });
                }
            }
            out.flush();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "cannot delete old " + target);
            }
            if (!part.renameTo(target)) {
                throw new IOException("rename failed: " + part + " -> " + target);
            }
            selectModel(target);
        } catch (Exception e) {
            Log.e(TAG, "copy failed", e);
            final String msg = "复制失败: " + e.getMessage();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    setStatus(msg);
                    toast(msg);
                }
            });
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private void selectModel(final File f) {
        currentModel = f;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_model", f.getAbsolutePath()).apply();
        ui.post(new Runnable() {
            @Override
            public void run() {
                setStatus("就绪 · " + f.getName());
                if (modelText != null) {
                    modelText.setText(f.getAbsolutePath());
                }
            }
        });
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String s = c.getString(idx);
                    if (s != null && s.length() > 0) {
                        return s;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String s = uri.getLastPathSegment();
        return s != null ? s : "model.gguf";
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    private static String sanitize(String name) {
        String base = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!base.toLowerCase(Locale.US).endsWith(".gguf")) {
            base = base + ".gguf";
        }
        return base;
    }

    private ProcessBuilder newBuilder(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(getFilesDir());
        Map<String, String> env = pb.environment();
        String libDir = getApplicationInfo().nativeLibraryDir;
        env.put("LD_LIBRARY_PATH", libDir + ":/vendor/lib64");
        env.put("ADSP_LIBRARY_PATH", libDir);
        env.put("GGML_HEXAGON_VERBOSE", "1");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        return pb;
    }

    private int currentCtx() {
        if (ctxSpin.getSelectedItemPosition() == 6) {
            return customCtx;
        }
        String s = ctxSpin.getSelectedItem().toString();
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 4096;
        }
    }

    private String nglValue() {
        int mode = modeSpin.getSelectedItemPosition();
        if (mode == 3) {
            return "0";
        }
        int n = nglBar.getProgress();
        return n >= 99 ? "99" : String.valueOf(n);
    }

    private void saveSettings(SharedPreferences sp) {
        SharedPreferences.Editor e = sp.edit();
        e.putInt("mode", modeSpin.getSelectedItemPosition());
        e.putInt("ctx", currentCtx());
        e.putInt("ngl", nglBar.getProgress());
        e.putInt("fa", faSpin.getSelectedItemPosition());
        e.putInt("ctk", ctkSpin.getSelectedItemPosition());
        e.putInt("ctv", ctvSpin.getSelectedItemPosition());
        e.putInt("threads", threadsSpin.getSelectedItemPosition());
        e.putInt("batch", batchSpin.getSelectedItemPosition());
        e.putInt("ubatch", ubatchSpin.getSelectedItemPosition());
        e.putInt("np", npSpin.getSelectedItemPosition());
        e.putInt("lm", lmSpin.getSelectedItemPosition());
        e.putInt("lv", lvSpin.getSelectedItemPosition());
        e.putInt("reasoning", reasoningSpin.getSelectedItemPosition());
        e.putBoolean("cont", cbCont.isChecked());
        e.putBoolean("kvoff", cbKvOffload.isChecked());
        e.putBoolean("repack", cbRepack.isChecked());
        e.putBoolean("warmup", cbWarmup.isChecked());
        e.putBoolean("jinja", cbJinja.isChecked());
        e.putBoolean("metrics", cbMetrics.isChecked());
        e.putBoolean("stats", cbStats.isChecked());
        e.putBoolean("thought", cbThought.isChecked());
        e.putString("port", etPort.getText().toString());
        e.putString("temp", etTemp.getText().toString());
        e.putString("topk", etTopK.getText().toString());
        e.putString("topp", etTopP.getText().toString());
        e.putString("minp", etMinP.getText().toString());
        e.putString("repeat", etRepeat.getText().toString());
        e.putString("seed", etSeed.getText().toString());
        e.putString("extra", extraArgs.getText().toString());
        e.apply();
    }

    private int port() {
        try {
            return Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            return 8080;
        }
    }

    private List<String> buildArgs() {
        List<String> a = new ArrayList<String>();
        a.add("-m");
        a.add(currentModel.getAbsolutePath());
        a.add("--host");
        a.add("127.0.0.1");
        a.add("--port");
        a.add(String.valueOf(serverPort));
        if (has("-c")) {
            a.add("-c");
            a.add(String.valueOf(currentCtx()));
        }
        int mode = modeSpin.getSelectedItemPosition();
        String ngl = nglValue();
        if (has("-ngl") || has("--gpu-layers")) {
            a.add("-ngl");
            a.add(ngl);
        }
        if (mode == 1 && has("-dev")) {
            a.add("-dev");
            a.add("HTP0");
        } else if (mode == 2 && has("-dev")) {
            String gpu = "GPUOpenCL";
            synchronized (devices) {
                for (String d : devices) {
                    if (d.toUpperCase(Locale.US).contains("GPU") || d.toUpperCase(Locale.US).contains("OPENCL")) {
                        gpu = d;
                        break;
                    }
                }
            }
            a.add("-dev");
            a.add(gpu);
        }

        String fa = faSpin.getSelectedItem().toString();
        if (has("-fa") && !"auto".equals(fa)) {
            a.add("-fa");
            a.add(fa);
        }
        String ctk = ctkSpin.getSelectedItem().toString();
        if (has("-ctk") && !"f16".equals(ctk)) {
            a.add("-ctk");
            a.add(ctk);
        }
        String ctv = ctvSpin.getSelectedItem().toString();
        if (has("-ctv") && !"f16".equals(ctv)) {
            a.add("-ctv");
            a.add(ctv);
        }
        int th = threadsSpin.getSelectedItemPosition();
        if (has("-t") && th > 0) {
            a.add("-t");
            a.add(threadsSpin.getSelectedItem().toString());
        }
        if (has("-b")) {
            a.add("-b");
            a.add(batchSpin.getSelectedItem().toString());
        }
        if (has("-ub")) {
            a.add("-ub");
            a.add(ubatchSpin.getSelectedItem().toString());
        }
        if (has("-np")) {
            a.add("-np");
            a.add(npSpin.getSelectedItem().toString());
        }
        int lm = lmSpin.getSelectedItemPosition();
        if (has("-lm") && lm > 0) {
            String[] opts = {"auto", "mmap", "mlock", "mmap+mlock", "none", "dio"};
            a.add("-lm");
            a.add(opts[lm]);
        }
        if (!cbKvOffload.isChecked() && has("-nkvo")) {
            a.add("-nkvo");
        }
        if (!cbRepack.isChecked() && has("-nr")) {
            a.add("-nr");
        }
        if (!cbWarmup.isChecked() && has("--no-warmup")) {
            a.add("--no-warmup");
        }
        if (!cbCont.isChecked() && has("-nocb")) {
            a.add("-nocb");
        }
        if (!cbJinja.isChecked() && has("--no-jinja")) {
            a.add("--no-jinja");
        }
        String r = reasoningSpin.getSelectedItem().toString();
        if (has("--reasoning") && !"auto".equals(r)) {
            a.add("--reasoning");
            a.add(r);
        }
        addIfSet(a, "--temp", etTemp);
        addIfSet(a, "--top-k", etTopK);
        addIfSet(a, "--top-p", etTopP);
        addIfSet(a, "--min-p", etMinP);
        addIfSet(a, "--repeat-penalty", etRepeat);
        addIfSet(a, "-s", etSeed);
        if (cbMetrics.isChecked() && has("--metrics")) {
            a.add("--metrics");
        }
        String lv = lvSpin.getSelectedItem().toString();
        if (has("-lv") && !"3".equals(lv)) {
            a.add("-lv");
            a.add(lv);
        }
        String extra = extraArgs.getText().toString().trim();
        if (!extra.isEmpty()) {
            a.addAll(Arrays.asList(extra.split("\\s+")));
        }
        return a;
    }

    private void addIfSet(List<String> a, String flag, EditText e) {
        if (!has(flag)) {
            return;
        }
        String v = e.getText().toString().trim();
        if (!v.isEmpty()) {
            a.add(flag);
            a.add(v);
        }
    }

    private void startServer() {
        if (proc != null && proc.isAlive()) {
            toast("服务已在运行");
            return;
        }
        if (currentModel == null || !currentModel.isFile()) {
            toast("请先选择 GGUF 模型");
            return;
        }
        final int startMode = modeSpin.getSelectedItemPosition();
        final String ctkSel = ctkSpin.getSelectedItem().toString();
        final String ctvSel = ctvSpin.getSelectedItem().toString();
        if (startMode == 1 && (!"f16".equals(ctkSel) || !"f16".equals(ctvSel))) {
            new AlertDialog.Builder(this)
                    .setTitle("Hexagon 不支持量化 KV")
                    .setMessage("HTP0 后端缺少 SET_ROWS 算子，q4_0 / q8_0 的 KV cache 会导致启动失败。\n\n可以改用 f16，或切到 GPU(OpenCL)。")
                    .setPositiveButton("改用 f16", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            ctkSpin.setSelection(0);
                            ctvSpin.setSelection(0);
                            startServer();
                        }
                    })
                    .setNeutralButton("切到 GPU", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            modeSpin.setSelection(2);
                            startServer();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        saveSettings(getSharedPreferences(PREFS, MODE_PRIVATE));
        serverPort = port();

        final List<String> cmd = new ArrayList<String>();
        cmd.add(getApplicationInfo().nativeLibraryDir + "/libllamaserver.so");
        cmd.addAll(buildArgs());
        stopping = false;
        setStatus("启动中… " + currentModel.getName());
        Log.i(TAG, "exec: " + cmd);
        placement = "";
        tokPerSec = 0;
        lastTokens = -1;
        lastSeconds = -1;

        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    proc = newBuilder(cmd).start();
                } catch (Exception e) {
                    Log.e(TAG, "start failed", e);
                    final String msg = "启动失败: " + e.getMessage();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(msg);
                            setRunningUi(false);
                            toast(msg);
                        }
                    });
                    return;
                }
                startReader();
                watchHealth();
            }
        });
    }

    private void startMetrics() {
        Log.i(TAG, "metrics polling start");
        metricsPolling = true;
        dash.execute(new Runnable() {
            @Override
            public void run() {
                while (metricsPolling) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!metricsPolling) {
                        return;
                    }
                    fetchMetrics();
                }
            }
        });
    }

    private void fetchMetrics() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + serverPort + "/metrics").openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(700);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            long tokens = -1;
            double seconds = -1;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("llamacpp:tokens_predicted_total")) {
                    tokens = (long) Double.parseDouble(line.trim().split("\\s+")[1]);
                } else if (line.startsWith("llamacpp:tokens_predicted_seconds_total")) {
                    seconds = Double.parseDouble(line.trim().split("\\s+")[1]);
                }
            }
            br.close();
            long now = System.currentTimeMillis();
            if (tokens >= 0) {
                if (lastTokens >= 0 && seconds >= 0 && lastSeconds >= 0) {
                    double dtok = tokens - lastTokens;
                    double dsec = seconds - lastSeconds;
                    if (dsec > 0.0001) {
                        tokPerSec = dtok / dsec;
                        if (dtok > 0) {
                            lastActiveAt = now;
                        }
                    }
                }
                lastTokens = tokens;
                lastSeconds = seconds;
                lastTokensAt = now;
            }
        } catch (Exception e) {
            Log.w(TAG, "metrics failed: " + e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Process p = proc;
                if (p == null) {
                    return;
                }
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        appendLog(line);
                        parsePlacement(line);
                    }
                } catch (Exception e) {
                    appendLog("reader end: " + e);
                }
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                appendLog("process exited: " + code);
                metricsPolling = false;
                final int c = code;
                if (!stopping) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("已退出 (" + c + ") · 查看日志");
                            setRunningUi(false);
                        }
                    });
                }
            }
        }, "llama-reader");
        t.setDaemon(true);
        t.start();
    }

    private void parsePlacement(String line) {
        if (!line.contains("model buffer size")) {
            return;
        }
        int i = line.indexOf("model buffer size");
        String head = line.substring(0, i).trim();
        String tail = line.substring(i).replace("model buffer size", "").replace("=", "").replace("MiB", "").trim();
        String[] parts = head.split("\\s+");
        String name = parts.length > 0 ? parts[parts.length - 1] : "";
        try {
            double mib = Double.parseDouble(tail);
            String cur = placement;
            if (cur.isEmpty()) {
                placement = name + " " + String.format(Locale.US, "%.0f", mib) + " MiB";
            } else if (!cur.contains(name)) {
                placement = cur + " · " + name + " " + String.format(Locale.US, "%.0f", mib) + " MiB";
            }
        } catch (Exception ignored) {
        }
    }

    private void watchHealth() {
        for (int i = 0; i < 600; i++) {
            if (stopping) {
                return;
            }
            Process p = proc;
            if (p == null || !p.isAlive()) {
                return;
            }
            if (healthOk()) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("运行中 · http://127.0.0.1:" + serverPort);
                        setRunningUi(true);
                        web.loadUrl("http://127.0.0.1:" + serverPort + "/");
                        if (cbMetrics.isChecked()) {
                            startMetrics();
                        }
                    }
                });
                return;
            }
            if (i > 0 && i % 10 == 0) {
                final int sec = i / 2;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("加载模型/后端中… " + sec + "s");
                    }
                });
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                setStatus("启动超时 (300s) · 查看日志");
                setRunningUi(false);
            }
        });
    }

    private boolean healthOk() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + serverPort + "/health").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void runRaw(final String raw) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<String> cmd = new ArrayList<String>();
                cmd.add(getApplicationInfo().nativeLibraryDir + "/libllamaserver.so");
                for (String s : raw.trim().split("\\s+")) {
                    if (s.length() > 0) {
                        cmd.add(s);
                    }
                }
                appendLog("RAW: " + cmd);
                Process p = null;
                try {
                    p = newBuilder(cmd).start();
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        appendLog("RAW| " + line);
                    }
                    appendLog("RAW exit=" + p.waitFor());
                } catch (Exception e) {
                    appendLog("RAW failed: " + e);
                } finally {
                    if (p != null && p.isAlive()) {
                        p.destroyForcibly();
                    }
                }
            }
        });
    }

    private void stopServerAsync() {
        stopping = true;
        metricsPolling = false;
        tokPerSec = 0;
        ui.post(new Runnable() {
            @Override
            public void run() {
                setStatus("停止中…");
            }
        });
        io.execute(new Runnable() {
            @Override
            public void run() {
                Process p = proc;
                proc = null;
                if (p != null) {
                    p.destroy();
                    try {
                        if (!p.waitFor(4, TimeUnit.SECONDS)) {
                            p.destroyForcibly();
                            p.waitFor(2, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("已停止");
                        setRunningUi(false);
                    }
                });
            }
        });
    }

    private void appendLog(String line) {
        Log.i(TAG, line);
        synchronized (logBuf) {
            logBuf.append(line).append('\n');
            if (logBuf.length() > 200000) {
                logBuf.delete(0, logBuf.length() - 180000);
            }
        }
        if (!logFlushScheduled) {
            logFlushScheduled = true;
            ui.postDelayed(logFlush, 250);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static void closeQuietly(Object o) {
        try {
            if (o instanceof InputStream) {
                ((InputStream) o).close();
            } else if (o instanceof OutputStream) {
                ((OutputStream) o).close();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (historyPage != null && historyPage.getVisibility() == View.VISIBLE) {
            refreshHistory();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String js = intent.getStringExtra("js_now_b64");
        if (js != null) {
            try {
                web.evaluateJavascript(new String(
                        android.util.Base64.decode(js, android.util.Base64.NO_WRAP), "UTF-8"), null);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopping = true;
        metricsPolling = false;
        Process p = proc;
        proc = null;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
            }
        }
        if (web != null) {
            web.destroy();
        }
        io.shutdownNow();
        dash.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final String COLLECT_JS =
            "(function(){if(!window.LlamaHub)return;" +
            "function req(r){return new Promise(function(res,rej){r.onsuccess=function(){res(r.result)};r.onerror=function(){rej(r.error)}})}" +
            "(async function(){var out=0;try{var dbs=await indexedDB.databases();var convs=[],msgs=[];" +
            "for(var di=0;di<dbs.length;di++){var db=await new Promise(function(res,rej){var q=indexedDB.open(dbs[di].name);q.onsuccess=function(){res(q.result)};q.onerror=function(){rej(q.error)}});" +
            "var names=Array.from(db.objectStoreNames);" +
            "for(var si=0;si<names.length;si++){try{var tx=db.transaction(names[si],'readonly');var all=await req(tx.objectStore(names[si]).getAll());" +
            "for(var ri=0;ri<all.length;ri++){var rec=all[ri];if(!rec||typeof rec!=='object')continue;" +
            "if(rec.convId&&rec.role&&rec.content!==undefined){msgs.push(rec)}" +
            "else if(rec.id&&(rec.name||rec.title)){convs.push(rec)}}}catch(e){}}" +
            "db.close();}" +
            "var by={};for(var i=0;i<msgs.length;i++){var k=msgs[i].convId;(by[k]=by[k]||[]).push(msgs[i])}" +
            "for(var ci=0;ci<convs.length;ci++){var c=convs[ci];var arr=by[c.id];if(!arr||!arr.length)continue;" +
            "arr.sort(function(a,b){return (a.timestamp||0)-(b.timestamp||0)});var om=[];" +
            "for(var j=0;j<arr.length;j++){var mm=arr[j];var ct=mm.content;if(typeof ct!=='string'){try{ct=JSON.stringify(ct)}catch(e){ct=String(ct)}}" +
            "if(ct&&ct.length){om.push({role:mm.role,content:ct})}}" +
            "if(!om.length)continue;var title=c.name||c.title||'';" +
            "if(!title){for(var q=0;q<om.length;q++){if(om[q].role==='user'){title=om[q].content.slice(0,42);break}}}" +
            "LlamaHub.onChatRecord(JSON.stringify({id:c.id,title:title||'Chat',updated:c.lastModified||arr[arr.length-1].timestamp||Date.now(),messages:om}));out++;}" +
            "}catch(e){LlamaHub.onProbe('err '+e)}LlamaHub.onChatsDone(out);})();})();";

    private static final String OPEN_JS =
            "(function(title){" +
            "function byText(){var els=document.querySelectorAll('a,button,li,div,span,p');" +
            "for(var i=0;i<els.length;i++){var e=els[i];if(e.children.length===0&&e.textContent&&e.textContent.trim()===title)return e}return null}" +
            "function toggle(){var bs=document.querySelectorAll('button,[role=button],a');" +
            "for(var i=0;i<bs.length;i++){var b=bs[i];var s=((b.getAttribute('aria-label')||'')+' '+(b.title||'')+' '+(b.className||'')).toLowerCase();" +
            "if(/sidebar|menu|history|chats/.test(s)){b.click();return true}}return false}" +
            "var t=byText();if(t){t.click();LlamaHub.onOpenChat('ok');return}" +
            "toggle();setTimeout(function(){var t2=byText();if(t2){t2.click();LlamaHub.onOpenChat('ok')}else{LlamaHub.onOpenChat('fail')}},700);" +
            "})(__TITLE__);";

    private static final String STATS_HIDE_JS =
            "(function(){if(window.__lhStatsHide)return;function hide(){" +
            "var es=document.querySelectorAll('[class*=info]');" +
            "for(var i=0;i<es.length;i++){var e=es[i];var t=(e.textContent||'');" +
            "if(t.length<80&&t.indexOf('tokens/s')>=0){e.style.display='none'}}}" +
            "window.__lhStatsHide=setInterval(hide,1000);hide()})();";

    private static final String COMPOSER_FIX_JS =
            "(function(){if(window.__lhComposerFix)return;window.__lhComposerFix=setInterval(function(){" +
            "var ta=document.querySelector('textarea');if(!ta)return;" +
            "var h=ta.getBoundingClientRect().height;" +
            "if((ta.value||'').length===0&&h>96){ta.style.height='48px'}},800)})();";

    private static final String THOUGHT_ON =
            "(function(){window.__lhThought=true;if(window.__lhThoughtObs)return;window.__lhThoughtObs=true;" +
            "setInterval(function(){if(!window.__lhThought)return;var ds=document.querySelectorAll('details');" +
            "for(var i=0;i<ds.length;i++){var d=ds[i];var s=(d.querySelector('summary')?d.querySelector('summary').textContent:'').toLowerCase();" +
            "if(/reason|think|思考/.test(s)&&!d.open){d.open=true}}},700)})();";

    private static final String THOUGHT_OFF = "(function(){window.__lhThought=false})();";

    private static final String DELETE_JS =
            "(function(id){(async function(){try{var dbs=await indexedDB.databases();" +
            "for(var di=0;di<dbs.length;di++){var db=await new Promise(function(res,rej){var q=indexedDB.open(dbs[di].name);q.onsuccess=function(){res(q.result)};q.onerror=function(){rej(q.error)}});" +
            "var names=Array.from(db.objectStoreNames);" +
            "for(var si=0;si<names.length;si++){var st=db.transaction(names[si],'readwrite').objectStore(names[si]);" +
            "await new Promise(function(res){var req=st.openCursor();req.onsuccess=function(){var c=req.result;if(!c){res();return}" +
            "var v=c.value||{};if(v.id===id||v.convId===id){c.delete()}c.continue()};req.onerror=function(){res()}});}" +
            "db.close();}LlamaHub.onDeleted('ok')}catch(e){LlamaHub.onDeleted('fail')}})()})(__ID__);";

    private static final String IMPORT_JS =
            "(function(payload){(async function(){try{var o=JSON.parse(payload);" +
            "var list=Array.isArray(o)?o:[o];var dbs=await indexedDB.databases();var db=null;" +
            "for(var di=0;di<dbs.length;di++){var d=await new Promise(function(res,rej){var q=indexedDB.open(dbs[di].name);q.onsuccess=function(){res(q.result)};q.onerror=function(){rej(q.error)}});" +
            "if(d.objectStoreNames.contains('conversations')&&d.objectStoreNames.contains('messages')){db=d;break}d.close()}" +
            "if(!db){LlamaHub.onImported('fail:no store');return}var ok=0;" +
            "for(var ci=0;ci<list.length;ci++){var c=list[ci]||{};var msgs=c.messages||[];if(!msgs.length)continue;" +
            "var convId=(crypto.randomUUID?crypto.randomUUID():('c'+Date.now()+Math.random().toString(16).slice(2)));var now=Date.now();" +
            "var tx=db.transaction(['conversations','messages'],'readwrite');var cs=tx.objectStore('conversations');var ms=tx.objectStore('messages');" +
            "var recs=[];var prev=null;" +
            "for(var i=0;i<msgs.length;i++){var mid=(crypto.randomUUID?crypto.randomUUID():('m'+now+i+Math.random().toString(16).slice(2)));" +
            "var rec={id:mid,convId:convId,role:msgs[i].role||'user',content:String(msgs[i].content==null?'':msgs[i].content),timestamp:now+i,children:[],parent:prev,type:'text',toolCalls:'',extra:[]};" +
            "if(prev){prev.children.push(mid)}recs.push(rec);prev=rec}" +
            "for(var r=0;r<recs.length;r++){ms.put(recs[r])}" +
            "cs.put({id:convId,name:c.title||('Imported '+new Date().toLocaleString()),lastModified:now,currNode:prev.id,reasoningEffort:'default'});" +
            "await new Promise(function(res,rej){tx.oncomplete=res;tx.onerror=rej;tx.onabort=rej});ok++}" +
            "db.close();LlamaHub.onImported('ok:'+ok)}catch(e){LlamaHub.onImported('fail:'+e)}})()})(__DATA__);";

    private static final String NEW_CHAT_JS =
            "(function(){var es=document.querySelectorAll('button,a,[role=button]');" +
            "for(var i=0;i<es.length;i++){var e=es[i];var al=(e.getAttribute('aria-label')||'').toLowerCase();" +
            "var tx=(e.textContent||'').toLowerCase().replace(/\\s+/g,' ').trim();" +
            "if(al==='new chat'||al.indexOf('new chat')===0||tx.indexOf('new chat')===0){e.click();LlamaHub.onNewChat('ok');return}}" +
            "LlamaHub.onNewChat('fail');})();";

    private static final String PLACEHOLDER_HTML =
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>body{font-family:-apple-system,sans-serif;background:#0f172a;color:#e2e8f0;padding:24px;line-height:1.7}" +
            "h3{margin:0 0 12px}code{background:#1e293b;padding:2px 6px;border-radius:4px;word-break:break-all}" +
            ".muted{color:#94a3b8;font-size:13px}</style></head><body>" +
            "<h3>Hexagon Chat</h3>" +
            "<p>1. 在「设置」页选模型与参数，点「启动」。</p>" +
            "<p>2. 状态变为「运行中」后点「对话」。</p>" +
            "<p class='muted'>首次启动需加载 2 GB 左右权重，可能需要 30–120 秒。</p>" +
            "</body></html>";
}
