package cn.workbuddy.orb;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;
    private static final int REQ_FILE = 1002;
    private static final int REQ_OVERLAY = 1003;

    private WebView web;
    private View cardBody;
    private TextView tvSummary;
    private Button btnOverlay, btnNotify, btnExact, btnBattery, btnOrb, btnToggle;
    private CheckBox chkBoot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifier.ensureChannels(this);
        setContentView(R.layout.activity_main);

        cardBody = findViewById(R.id.cardBody);
        tvSummary = findViewById(R.id.tvSummary);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnNotify = findViewById(R.id.btnNotify);
        btnExact = findViewById(R.id.btnExact);
        btnBattery = findViewById(R.id.btnBattery);
        btnOrb = findViewById(R.id.btnOrb);
        btnToggle = findViewById(R.id.btnToggle);
        chkBoot = findViewById(R.id.chkBoot);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = cardBody.getVisibility() != View.VISIBLE;
                cardBody.setVisibility(show ? View.VISIBLE : View.GONE);
                btnToggle.setText(show ? "设置 ▴" : "设置 ▾");
            }
        });

        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });
        btnNotify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotif();
            }
        });
        btnExact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.openExactAlarmSettings(MainActivity.this);
            }
        });
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBattery();
            }
        });
        btnOrb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOrb();
            }
        });
        chkBoot.setChecked(Prefs.autoStart(this));
        chkBoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setAutoStart(MainActivity.this, checked);
            }
        });

        setupWebView();
        refreshStatus();
        ReminderReceiver.rescheduleAll(this);

        // 首次打开：权限不齐就展开设置卡；都齐了就收起，把屏幕留给界面
        if (allReady()) collapseCard();
    }

    /* ---------------- WebView ---------------- */

    private void setupWebView() {
        web = findViewById(R.id.web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true); // 允许 file:// 页面访问 DeepSeek API
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(ws.getUserAgentString() + " OrbButler/1.0");

        web.addJavascriptInterface(new OrbBridge(), "OrbNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // 让「导出备份」按钮真的能存成文件（再挂一个原生导出）
                v.evaluateJavascript(
                        "(function(){try{var b=document.getElementById('btn-export');"
                                + "if(b){b.addEventListener('click',function(){setTimeout(function(){"
                                + "try{window.OrbNative.exportJson(localStorage.getItem('ptb.state.v1')||'{}');}catch(e){}},80);},true);}}catch(e){}})();",
                        null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                fileCb = cb;
                try {
                    Intent i = params.createIntent();
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择备份文件"), REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    fileCb = null;
                    return false;
                }
            }
        });

        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                if (url != null && !url.startsWith("blob:")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    /* ---------------- 权限 ---------------- */

    private boolean hasOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private boolean hasNotif() {
        return Notifier.hasPermission(this);
    }

    private boolean hasExact() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private boolean hasBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean allReady() {
        return hasOverlay() && hasNotif() && hasExact();
    }

    private void requestOverlay() {
        if (hasOverlay()) {
            toast("悬浮窗权限已开启");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY);
            } catch (Throwable t) {
                openAppDetails();
            }
        }
    }

    private void requestNotif() {
        if (hasNotif()) {
            toast("通知权限已开启");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Throwable t) {
                openAppDetails();
            }
        }
    }

    private void requestBattery() {
        if (hasBattery()) {
            toast("已允许后台运行");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(i);
            } catch (Throwable ignored) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void collapseCard() {
        cardBody.setVisibility(View.GONE);
        btnToggle.setText("设置 ▾");
    }

    private void refreshStatus() {
        boolean o = hasOverlay(), n = hasNotif(), e = hasExact(), b = hasBattery();
        mark(btnOverlay, o, "已开启", "去开启");
        mark(btnNotify, n, "已开启", "去开启");
        mark(btnExact, e, "已开启", "去开启");
        mark(btnBattery, b, "已允许", "去设置");

        int ok = (o ? 1 : 0) + (n ? 1 : 0) + (e ? 1 : 0) + (b ? 1 : 0);
        tvSummary.setText("权限状态 " + ok + "/4 —— 悬浮窗负责光球悬浮，通知负责到点提醒");

        boolean running = FloatingService.isRunning();
        btnOrb.setText(running ? "收起光球" : "启动光球");
    }

    private void mark(Button b, boolean ok, String yes, String no) {
        b.setText(ok ? yes : no);
        b.setSelected(ok);
    }

    private void toggleOrb() {
        if (FloatingService.isRunning()) {
            Intent i = new Intent(this, FloatingService.class);
            i.setAction(FloatingService.ACTION_STOP);
            try {
                startService(i);
            } catch (Throwable ignored) {
            }
            toast("光球已收起");
        } else {
            if (!hasOverlay()) {
                toast("先开「显示在其他软件上方」的权限");
                requestOverlay();
                return;
            }
            Intent i = new Intent(this, FloatingService.class);
            i.setAction(FloatingService.ACTION_START);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                else startService(i);
            } catch (Throwable t) {
                try {
                    startService(i);
                } catch (Throwable ignored) {
                }
            }
            toast("光球已启动，不用时会自动贴边收纳");
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 400);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handleIntentExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    private void handleIntentExtras(Intent it) {
        if (it == null) return;
        String tab = it.getStringExtra("open_tab");
        if (tab == null) return;
        final String t = tab;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript(
                            "(function(){var el=document.querySelector('.tab[data-tab=\"" + t + "\"]');"
                                    + "if(el){el.click();}})();", null);
                }
                it.removeExtra("open_tab");
            }
        }, 700);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_NOTIF) {
            refreshStatus();
            if (hasNotif()) toast("通知已开启，之后到点会弹到通知栏");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb != null) {
                Uri[] uris = null;
                if (data != null && data.getData() != null) uris = new Uri[]{data.getData()};
                else if (data != null && data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    uris = new Uri[n];
                    for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                }
                fileCb.onReceiveValue(uris);
                fileCb = null;
            }
            return;
        }
        refreshStatus();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup vg = (ViewGroup) web.getParent();
            if (vg != null) vg.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ---------------- JS 桥：让网页里的提醒变成真系统通知 ---------------- */

    private class OrbBridge {

        @JavascriptInterface
        public String getTasksJson() {
            return Prefs.getTasks(MainActivity.this);
        }

        @JavascriptInterface
        public void saveTasks(String json) {
            Prefs.saveTasks(MainActivity.this, json);
            ReminderReceiver.rescheduleAll(MainActivity.this);
        }

        @JavascriptInterface
        public void notify(String title, String text, String kind) {
            final String t = title, x = text, k = kind == null ? "normal" : kind;
            main.post(new Runnable() {
                @Override
                public void run() {
                    Notifier.notifyNow(MainActivity.this, t, x, k, "js_" + t);
                    FloatingService.ping(MainActivity.this, "alarm".equals(k) ? "alert" : "warn");
                }
            });
        }

        @JavascriptInterface
        public void schedule(String id, long when, String title, String text, String kind) {
            Notifier.schedule(MainActivity.this, id, when, title, text, kind);
        }

        @JavascriptInterface
        public void cancel(String id) {
            Notifier.cancel(MainActivity.this, id);
        }

        @JavascriptInterface
        public boolean hasNotifPermission() {
            return Notifier.hasPermission(MainActivity.this);
        }

        @JavascriptInterface
        public boolean hasOverlayPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void openPermissionSettings() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    requestNotif();
                    if (!hasOverlay()) requestOverlay();
                }
            });
        }

        @JavascriptInterface
        public void startOrb() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!FloatingService.isRunning()) toggleOrb();
                }
            });
        }

        @JavascriptInterface
        public void stopOrb() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (FloatingService.isRunning()) toggleOrb();
                }
            });
        }

        @JavascriptInterface
        public void collapseOrb() {
            Intent i = new Intent(MainActivity.this, FloatingService.class);
            i.setAction(FloatingService.ACTION_COLLAPSE);
            try {
                startService(i);
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void ping(String kind) {
            FloatingService.ping(MainActivity.this, kind);
        }

        @JavascriptInterface
        public void toast(final String s) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void exportJson(String json) {
            final String name = "orb-butler-backup-" + System.currentTimeMillis() + ".json";
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("no uri");
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    os.close();
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("no dir");
                    File f = new File(dir, name);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.close();
                    try {
                        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        if (dm != null) {
                            dm.addCompletedDownload(f.getName(), f.getName(), true,
                                    "application/json", f.getAbsolutePath(), f.length(), true);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("已备份到 下载/" + name);
                    }
                });
            } catch (Throwable t) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("备份失败，请检查存储权限");
                    }
                });
            }
        }
    }
}
