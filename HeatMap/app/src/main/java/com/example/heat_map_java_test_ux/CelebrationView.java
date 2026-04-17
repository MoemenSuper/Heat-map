package com.example.heat_map_java_test_ux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BlurMaskFilter;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CelebrationView extends View {

    private static final int PARTICLE_COUNT = 80; 
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private ValueAnimator animator;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BlurMaskFilter glowFilter;

    public CelebrationView(Context context) {
        super(context);
        init();
    }

    public CelebrationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        glowFilter = new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL);
        paint.setMaskFilter(glowFilter);

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles.add(new Particle());
        }

        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(2000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            updateParticles();
            invalidate();
        });
    }

    private void updateParticles() {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        for (Particle p : particles) {
            if (p.y < -50 || p.alpha <= 0) {
                p.reset(width, height);
            }
            p.y -= p.speed;
            p.x += (float) Math.cos(p.angle) * 4; 
            p.angle += 0.1;
            
            // Randomly twinkle
            if (random.nextFloat() > 0.9) {
                p.alpha -= 10;
            } else {
                p.alpha -= 1.2;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Particle p : particles) {
            paint.setColor(p.color);
            paint.setAlpha(Math.max(0, p.alpha));
            canvas.drawCircle(p.x, p.y, p.size, paint);
        }
    }

    public void start() {
        if (animator != null && !animator.isRunning()) {
            animator.start();
        }
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
        }
    }

    private class Particle {
        float x, y, size, speed, angle;
        int color, alpha;

        void reset(int width, int height) {
            x = random.nextInt(width);
            y = height + random.nextInt(200);
            size = random.nextFloat() * 12 + 6; 
            speed = random.nextFloat() * 5 + 3; 
            angle = random.nextFloat() * (float) Math.PI * 2;
            alpha = 255;
            
            int type = random.nextInt(5);
            if (type == 0) color = Color.parseColor("#FF2D55"); 
            else if (type == 1) color = Color.parseColor("#FF8C00"); 
            else if (type == 2) color = Color.parseColor("#FFD600"); 
            else if (type == 3) color = Color.parseColor("#FF3B30"); // Brighter red
            else color = Color.WHITE;
        }
    }
}
