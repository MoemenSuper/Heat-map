package com.example.heat_map_java_test_ux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class AnimatedGradientHeaderView extends View {

    private static final long ANIMATION_DURATION_MS = 12000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();

    private LinearGradient gradient;
    private ValueAnimator gradientAnimator;
    private float gradientShiftPx = 0f;
    private float bottomCornerRadiusPx;

    public AnimatedGradientHeaderView(Context context) {
        super(context);
        init();
    }

    public AnimatedGradientHeaderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedGradientHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bottomCornerRadiusPx = dpToPx(32f);
        setWillNotDraw(false);
        setupAnimator();
    }

    private void setupAnimator() {
        gradientAnimator = ValueAnimator.ofFloat(0f, 1f);
        gradientAnimator.setDuration(ANIMATION_DURATION_MS);
        gradientAnimator.setRepeatCount(ValueAnimator.INFINITE);
        gradientAnimator.setRepeatMode(ValueAnimator.REVERSE);
        gradientAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            gradientShiftPx = (progress - 0.5f) * getWidth() * 0.6f;
            invalidate();
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGradient(w, h);
        rebuildClipPath(w, h);
    }

    private void rebuildGradient(int width, int height) {
        if (width <= 0 || height <= 0) {
            gradient = null;
            paint.setShader(null);
            return;
        }

        gradient = new LinearGradient(
                -width,
                0f,
                width * 2f,
                0f,
                new int[]{
                        Color.parseColor("#FF2D55"),
                        Color.parseColor("#FF6A00"),
                        Color.parseColor("#FF8C00")
                },
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        );
        paint.setShader(gradient);
    }

    private void rebuildClipPath(int width, int height) {
        clipRect.set(0f, 0f, width, height);
        clipPath.reset();
        clipPath.addRoundRect(
                clipRect,
                new float[]{
                        0f, 0f,
                        0f, 0f,
                        bottomCornerRadiusPx, bottomCornerRadiusPx,
                        bottomCornerRadiusPx, bottomCornerRadiusPx
                },
                Path.Direction.CW
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gradient == null) return;

        shaderMatrix.reset();
        shaderMatrix.setTranslate(gradientShiftPx, 0f);
        gradient.setLocalMatrix(shaderMatrix);

        int saveCount = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRect(clipRect, paint);
        canvas.restoreToCount(saveCount);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimationIfPossible();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startAnimationIfPossible();
        } else {
            stopAnimation();
        }
    }

    private void startAnimationIfPossible() {
        if (gradientAnimator == null || gradientAnimator.isStarted()) return;
        gradientAnimator.start();
    }

    private void stopAnimation() {
        if (gradientAnimator != null && gradientAnimator.isRunning()) {
            gradientAnimator.cancel();
        }
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}