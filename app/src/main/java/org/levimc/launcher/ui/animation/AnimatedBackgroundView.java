package org.levimc.launcher.ui.animation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.View;

import org.levimc.launcher.util.PersonalizationManager;

import java.util.Arrays;
import java.util.Random;

public class AnimatedBackgroundView extends View implements Choreographer.FrameCallback {
    private static final long FRAME_INTERVAL_MS = 33;
    private static final String TYPE_MATRIX = "matrix";
    private static final String TYPE_DONUT = "donut";
    private static final String TYPE_FIREWORKS = "fireworks";
    private static final String TYPE_WAVE = "wave";
    private static final char[] GLYPHS = "01<>/*+-=アイウエオカキクケコサシスセソ".toCharArray();
    private static final int MATRIX_TRAIL = 14;
    private static final int WAVE_LAYERS = 4;
    private static final int MAX_PARTICLES = 320;

    private final String type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] hsv = new float[3];
    private final float[] scratchHsv = new float[3];
    private final boolean dark;
    private final float maxAlpha;
    private boolean running;
    private boolean sized;
    private long lastFrame;
    private int frameCount;

    private float textSize;
    private float columnWidth;
    private float rowHeight;
    private int columns;
    private float[] columnY;
    private float[] columnSpeed;

    private float[] torus;
    private float[] projectedX;
    private float[] projectedY;
    private float[] projectedZ;
    private long[] drawOrder;
    private float angleX;
    private float angleZ;

    private final Path wavePath = new Path();
    private float time;

    private final float[] px = new float[MAX_PARTICLES];
    private final float[] py = new float[MAX_PARTICLES];
    private final float[] pvx = new float[MAX_PARTICLES];
    private final float[] pvy = new float[MAX_PARTICLES];
    private final float[] plife = new float[MAX_PARTICLES];
    private final float[] pdecay = new float[MAX_PARTICLES];
    private final float[] phue = new float[MAX_PARTICLES];
    private final float[] rocketX = new float[3];
    private final float[] rocketY = new float[3];
    private final float[] rocketTargetY = new float[3];
    private final boolean[] rocketActive = new boolean[3];
    private int particleCount;
    private float launchTimer;
    private float gravity;
    private float density;

    public AnimatedBackgroundView(Context context, String type) {
        super(context);
        this.type = type;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        PersonalizationManager pm = new PersonalizationManager(context);
        Color.colorToHSV(pm.getAccentColor(), hsv);
        dark = pm.isDarkMode(context);
        if (!dark) {
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] *= 0.45f;
        }
        maxAlpha = dark ? 0.85f : 0.6f;

        paint.setTypeface(Typeface.MONOSPACE);
    }

    public String getType() {
        return type;
    }

    private int shade(float alpha, float valueMul, float hueOffset) {
        float v = Math.max(0f, Math.min(1f, hsv[2] * valueMul));
        scratchHsv[0] = (hsv[0] + hueOffset + 360f) % 360f;
        scratchHsv[1] = hsv[1];
        scratchHsv[2] = v;
        return Color.HSVToColor(Math.round(Math.max(0f, Math.min(1f, alpha)) * maxAlpha * 255), scratchHsv);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        density = getResources().getDisplayMetrics().density;
        gravity = 320f * density;
        if (TYPE_MATRIX.equals(type)) {
            textSize = 11f * density;
            columnWidth = textSize * 1.7f;
            rowHeight = textSize * 1.25f;
            columns = Math.max(1, (int) (w / columnWidth) + 1);
            columnY = new float[columns];
            columnSpeed = new float[columns];
            for (int i = 0; i < columns; i++) {
                columnY[i] = random.nextFloat() * h / rowHeight;
                columnSpeed[i] = 4f + random.nextFloat() * 8f;
            }
        } else if (TYPE_DONUT.equals(type)) {
            final int steps = 28;
            final int rings = 14;
            int points = steps * rings;
            torus = new float[points * 3];
            projectedX = new float[points];
            projectedY = new float[points];
            projectedZ = new float[points];
            drawOrder = new long[points];
            int idx = 0;
            for (int i = 0; i < steps; i++) {
                float theta = (float) (Math.PI * 2 * i / steps);
                float ringRadius = 1f + 0.45f * (float) Math.cos(theta);
                float z = 0.45f * (float) Math.sin(theta);
                for (int j = 0; j < rings; j++) {
                    float phi = (float) (Math.PI * 2 * j / rings);
                    torus[idx++] = ringRadius * (float) Math.cos(phi);
                    torus[idx++] = ringRadius * (float) Math.sin(phi);
                    torus[idx++] = z;
                }
            }
        }
        sized = true;
        launchTimer = 0.3f;
    }

    private void start() {
        if (!running) {
            running = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) start();
        else stop();
    }

    @Override
    public void doFrame(long frameTime) {
        if (!running) return;
        long dt = lastFrame == 0 ? FRAME_INTERVAL_MS : frameTime - lastFrame;
        if (dt >= FRAME_INTERVAL_MS && sized) {
            lastFrame = frameTime;
            step(Math.min(dt, 100f) / 1000f);
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void step(float dt) {
        frameCount++;
        if (TYPE_MATRIX.equals(type)) {
            int rows = (int) (getHeight() / rowHeight) + 1;
            for (int i = 0; i < columns; i++) {
                columnY[i] += columnSpeed[i] * dt;
                if (columnY[i] - MATRIX_TRAIL > rows) {
                    columnY[i] = -random.nextFloat() * rows * 0.5f;
                    columnSpeed[i] = 4f + random.nextFloat() * 8f;
                }
            }
        } else if (TYPE_DONUT.equals(type)) {
            angleX += 0.7f * dt;
            angleZ += 0.45f * dt;
        } else if (TYPE_WAVE.equals(type)) {
            time += dt;
        } else if (TYPE_FIREWORKS.equals(type)) {
            stepFireworks(dt);
        }
    }

    private void stepFireworks(float dt) {
        launchTimer -= dt;
        if (launchTimer <= 0) {
            launchTimer = 0.5f + random.nextFloat() * 0.7f;
            for (int r = 0; r < rocketActive.length; r++) {
                if (!rocketActive[r]) {
                    rocketX[r] = getWidth() * (0.15f + random.nextFloat() * 0.7f);
                    rocketY[r] = getHeight();
                    rocketTargetY[r] = getHeight() * (0.2f + random.nextFloat() * 0.35f);
                    rocketActive[r] = true;
                    break;
                }
            }
        }
        for (int r = 0; r < rocketActive.length; r++) {
            if (!rocketActive[r]) continue;
            float target = rocketTargetY[r];
            float distance = rocketY[r] - target;
            float step = (float) Math.sqrt(2f * gravity * distance) * dt;
            if (step >= distance) {
                burst(rocketX[r], target);
                rocketActive[r] = false;
                continue;
            }
            rocketY[r] -= step;
        }
        float drag = Math.max(0f, 1f - 1.1f * dt);
        for (int i = particleCount - 1; i >= 0; i--) {
            pvx[i] *= drag;
            pvy[i] = pvy[i] * drag + gravity * dt;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            plife[i] -= pdecay[i] * dt;
            if (plife[i] <= 0) {
                int last = --particleCount;
                px[i] = px[last]; py[i] = py[last];
                pvx[i] = pvx[last]; pvy[i] = pvy[last];
                plife[i] = plife[last]; pdecay[i] = pdecay[last]; phue[i] = phue[last];
            }
        }
    }

    private void burst(float x, float y) {
        int count = 42 + random.nextInt(20);
        float hueShift = random.nextInt(100) - 50;
        for (int i = 0; i < count && particleCount < MAX_PARTICLES; i++) {
            double angle = Math.PI * 2 * i / count + random.nextDouble() * 0.2;
            float speed = (90f + random.nextFloat() * 180f) * density;
            px[particleCount] = x;
            py[particleCount] = y;
            pvx[particleCount] = (float) (Math.cos(angle) * speed);
            pvy[particleCount] = (float) (Math.sin(angle) * speed);
            plife[particleCount] = 1f;
            pdecay[particleCount] = 0.6f + random.nextFloat() * 0.5f;
            phue[particleCount] = hueShift * (0.4f + random.nextFloat() * 0.6f);
            particleCount++;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (TYPE_MATRIX.equals(type)) drawMatrix(canvas);
        else if (TYPE_DONUT.equals(type)) drawDonut(canvas);
        else if (TYPE_WAVE.equals(type)) drawWave(canvas);
        else if (TYPE_FIREWORKS.equals(type)) drawFireworks(canvas);
    }

    private void drawMatrix(Canvas canvas) {
        paint.setTextSize(textSize);
        int bucket = frameCount / 6;
        for (int c = 0; c < columns; c++) {
            float x = (c + 0.5f) * columnWidth;
            int headRow = (int) columnY[c];
            for (int k = 0; k < MATRIX_TRAIL; k++) {
                int row = headRow - k;
                float y = (row + 1f) * rowHeight;
                if (y < -textSize || y > getHeight() + textSize) continue;
                int glyphIndex = Math.floorMod(c * 131 + row * 17 + bucket * 7, GLYPHS.length);
                if (k == 0) {
                    paint.setTextSize(textSize + density);
                    paint.setColor(shade(0.95f, 1f, 0f));
                } else {
                    paint.setTextSize(textSize);
                    float t = 1f - k / (float) MATRIX_TRAIL;
                    paint.setColor(shade(0.85f * t * t, 0.55f + 0.45f * t, 0f));
                }
                float glyphWidth = paint.measureText(GLYPHS, glyphIndex, 1);
                canvas.drawText(GLYPHS, glyphIndex, 1, x - glyphWidth / 2f, y, paint);
            }
        }
    }

    private void drawDonut(Canvas canvas) {
        float sinX = (float) Math.sin(angleX);
        float cosX = (float) Math.cos(angleX);
        float sinZ = (float) Math.sin(angleZ);
        float cosZ = (float) Math.cos(angleZ);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float scale = Math.min(getWidth(), getHeight()) * 0.85f;
        float baseRadius = 2.2f * density;

        int points = torus.length / 3;
        for (int i = 0; i < points; i++) {
            float x = torus[i * 3];
            float y = torus[i * 3 + 1];
            float z = torus[i * 3 + 2];
            float ry = y * cosX - z * sinX;
            float rz = y * sinX + z * cosX;
            float rx = x * cosZ - ry * sinZ;
            ry = x * sinZ + ry * cosZ;
            float s = scale / (3.2f - rz);
            projectedX[i] = cx + rx * s;
            projectedY[i] = cy + ry * s;
            projectedZ[i] = rz;
            drawOrder[i] = ((long) ((rz + 2f) * 4_000_000f) << 20) | i;
        }
        Arrays.sort(drawOrder);
        for (long packed : drawOrder) {
            int i = (int) (packed & 0xFFFFF);
            float z = projectedZ[i];
            float depth = (z + 1.45f) / 2.9f;
            paint.setColor(shade(0.3f + 0.65f * depth * depth, 0.5f + 0.5f * depth, 0f));
            canvas.drawCircle(projectedX[i], projectedY[i], baseRadius * (1f + z * 0.18f), paint);
        }
    }

    private void drawWave(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float sampleStep = 16f * density;
        float[] alphas = {0.14f, 0.24f, 0.36f, 0.52f};
        float[] valueMul = {0.8f, 0.88f, 0.94f, 1f};
        for (int i = 0; i < WAVE_LAYERS; i++) {
            float baseY = h * (0.32f + i * 0.14f)
                    + 10f * density * (float) Math.sin(time * (0.5f + i * 0.11f) + i * 2f);
            float amplitude = (14f + i * 8f) * density;
            float wavelength = w / (1.7f - i * 0.2f);
            float phase = (0.8f + i * 0.25f) * (float) Math.sin(time * (0.45f + i * 0.13f) + i * 1.9f);
            wavePath.reset();
            boolean first = true;
            for (float x = 0; x <= w + sampleStep; x += sampleStep) {
                float y = baseY + amplitude
                        * (float) Math.sin(x / wavelength * (float) (Math.PI * 2) + phase);
                if (first) {
                    wavePath.moveTo(x, y);
                    first = false;
                } else {
                    wavePath.lineTo(x, y);
                }
            }
            wavePath.lineTo(w, h);
            wavePath.lineTo(0, h);
            wavePath.close();
            paint.setColor(shade(alphas[i], valueMul[i], 0f));
            canvas.drawPath(wavePath, paint);
        }
    }

    private void drawFireworks(Canvas canvas) {
        for (int r = 0; r < rocketActive.length; r++) {
            if (!rocketActive[r]) continue;
            paint.setColor(shade(0.95f, 1f, 0f));
            canvas.drawCircle(rocketX[r], rocketY[r], 2.4f * density, paint);
        }
        for (int i = 0; i < particleCount; i++) {
            paint.setColor(shade(plife[i], 0.6f + 0.4f * plife[i], phue[i]));
            canvas.drawCircle(px[i], py[i], 2.2f * density * (0.45f + 0.55f * plife[i]), paint);
        }
    }
}
