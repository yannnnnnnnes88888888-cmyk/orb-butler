package cn.workbuddy.orb;

import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 悬浮在其他软件上方的光球。
 * · 拖动换位置（位置会记住）
 * · 轻点 = 打开主界面；长按 / 8 秒不动 = 自动收纳到屏幕边缘变成半透明小光点
 * · 收到提醒时会变色 + 加速呼吸
 */
public class FloatingService extends Service {

    public static final String ACTION_START = "cn.workbuddy.orb.action.START";
    public static final String ACTION_STOP = "cn.workbuddy.orb.action.STOP";
    public static final String ACTION_COLLAPSE = "cn.workbuddy.orb.action.COLLAPSE";
    public static final String ACTION_EXPAND = "cn.workbuddy.orb.action.EXPAND";
    public static final String ACTION_PING = "cn.workbuddy.orb.action.PING";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_TEXT = "text";

    private static final int NOTI_ID = 1031;
    private static final long IDLE_MS = 8000L;
    private static final long LONG_PRESS_MS = 550L;

    private static FloatingService INSTANCE;

    private WindowManager wm;
    private FrameLayout root;
    private PetView pet;
    private TextView bubble;
    private Runnable bubbleHide;
    private WindowManager.LayoutParams lp;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable idleTask;
    private Runnable longPressTask;
    private ValueAnimator anim;

    private boolean collapsed = false;
    private float curDim = 1f;
    private int sizeExp, sizeCol;
    private int expandedX = Integer.MIN_VALUE, expandedY = Integer.MIN_VALUE;
    private int screenW = 1080, screenH = 1920;

    private float downX, downY;
    private int startX, startY;
    private long downAt;
    private boolean moved;

    public static boolean isRunning() {
        return INSTANCE != null;
    }

    /** 提醒来了：让桌宠变色 + 激动一下 */
    public static void ping(Context c, String kind) {
        ping(c, kind, null);
    }

    /** 提醒来了 + 桌宠冒气泡说话 */
    public static void ping(Context c, String kind, String text) {
        Intent i = new Intent(c, FloatingService.class);
        i.setAction(ACTION_PING);
        i.putExtra(EXTRA_KIND, kind == null ? "alert" : kind);
        if (text != null) i.putExtra(EXTRA_TEXT, text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            try {
                c.startService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        Notifier.ensureChannels(this);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureScreen();

        sizeExp = dp(96);
        sizeCol = dp(46);

        pet = new PetView(this);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        bubble = new TextView(this);
        bubble.setBackgroundResource(R.drawable.bg_bubble);
        bubble.setTextColor(0xFF1C1E21);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        bubble.setPadding(dp(10), dp(7), dp(10), dp(7));
        bubble.setMaxLines(4);
        bubble.setLineSpacing(dp(1), 1f);
        bubble.setVisibility(View.GONE);
        bubble.setAlpha(0f);
        root.addView(pet, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                dp(172), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        bubble.setTranslationY(-dp(62));
        root.addView(bubble, bp);

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp = new WindowManager.LayoutParams(sizeExp, sizeExp, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;

        int sx = Prefs.getX(this), sy = Prefs.getY(this);
        if (sx == Integer.MIN_VALUE || sy == Integer.MIN_VALUE) {
            lp.x = screenW - sizeExp - dp(10);
            lp.y = (int) (screenH * 0.58f);
        } else {
            lp.x = sx;
            lp.y = sy;
        }
        clampIn();

        android.app.Notification notif = Notifier.buildServiceNotification(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, notif);
        }

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            stopSelf();
            return;
        }

        attachTouch();

        pet.setMood("idle");
        scheduleIdle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次 startForegroundService 都呼应一次 startForeground，避免系统 5 秒判定
        try {
            android.app.Notification n = Notifier.buildServiceNotification(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable ignored) {
        }

        String a = intent == null ? ACTION_START : intent.getAction();
        if (a == null) a = ACTION_START;
        switch (a) {
            case ACTION_STOP:
                stopSelf();
                break;
            case ACTION_COLLAPSE:
                collapse(true);
                break;
            case ACTION_EXPAND:
                expand(true);
                break;
            case ACTION_PING:
                String pk = intent == null ? "alert" : intent.getStringExtra(EXTRA_KIND);
                String pt = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
                onPing(pk, pt);
                break;
            default:
                if (collapsed) expand(false);
                scheduleIdle();
                break;
        }
        return START_STICKY;
    }

    private void onPing(String kind, String text) {
        pet.setMood(kind);
        if (collapsed) expand(false);
        speak(text == null || text.length() == 0 ? defaultLine(kind) : text);
        scheduleIdle();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                pet.setMood(collapsed ? "sleep" : "idle");
            }
        }, 9000);
    }

    private String defaultLine(String kind) {
        if ("alarm".equals(kind)) return "到点啦！有个任务在等你～";
        if ("alert".equals(kind)) return "截止时间快到了，抓紧呀！";
        if ("warn".equals(kind)) return "别忘了手头的事哦";
        if ("daily".equals(kind)) return "早上好呀～今天打算做什么？";
        return "我在呢～";
    }

    /** 桌宠头顶冒个气泡说句话 */
    private void speak(String text) {
        if (text == null || text.length() == 0 || bubble == null) return;
        if (bubbleHide != null) main.removeCallbacks(bubbleHide);
        bubble.setText(text.length() > 90 ? text.substring(0, 89) + "…" : text);
        bubble.setVisibility(View.VISIBLE);
        bubble.animate().alpha(1f).setDuration(180).start();
        bubbleHide = new Runnable() {
            @Override
            public void run() {
                bubble.animate().alpha(0f).setDuration(240).start();
            }
        };
        main.postDelayed(bubbleHide, 5200);
    }

    /* ---------------- 触摸：拖动 / 轻点 / 长按 ---------------- */

    private void attachTouch() {
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        downAt = System.currentTimeMillis();
                        moved = false;
                        cancelIdle();
                        if (anim != null) anim.cancel();
                        longPressTask = new Runnable() {
                            @Override
                            public void run() {
                                if (!moved) toggleCollapse();
                            }
                        };
                        main.postDelayed(longPressTask, LONG_PRESS_MS);
                        pet.setPulse(1.35f);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                            moved = true;
                            cancelLongPress();
                        }
                        if (moved) {
                            lp.x = (int) (startX + dx);
                            lp.y = (int) (startY + dy);
                            clampIn();
                            safeUpdate();
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        cancelLongPress();
                        pet.setPulse(1f);
                        long dur = System.currentTimeMillis() - downAt;
                        if (!moved && dur < 420) {
                            onTap();
                        } else if (moved) {
                            if (!collapsed) {
                                expandedX = lp.x;
                                expandedY = lp.y;
                                Prefs.savePos(FloatingService.this, lp.x, lp.y);
                            }
                        }
                        scheduleIdle();
                        return true;
                }
                return true;
            }
        });
    }

    private void onTap() {
        if (collapsed) {
            expand(true);
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("from_orb", true);
        i.putExtra("open_tab", "chat"); // 点桌宠 = 找它聊天
        try {
            startActivity(i);
        } catch (Throwable ignored) {
        }
        collapse(true); // 打开 App 时自动让开位置
    }

    private void toggleCollapse() {
        if (collapsed) expand(true);
        else collapse(true);
    }

    /* ---------------- 收纳 / 展开 ---------------- */

    private void collapse(boolean animate) {
        if (collapsed || root == null) return;
        hideBubble();
        measureScreen();
        expandedX = lp.x;
        expandedY = lp.y;
        Prefs.savePos(this, expandedX, expandedY);
        collapsed = true;

        boolean toLeft = (lp.x + sizeExp / 2f) < screenW / 2f;
        int tx = toLeft ? (int) (-sizeCol * 0.34f) : (int) (screenW - sizeCol * 0.66f);
        int ty = Math.max(0, Math.min(lp.y + sizeExp / 2 - sizeCol / 2, screenH - sizeCol));

        pet.setMood("sleep");
        if (animate) animateTo(tx, ty, sizeCol, 0.45f);
        else {
            lp.x = tx;
            lp.y = ty;
            curDim = 0.45f;
            setSize(sizeCol);
            pet.setDim(0.45f);
            safeUpdate();
        }
        cancelIdle();
    }

    private void expand(boolean animate) {
        if (!collapsed || root == null) return;
        measureScreen();
        collapsed = false;
        if (expandedX == Integer.MIN_VALUE) {
            expandedX = screenW - sizeExp - dp(10);
            expandedY = (int) (screenH * 0.58f);
        }
        pet.setMood("idle");
        if (animate) animateTo(expandedX, expandedY, sizeExp, 1f);
        else {
            lp.x = expandedX;
            lp.y = expandedY;
            curDim = 1f;
            setSize(sizeExp);
            pet.setDim(1f);
            safeUpdate();
        }
        scheduleIdle();
    }

    private void animateTo(final int tx, final int ty, int size, float dim) {
        final int sx = lp.x, sy = lp.y, sw = lp.width;
        final float d0 = curDim;
        final float d1 = dim;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(280);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                lp.x = (int) (sx + (tx - sx) * f);
                lp.y = (int) (sy + (ty - sy) * f);
                int w = (int) (sw + (size - sw) * f);
                lp.width = w;
                lp.height = w;
                pet.setDim(d0 + (d1 - d0) * f);
                safeUpdate();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                curDim = d1;
                pet.setDim(d1);
            }
        });
        anim.start();
    }

    /* ---------------- 杂项 ---------------- */

    private void hideBubble() {
        if (bubbleHide != null) {
            main.removeCallbacks(bubbleHide);
            bubbleHide = null;
        }
        if (bubble != null) {
            bubble.animate().cancel();
            bubble.setAlpha(0f);
            bubble.setVisibility(View.GONE);
        }
    }

    private void scheduleIdle() {
        cancelIdle();
        if (collapsed) return;
        idleTask = new Runnable() {
            @Override
            public void run() {
                collapse(true);
            }
        };
        main.postDelayed(idleTask, IDLE_MS);
    }

    private void cancelIdle() {
        if (idleTask != null) {
            main.removeCallbacks(idleTask);
            idleTask = null;
        }
    }

    private void cancelLongPress() {
        if (longPressTask != null) {
            main.removeCallbacks(longPressTask);
            longPressTask = null;
        }
    }

    private void setSize(int px) {
        lp.width = px;
        lp.height = px;
        safeUpdate();
    }

    private void safeUpdate() {
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    private void clampIn() {
        int maxX = Math.max(0, screenW - lp.width);
        int maxY = Math.max(0, screenH - lp.height - dp(8));
        lp.x = Math.max(-lp.width / 3, Math.min(lp.x, maxX));
        lp.y = Math.max(0, Math.min(lp.y, maxY));
    }

    @SuppressWarnings("deprecation")
    private void measureScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            wm.getDefaultDisplay().getMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Throwable t) {
            screenW = getResources().getDisplayMetrics().widthPixels;
            screenH = getResources().getDisplayMetrics().heightPixels;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        measureScreen();
        if (!collapsed) {
            clampIn();
            safeUpdate();
        } else {
            collapse(false);
        }
    }

    @Override
    public void onDestroy() {
        cancelIdle();
        cancelLongPress();
        if (anim != null) anim.cancel();
        try {
            if (root != null) wm.removeView(root);
        } catch (Throwable ignored) {
        }
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
