package com.example.heat_map_java_test_ux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WeeklyAreaChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previousLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Float> currentValues = new ArrayList<>();
    private final List<Float> previousValues = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private int lineColor = Color.parseColor("#FF5A1F");
    private float animationProgress = 1f;

    public WeeklyAreaChartView(Context context) {
        super(context);
        init();
    }

    public WeeklyAreaChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WeeklyAreaChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.parseColor("#2A2A2A"));
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setStyle(Paint.Style.STROKE);

        currentLinePaint.setStyle(Paint.Style.STROKE);
        currentLinePaint.setStrokeWidth(dp(2.5f));
        currentLinePaint.setStrokeCap(Paint.Cap.ROUND);
        currentLinePaint.setStrokeJoin(Paint.Join.ROUND);

        previousLinePaint.setColor(Color.parseColor("#444444"));
        previousLinePaint.setStyle(Paint.Style.STROKE);
        previousLinePaint.setStrokeWidth(dp(2f));
        previousLinePaint.setStrokeCap(Paint.Cap.ROUND);
        previousLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(6f), dp(4f)}, 0));

        fillPaint.setStyle(Paint.Style.FILL);

        pointPaint.setStyle(Paint.Style.FILL);

        xLabelPaint.setColor(Color.parseColor("#888888"));
        xLabelPaint.setTextSize(sp(10f));
        xLabelPaint.setTextAlign(Paint.Align.CENTER);

        peakLabelPaint.setColor(Color.parseColor("#FFFFFF"));
        peakLabelPaint.setTextSize(sp(11f));
        peakLabelPaint.setTextAlign(Paint.Align.CENTER);
        peakLabelPaint.setFakeBoldText(true);

        emptyPaint.setColor(Color.parseColor("#888888"));
        emptyPaint.setTextSize(sp(14f));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setChartData(List<Float> current, List<Float> previous, List<String> labels, int color) {
        currentValues.clear();
        previousValues.clear();
        this.labels.clear();

        if (current != null) currentValues.addAll(current);
        if (previous != null) previousValues.addAll(previous);
        if (labels != null) this.labels.addAll(labels);

        lineColor = color;
        currentLinePaint.setColor(lineColor);
        pointPaint.setColor(lineColor);

        int fillStart = Color.argb(75, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor));
        int fillEnd = Color.argb(0, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor));
        fillPaint.setShader(new LinearGradient(0, 0, 0, getHeight(), fillStart, fillEnd, Shader.TileMode.CLAMP));

        // added by Moemen: keep chart transitions smooth when changing filters
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(600);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            animationProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float left = dp(18f);
        float top = dp(16f);
        float right = getWidth() - dp(12f);
        float bottom = getHeight() - dp(28f);

        float chartWidth = right - left;
        float chartHeight = bottom - top;

        if (currentValues.isEmpty()) {
            canvas.drawText(getContext().getString(R.string.mes_stats_no_data), getWidth() / 2f, getHeight() / 2f, emptyPaint);
            return;
        }

        float maxValue = 1f;
        for (Float value : currentValues) {
            if (value != null) maxValue = Math.max(maxValue, value);
        }
        for (Float value : previousValues) {
            if (value != null) maxValue = Math.max(maxValue, value);
        }

        for (int i = 0; i < 4; i++) {
            float y = top + (chartHeight * i / 3f);
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        if (previousValues.size() > 1) {
            Path previousPath = buildLinePath(previousValues, left, top, chartWidth, chartHeight, maxValue, 1f);
            canvas.drawPath(previousPath, previousLinePaint);
        }

        Path currentPath = buildLinePath(currentValues, left, top, chartWidth, chartHeight, maxValue, animationProgress);
        Path fillPath = new Path(currentPath);
        fillPath.lineTo(right, bottom);
        fillPath.lineTo(left, bottom);
        fillPath.close();

        int fillStart = Color.argb(70, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor));
        int fillEnd = Color.argb(0, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor));
        fillPaint.setShader(new LinearGradient(0, top, 0, bottom, fillStart, fillEnd, Shader.TileMode.CLAMP));

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(currentPath, currentLinePaint);

        int peakIndex = 0;
        float peakValue = -1f;
        for (int i = 0; i < currentValues.size(); i++) {
            float value = currentValues.get(i);
            if (value > peakValue) {
                peakValue = value;
                peakIndex = i;
            }
        }

        float peakX = pointX(peakIndex, currentValues.size(), left, chartWidth);
        float peakY = pointY(peakValue * animationProgress, top, chartHeight, maxValue);
        canvas.drawCircle(peakX, peakY, dp(4f), pointPaint);
        canvas.drawText(formatSurface(peakValue), peakX, peakY - dp(8f), peakLabelPaint);

        if (!labels.isEmpty()) {
            int count = Math.min(labels.size(), currentValues.size());
            int step = count > 8 ? 2 : 1;
            for (int i = 0; i < count; i += step) {
                float x = pointX(i, count, left, chartWidth);
                canvas.drawText(labels.get(i), x, bottom + dp(16f), xLabelPaint);
            }
        }
    }

    private Path buildLinePath(List<Float> values, float left, float top, float chartWidth,
                               float chartHeight, float maxValue, float progress) {
        Path path = new Path();
        int count = values.size();
        if (count == 0) return path;

        float firstX = pointX(0, count, left, chartWidth);
        float firstY = pointY(values.get(0) * progress, top, chartHeight, maxValue);
        path.moveTo(firstX, firstY);

        for (int i = 1; i < count; i++) {
            float x = pointX(i, count, left, chartWidth);
            float y = pointY(values.get(i) * progress, top, chartHeight, maxValue);
            path.lineTo(x, y);
        }
        return path;
    }

    private float pointX(int index, int count, float left, float chartWidth) {
        if (count <= 1) return left + chartWidth / 2f;
        return left + (chartWidth * index / (count - 1f));
    }

    private float pointY(float value, float top, float chartHeight, float maxValue) {
        float ratio = Math.max(0f, Math.min(1f, value / Math.max(1f, maxValue)));
        return top + chartHeight - (chartHeight * ratio);
    }

    private String formatSurface(float value) {
        if (value >= 1_000_000f) {
            return String.format(Locale.US, "%.1f km2", value / 1_000_000f);
        }
        return String.format(Locale.US, "%.0f m2", value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
