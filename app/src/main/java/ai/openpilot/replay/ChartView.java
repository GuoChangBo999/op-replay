package ai.openpilot.replay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 原生曲线图 —— 用 Canvas 画 vEgo / gas / brake / steer 四条曲线，
 * 并画一条竖线表示当前视频时刻。点一下曲线 -> 回调 onSeek(秒)。
 *
 * 取代之前 WebView + Chart.js 的方案。
 */
public class ChartView extends View {

    public interface OnSeekListener { void onSeek(double seconds); }
    private OnSeekListener listener;
    public void setOnSeekListener(OnSeekListener l) { this.listener = l; }

    // ---- 数据（与 op_parser 输出的 signals 对应） ----
    private double[] t, vEgo, gas, brake, steer;
    private double[] leftBlink, rightBlink, gasPressed, brakePressed;
    private volatile int curIndex = 0;
    private double gasMax = 1, brakeMax = 1;

    // 视图变换用：时间范围（整段）
    private double t0 = 0, t1 = 1;

    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGas = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBrk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path pathSpd = new Path();
    private final Path pathGas = new Path();
    private final Path pathBrk = new Path();
    private final Path pathStr = new Path();

    public ChartView(Context c) { super(c); init(); }
    public ChartView(Context c, AttributeSet a) { super(c, a); init(); }
    public ChartView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setBackgroundColor(0xFF111111);
        pGrid.setColor(0xFF222222);
        pGrid.setStrokeWidth(1f);
        pSpd.setColor(0xFF3B9EFF); pSpd.setStrokeWidth(3f); pSpd.setStyle(Paint.Style.STROKE);
        pGas.setColor(0xFF00E676); pGas.setStrokeWidth(2f); pGas.setStyle(Paint.Style.STROKE);
        pBrk.setColor(0xFFFF5252); pBrk.setStrokeWidth(2f); pBrk.setStyle(Paint.Style.STROKE);
        pStr.setColor(0xFFFFD60A); pStr.setStrokeWidth(2f); pStr.setStyle(Paint.Style.STROKE);
        pCur.setColor(0xFFFFFFFF); pCur.setStrokeWidth(2f);
        pText.setColor(0xFF888888); pText.setTextSize(24f);
    }

    /** 设置整段信号数据。数组长度应一致；允许某些字段为 null。 */
    public void setData(double[] t, double[] vEgo, double[] gas, double[] brake, double[] steer,
                        double[] leftBlink, double[] rightBlink,
                        double[] gasPressed, double[] brakePressed) {
        this.t = t; this.vEgo = vEgo; this.gas = gas; this.brake = brake; this.steer = steer;
        this.leftBlink = leftBlink; this.rightBlink = rightBlink;
        this.gasPressed = gasPressed; this.brakePressed = brakePressed;
        gasMax = 0; brakeMax = 0;
        if (gas != null) for (double v : gas) if (!Double.isNaN(v)) gasMax = Math.max(gasMax, v);
        if (brake != null) for (double v : brake) if (!Double.isNaN(v)) brakeMax = Math.max(brakeMax, v);
        if (t != null && t.length > 0) { t0 = t[0]; t1 = t[t.length - 1]; }
        if (t1 <= t0) t1 = t0 + 1;
        buildPaths();
        invalidate();
    }

    private void buildPaths() {
        pathSpd.reset(); pathGas.reset(); pathBrk.reset(); pathStr.reset();
        if (t == null || t.length == 0) return;
        int n = t.length;
        // 车速最大用于左轴归一（0..max），方向用右轴归一（-540..540）
        double vmax = 1;
        if (vEgo != null) for (double v : vEgo) if (!Double.isNaN(v)) vmax = Math.max(vmax, v * 3.6);
        boolean first = true;
        for (int i = 0; i < n; i++) {
            float x = (float) i;
            float ySpd = norm((vEgo != null ? vEgo[i] * 3.6 : 0), 0, vmax);
            float yGas = norm(gas != null ? gas[i] : 0, 0, Math.max(gasMax, 1));
            float yBrk = norm(brake != null ? brake[i] : 0, 0, Math.max(brakeMax, 0.01));
            float yStr = norm(steer != null ? steer[i] : 0, -540, 540);
            if (first) {
                pathSpd.moveTo(x, ySpd); pathGas.moveTo(x, yGas);
                pathBrk.moveTo(x, yBrk); pathStr.moveTo(x, yStr);
                first = false;
            } else {
                pathSpd.lineTo(x, ySpd); pathGas.lineTo(x, yGas);
                pathBrk.lineTo(x, yBrk); pathStr.lineTo(x, yStr);
            }
        }
    }

    // 归一化到 0..1（y 轴向下，1=顶部）
    private float norm(double v, double lo, double hi) {
        if (hi <= lo) return 0.5f;
        float f = (float) ((v - lo) / (hi - lo));
        if (f < 0) f = 0; if (f > 1) f = 1;
        return 1f - f;
    }

    public void setCurrentIndex(int idx) {
        if (idx < 0) idx = 0;
        curIndex = idx;
        invalidate();
    }

    private int idxToPx(int i, float w) {
        if (t == null || t.length <= 1) return 0;
        return (int) (w * i / (float) (t.length - 1));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int padT = 8, padB = 8;
        int plotH = h - padT - padB;

        // 网格
        for (int g = 0; g <= 4; g++) {
            float y = padT + plotH * g / 4f;
            canvas.drawLine(0, y, w, y, pGrid);
        }

        if (t == null || t.length == 0) return;

        // 曲线：把归一化坐标映射到像素。构造时用的是 i（0..n-1）作为 x，这里缩放。
        canvas.save();
        float sx = w / (float) (t.length - 1);
        float sy = plotH;
        canvas.translate(0, padT);
        canvas.scale(sx / 1f, sy / 1f);
        // 由于 path 里 x 用的是像素索引、y 用的是 0..1，直接缩放即可
        canvas.drawPath(pathSpd, scalePaint(pSpd));
        canvas.drawPath(pathGas, scalePaint(pGas));
        canvas.drawPath(pathBrk, scalePaint(pBrk));
        canvas.drawPath(pathStr, scalePaint(pStr));
        canvas.restore();

        // 当前时刻竖线
        int cx = idxToPx(curIndex, w);
        canvas.drawLine(cx, padT, cx, padT + plotH, pCur);

        // 图例
        canvas.drawText("车速", 6, h - 6, tinted(pText, 0xFF3B9EFF));
        canvas.drawText("油门", 70, h - 6, tinted(pText, 0xFF00E676));
        canvas.drawText("刹车", 134, h - 6, tinted(pText, 0xFFFF5252));
        canvas.drawText("方向", 198, h - 6, tinted(pText, 0xFFFFD60A));
    }

    // 让线条在缩放后仍保持大致粗细
    private Paint scalePaint(Paint src) {
        Paint p = new Paint(src);
        p.setStrokeWidth(1.5f);
        return p;
    }

    private Paint tinted(Paint base, int color) {
        Paint p = new Paint(base);
        p.setColor(color);
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && t != null && t.length > 0 && listener != null) {
            int w = getWidth();
            double frac = Math.max(0, Math.min(1, e.getX() / (double) w));
            int i = (int) Math.round(frac * (t.length - 1));
            double sec = t[Math.max(0, Math.min(t.length - 1, i))];
            listener.onSeek(sec);
            return true;
        }
        return super.onTouchEvent(e);
    }
}