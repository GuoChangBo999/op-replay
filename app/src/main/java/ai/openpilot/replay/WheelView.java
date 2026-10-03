package ai.openpilot.replay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 方向盘自定义 View —— 纯 Canvas 绘制，不依赖 WebView/SVG。
 * angleDeg 为 openpilot 的 steeringAngleDeg（左正右负），内部取负以匹配物理方向。
 */
public class WheelView extends View {

    private float angleDeg = 0f;   // openpilot 约定：左正右负
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WheelView(Context c) { super(c); init(); }
    public WheelView(Context c, AttributeSet a) { super(c, a); init(); }
    public WheelView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(14f);
        rim.setColor(0xFF8E8E93);

        spoke.setColor(0xFF8E8E93);
        spoke.setStrokeWidth(14f);
        spoke.setStrokeCap(Paint.Cap.ROUND);

        hub.setStyle(Paint.Style.STROKE);
        hub.setStrokeWidth(10f);
        hub.setColor(0xFF8E8E93);

        dot.setColor(0xFF3B9EFF);
    }

    /** 传入 openpilot 的 steeringAngleDeg（左正右负）。 */
    public void setAngle(float deg) {
        this.angleDeg = deg;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - 16f;

        canvas.save();
        canvas.rotate(-angleDeg, cx, cy);   // 左正 -> 逆时针

        // 外圈
        canvas.drawCircle(cx, cy, r, rim);
        // 轮毂
        canvas.drawCircle(cx, cy, r * 0.32f, hub);
        // 三根辐条：左、右、下
        float sp = r * 0.32f;
        canvas.drawLine(cx - r + 12f, cy, cx - sp, cy, spoke);
        canvas.drawLine(cx + sp, cy, cx + r - 12f, cy, spoke);
        canvas.drawLine(cx, cy + sp, cx, cy + r - 12f, spoke);
        // 中心标记
        canvas.drawCircle(cx, cy, r * 0.09f, dot);
        // 顶部指示点
        dot.setColor(0xFFFFD60A);
        canvas.drawCircle(cx, cy - r, r * 0.10f, dot);
        dot.setColor(0xFF3B9EFF);

        canvas.restore();
    }
}