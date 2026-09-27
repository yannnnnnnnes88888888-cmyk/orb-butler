package cn.workbuddy.orb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** 用 Canvas 画的光球（不依赖任何图片资源） */
public class OrbView extends View {

    private static final long FRAME = 33L; // ~30fps

    private final Paint pCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int a = Color.parseColor("#0064E0"); // 外圈色
    private int b = Color.parseColor("#7B61FF"); // 内圈色
    private float pulse = 1f;   // 呼吸/激动幅度
    private float alpha = 1f;   // 收纳时半透明
    private long lastFrame = 0;

    public OrbView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** idle / happy / alert / warn / sleep / focus */
    public void setMood(String mood) {
        if (mood == null) mood = "idle";
        switch (mood) {
            case "happy":
                a = Color.parseColor("#F5A623");
                b = Color.parseColor("#FF6B9D");
                pulse = 1.25f;
                break;
            case "alert":
                a = Color.parseColor("#FA383E");
                b = Color.parseColor("#FF8A3D");
                pulse = 1.9f;
                break;
            case "warn":
            case "worry":
                a = Color.parseColor("#F5A623");
                b = Color.parseColor("#FF8A3D");
                pulse = 1.5f;
                break;
            case "focus":
                a = Color.parseColor("#00A3FF");
                b = Color.parseColor("#0064E0");
                pulse = 1.1f;
                break;
            case "sleep":
                a = Color.parseColor("#3C4A63");
                b = Color.parseColor("#232C3D");
                pulse = 0.6f;
                break;
            default:
                a = Color.parseColor("#0064E0");
                b = Color.parseColor("#7B61FF");
                pulse = 1f;
        }
        postInvalidate();
    }

    public void setPulse(float p) {
        pulse = p;
        postInvalidate();
    }

    public void setDim(float v) {
        alpha = v;
        setAlpha(v);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        long now = SystemClock.uptimeMillis();
        lastFrame = now;

        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float base = Math.min(w, h) / 2f;

        float t = (now % 5200L) / 5200f;
        float breathe = 1f + 0.045f * (float) Math.sin(t * Math.PI * 2) * pulse;
        float r = base * 0.62f * breathe;

        // 1) 光晕（外发光）
        float haloR = base * (0.98f + 0.12f * (float) Math.sin(t * Math.PI * 2) * pulse);
        pHalo.setShader(new RadialGradient(cx, cy, haloR,
                argb(b, (int) (110 * alpha)), argb(b, 0), Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, haloR, pHalo);

        // 2) 球体：白高光 → 内色 → 外色
        pCore.setShader(new RadialGradient(
                cx - r * 0.28f, cy - r * 0.32f, r * 1.25f,
                argb(Color.WHITE, (int) (235 * alpha)),
                argb(a, (int) (255 * alpha)),
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, pCore);

        // 第二层内色叠一点，做出渐变过渡
        pCore.setShader(new RadialGradient(cx, cy, r,
                argb(b, (int) (170 * alpha)), argb(b, 0), Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, pCore);

        // 3) 旋转光环
        float ringR = base * 0.80f;
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(Math.max(2f, base * 0.045f));
        pRing.setStrokeCap(Paint.Cap.ROUND);
        pRing.setShader(new LinearGradient(cx - ringR, cy - ringR, cx + ringR, cy + ringR,
                argb(Color.WHITE, (int) (200 * alpha)), argb(b, (int) (60 * alpha)),
                Shader.TileMode.CLAMP));
        float sweep = 110f + 40f * (float) Math.sin(t * Math.PI * 2);
        cv.drawArc(oval, now / 12f % 360f, sweep, false, pRing);
        cv.drawArc(oval, now / 9f % 360f + 180f, sweep * 0.55f, false, pRing);

        // 4) 环绕的小光点
        pSpark.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            double ang = (now / (900.0 + i * 260.0)) * Math.PI * 2 + i * 2.1;
            float rr = base * (0.86f + 0.05f * i);
            float sx = cx + (float) (Math.cos(ang) * rr);
            float sy = cy + (float) (Math.sin(ang) * rr);
            float sr = base * (0.055f + 0.012f * (float) Math.sin(now / 300.0 + i));
            pSpark.setColor(argb(Color.WHITE, (int) ((150 + 60 * Math.sin(now / 260.0 + i)) * alpha)));
            cv.drawCircle(sx, sy, sr, pSpark);
        }

        // 动画续帧
        postInvalidateDelayed(FRAME);
    }

    private static int argb(int color, int alpha255) {
        int al = Math.max(0, Math.min(255, alpha255));
        return (al << 24) | (color & 0x00FFFFFF);
    }
}
