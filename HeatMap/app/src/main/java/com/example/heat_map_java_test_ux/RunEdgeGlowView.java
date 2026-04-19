package com.example.heat_map_java_test_ux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

public class RunEdgeGlowView extends View {

    private static final int[] COLORS = {
            0xFFCC1000, 0xFFFF2800, 0xFFFF5500, 0xFFFF8C00,
            0xFFFFB300, 0xFFFF7000, 0xFFFF3300, 0xFFCC1000
    };
    private static final float[] POS = {
            0.00f, 0.14f, 0.28f, 0.43f, 0.57f, 0.72f, 0.86f, 1.00f
    };

    private static final long SLOW = 5000L;
    private static final long FAST = 1000L;
    private static final float MAX_SPEED = 6.0f;

    private final Paint[] layers = new Paint[3];
    private final Paint[][] corners = new Paint[4][3];

    private final Path path = new Path();
    private final Path segment = new Path();
    private final RectF bounds = new RectF();
    private final Matrix rot = new Matrix();
    private PathMeasure pm;
    private float totalLen = 0f;

    private final float[] cx = new float[4];
    private final float[] cy = new float[4];
    private final float[] r = new float[3];

    private SweepGradient grad;
    private ValueAnimator rotAnim;
    private ValueAnimator introAnim;
    private ValueAnimator spdAnim;

    private float angle = 0f;
    private float intro = 0f;
    private long currentDur = SLOW;

    public RunEdgeGlowView(Context context) {
        this(context, null);
    }

    public RunEdgeGlowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        float[] sw = {32f, 12f, 3.5f};
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            layers[i].setStyle(Paint.Style.STROKE);
            layers[i].setStrokeCap(Paint.Cap.ROUND);
            layers[i].setStrokeWidth(dp(sw[i]));
        }

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) corners[i][j] = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        rotAnim = ValueAnimator.ofFloat(0f, 360f);
        rotAnim.setDuration(currentDur);
        rotAnim.setRepeatCount(ValueAnimator.INFINITE);
        rotAnim.setInterpolator(new LinearInterpolator());
        rotAnim.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    public void setSpeedMetersPerSecond(float speed) {
        float t = Math.min(Math.max(speed, 0) / MAX_SPEED, 1f);
        long target = (long) (SLOW + t * (FAST - SLOW));
        if (target == currentDur) return;

        if (spdAnim != null) spdAnim.cancel();
        long start = currentDur;
        currentDur = target;

        spdAnim = ValueAnimator.ofFloat(0f, 1f);
        spdAnim.setDuration(1500);
        spdAnim.setInterpolator(new DecelerateInterpolator());
        spdAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            rotAnim.setDuration(start + (long) (f * (target - start)));
        });
        spdAnim.start();
    }

    @Override
    protected void onVisibilityChanged(View v, int visibility) {
        super.onVisibilityChanged(v, visibility);
        if (visibility == VISIBLE) {
            currentDur = SLOW;
            rotAnim.setDuration(SLOW);
            if (!rotAnim.isStarted()) rotAnim.start();
            
            intro = 0f;
            if (introAnim != null) introAnim.cancel();
            introAnim = ValueAnimator.ofFloat(0f, 1f);
            introAnim.setDuration(1000);
            introAnim.setInterpolator(new DecelerateInterpolator());
            introAnim.addUpdateListener(a -> intro = (float) a.getAnimatedValue());
            introAnim.start();
        } else {
            rotAnim.cancel();
            if (introAnim != null) introAnim.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float p = dp(2f);
        bounds.set(p, p, w - p, h - p);
        path.reset();
        path.addRect(bounds, Path.Direction.CW);
        pm = new PathMeasure(path, false);
        totalLen = pm.getLength();

        cx[0] = p;     cy[0] = p;
        cx[1] = w - p; cy[1] = p;
        cx[2] = w - p; cy[2] = h - p;
        cx[3] = p;     cy[3] = h - p;

        grad = new SweepGradient(w / 2f, h / 2f, COLORS, POS);
        for (Paint paint : layers) paint.setShader(grad);

        float br = dp(26f);
        r[0] = br * 1.8f; r[1] = br; r[2] = br * 0.45f;

        int[] alphas = {25, 60, 120};
        int[] cCols = {0xFFFF3300, 0xFFFF8C00, 0xFFFFB300, 0xFFFF5500};

        for (int i = 0; i < 4; i++) {
            int rgb = cCols[i] & 0x00FFFFFF;
            for (int j = 0; j < 3; j++) {
                corners[i][j].setShader(new RadialGradient(cx[i], cy[i], r[j],
                        new int[]{(alphas[j] << 24) | rgb, rgb}, null, Shader.TileMode.CLAMP));
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (grad == null) return;

        rot.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
        grad.setLocalMatrix(rot);

        float time = SystemClock.uptimeMillis() / 1000f;
        float pulse = 0.9f + 0.1f * (float) Math.sin(time * 2f);
        int[] baseAlphas = {40, 100, 255};

        if (intro >= 1f) {
            for (int i = 0; i < layers.length; i++) {
                layers[i].setAlpha((int) (baseAlphas[i] * pulse));
                canvas.drawPath(path, layers[i]);
            }
            drawCorners(canvas, pulse, time);
        } else {
            segment.reset();
            pm.getSegment(0f, intro * totalLen, segment, true);
            for (int i = 0; i < layers.length; i++) {
                layers[i].setAlpha((int) (baseAlphas[i] * intro));
                canvas.drawPath(segment, layers[i]);
            }
        }
    }

    private void drawCorners(Canvas canvas, float pulse, float time) {
        for (int i = 0; i < 4; i++) {
            float s = (0.85f + 0.15f * (float) Math.sin(time * 2f + i)) * pulse;
            for (int j = 0; j < 3; j++) {
                corners[i][j].setAlpha((int) (255 * s));
                canvas.drawCircle(cx[i], cy[i], r[j] * s, corners[i][j]);
            }
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
