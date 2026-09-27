package cn.workbuddy.orb;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 悬浮窗桌宠：直接用原图（res/drawable/pet.png），不再手绘。
 * · 呼吸缩放 / 开心蹦跳 / 提醒抖动 / 睡觉变淡
 * · 对外接口不变，FloatingService 无需改动
 */
public class PetView extends View {

    private Bitmap bmp;
    private String mood = "idle";
    private float pulse = 1f;
    private float dim = 1f;

    private final long t0 = System.currentTimeMillis();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public PetView(Context c) {
        super(c);
        try {
            bmp = BitmapFactory.decodeResource(getResources(), R.drawable.pet);
        } catch (Throwable t) {
            bmp = null;
        }
    }

    public void setMood(String m) {
        if (m == null) m = "idle";
        if ("alarm".equals(m)) m = "alert";
        if ("warn".equals(m)) m = "worry";
        if (!"happy".equals(m) && !"alert".equals(m) && !"worry".equals(m)
                && !"sleep".equals(m) && !"focus".equals(m)) m = "idle";
        mood = m;
        invalidate();
    }

    public void setPulse(float p) {
        pulse = p == 0 ? 1f : p;
    }

    public void setDim(float d) {
        dim = d == 0 ? 1f : d;
        setAlpha(dim);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;

        if (bmp == null) { // 兜底：资源没打到包里时画个白团子，不至于空白
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setAlpha((int) (200 * dim));
            canvas.drawCircle(s / 2f, s / 2f, s * 0.36f, p);
            return;
        }

        long t = System.currentTimeMillis() - t0;

        // 呼吸
        float br = 1f + 0.035f * (float) Math.sin(t / 850.0);
        // 开心 → 上下蹦
        float hop = "happy".equals(mood)
                ? Math.abs((float) Math.sin(t / 260.0)) * s * 0.07f : 0f;
        // 提醒 → 左右抖
        float jx = "alert".equals(mood)
                ? (float) Math.sin(t / 60.0) * s * 0.02f : 0f;

        canvas.save();
        canvas.translate(jx, -hop);
        canvas.scale(br * pulse, br * pulse, s / 2f, s / 2f);

        RectF dst = new RectF(0f, 0f, s, s);
        canvas.drawBitmap(bmp, null, dst, paint);
        canvas.restore();

        postInvalidateDelayed(33);
    }
}
