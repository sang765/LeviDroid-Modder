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
    private static final String TYPE_WARP = "warp";
    private static final String TYPE_RIPPLE = "ripple";
    private static final String TYPE_BUBBLES = "bubbles";
    private static final String TYPE_FIREFLIES = "fireflies";
    private static final String TYPE_AURORA = "aurora";
    private static final String TYPE_GRID = "grid";
    private static final String TYPE_COMET = "comet";
    private static final String TYPE_RAIN = "rain";
    private static final char[] GLYPHS = "01<>/*+-=アイウエオカキクケコサシスセソ".toCharArray();
    private static final int MATRIX_TRAIL = 14;
    private static final int WAVE_LAYERS = 4;
    private static final int WARP_STARS = 90;
    private static final int RIPPLE_SLOTS = 6;
    private static final int BUBBLE_COUNT = 26;
    private static final int FLY_COUNT = 22;
    private static final int AURORA_LAYERS = 3;
    private static final int STAR_COUNT = 70;
    private static final int COMET_SLOTS = 2;
    private static final int RAIN_DROPS = 150;
    private static final int CLOUD_COUNT = 5;
    private static final int BOLT_POINTS = 24;
    private static final int MAX_PARTICLES = 320;

    private final String type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] hsv = new float[3];
    private final float[] scratchHsv = new float[3];
    private final boolean dark;
    private final float maxAlpha;
    private float speedMul = 1f;
    private float amountMul = 1f;
    private int layersOverride;
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

    private final float[] warpX = new float[WARP_STARS];
    private final float[] warpY = new float[WARP_STARS];
    private final float[] warpVx = new float[WARP_STARS];
    private final float[] warpVy = new float[WARP_STARS];
    private final float[] rippleX = new float[RIPPLE_SLOTS];
    private final float[] rippleY = new float[RIPPLE_SLOTS];
    private final float[] rippleLife = new float[RIPPLE_SLOTS];
    private final float[] rippleDuration = new float[RIPPLE_SLOTS];
    private float rippleTimer;
    private final float[] bubbleBaseX = new float[BUBBLE_COUNT];
    private final float[] bubbleY = new float[BUBBLE_COUNT];
    private final float[] bubbleR = new float[BUBBLE_COUNT];
    private final float[] bubbleSpeed = new float[BUBBLE_COUNT];
    private final float[] bubbleAmp = new float[BUBBLE_COUNT];
    private final float[] bubbleFreq = new float[BUBBLE_COUNT];
    private final float[] bubblePhase = new float[BUBBLE_COUNT];
    private final float[] bubbleAlpha = new float[BUBBLE_COUNT];
    private final float[] flyX = new float[FLY_COUNT];
    private final float[] flyY = new float[FLY_COUNT];
    private final float[] flyAngle = new float[FLY_COUNT];
    private final float[] flySpeed = new float[FLY_COUNT];
    private final float[] flyPhase = new float[FLY_COUNT];
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];
    private final float[] cometX = new float[COMET_SLOTS];
    private final float[] cometY = new float[COMET_SLOTS];
    private final float[] cometVx = new float[COMET_SLOTS];
    private final float[] cometVy = new float[COMET_SLOTS];
    private final boolean[] cometActive = new boolean[COMET_SLOTS];
    private float cometTimer;
    private final float[] dropX = new float[RAIN_DROPS];
    private final float[] dropY = new float[RAIN_DROPS];
    private final float[] dropLen = new float[RAIN_DROPS];
    private final float[] dropSpeed = new float[RAIN_DROPS];
    private final float[] cloudX = new float[CLOUD_COUNT];
    private final float[] cloudY = new float[CLOUD_COUNT];
    private final float[] cloudScale = new float[CLOUD_COUNT];
    private final float[] cloudSeed = new float[CLOUD_COUNT];
    private final float[] boltX = new float[BOLT_POINTS];
    private final float[] boltY = new float[BOLT_POINTS];
    private int boltLength;
    private float boltLife;
    private float boltTimer;
    private float flashLife;
    private boolean cloudsOn = true;
    private int cloudMode;
    private int windDir = 1;
    private float windPower;
    private boolean lightningOn;
    private float lightningRate = 30f;

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
        dark = pm.isDarkMode(context);
        applyAccent(pm.getAccentColor());
        speedMul = pm.getAnimOption(type, "speed", 100) / 100f;
        amountMul = pm.getAnimOption(type, "amount", 100) / 100f;
        layersOverride = pm.getAnimOption(type, "layers", 0);
        cloudsOn = pm.getAnimOption(type, "clouds", 1) == 1;
        cloudMode = pm.getAnimOption(type, "cloudType", 0);
        windPower = pm.getAnimOption(type, "wind", 30) / 100f;
        windDir = pm.getAnimOption(type, "windDir", 1) == 0 ? -1 : 1;
        lightningOn = pm.getAnimOption(type, "lightning", 0) == 1;
        lightningRate = Math.max(1, pm.getAnimOption(type, "lightningRate", 30));
        maxAlpha = dark ? 0.85f : 0.6f;

        paint.setTypeface(Typeface.MONOSPACE);
    }

    public void setAccentColor(int accent) {
        applyAccent(accent);
        invalidate();
    }

    private void applyAccent(int accent) {
        Color.colorToHSV(accent, hsv);
        if (!dark) {
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] *= 0.45f;
        }
    }

    public void setOption(String key, int value) {
        if ("speed".equals(key)) {
            speedMul = value / 100f;
        } else if ("layers".equals(key)) {
            layersOverride = value;
        } else if ("rings".equals(key)) {
            buildTorus(value);
        } else if ("clouds".equals(key)) {
            cloudsOn = value == 1;
        } else if ("cloudType".equals(key)) {
            cloudMode = value;
        } else if ("wind".equals(key)) {
            windPower = value / 100f;
        } else if ("windDir".equals(key)) {
            windDir = value == 0 ? -1 : 1;
        } else if ("lightning".equals(key)) {
            lightningOn = value == 1;
        } else if ("lightningRate".equals(key)) {
            lightningRate = Math.max(1, value);
        } else {
            amountMul = value / 100f;
        }
        invalidate();
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
            buildTorus(new PersonalizationManager(getContext()).getAnimOption(TYPE_DONUT, "rings", 14));
        } else if (TYPE_WARP.equals(type)) {
            for (int i = 0; i < WARP_STARS; i++) respawnWarp(i, w, h);
        } else if (TYPE_BUBBLES.equals(type)) {
            for (int i = 0; i < BUBBLE_COUNT; i++) {
                bubbleBaseX[i] = random.nextFloat() * w;
                bubbleY[i] = random.nextFloat() * h;
                bubbleR[i] = (2f + random.nextFloat() * 5f) * density;
                bubbleSpeed[i] = (18f + random.nextFloat() * 30f) * density;
                bubbleAmp[i] = (8f + random.nextFloat() * 22f) * density;
                bubbleFreq[i] = 0.5f + random.nextFloat() * 0.9f;
                bubblePhase[i] = random.nextFloat() * (float) Math.PI * 2f;
                bubbleAlpha[i] = 0.25f + random.nextFloat() * 0.3f;
            }
        } else if (TYPE_FIREFLIES.equals(type)) {
            for (int i = 0; i < FLY_COUNT; i++) {
                flyX[i] = random.nextFloat() * w;
                flyY[i] = random.nextFloat() * h;
                flyAngle[i] = random.nextFloat() * (float) Math.PI * 2f;
                flySpeed[i] = (20f + random.nextFloat() * 35f) * density;
                flyPhase[i] = random.nextFloat() * (float) Math.PI * 2f;
            }
        } else if (TYPE_COMET.equals(type)) {
            for (int i = 0; i < STAR_COUNT; i++) {
                starX[i] = random.nextFloat() * w;
                starY[i] = random.nextFloat() * h;
                starPhase[i] = random.nextFloat();
            }
        } else if (TYPE_RAIN.equals(type)) {
            for (int i = 0; i < RAIN_DROPS; i++) {
                dropX[i] = random.nextFloat() * (w + 240f * density) - 120f * density;
                dropY[i] = random.nextFloat() * h;
                dropLen[i] = (9f + random.nextFloat() * 9f) * density;
                dropSpeed[i] = (520f + random.nextFloat() * 260f) * density;
            }
            for (int i = 0; i < CLOUD_COUNT; i++) {
                cloudX[i] = random.nextFloat() * w;
                cloudY[i] = h * (0.04f + random.nextFloat() * 0.12f);
                cloudScale[i] = 0.8f + random.nextFloat() * 0.7f;
                cloudSeed[i] = random.nextFloat() * (float) Math.PI * 2f;
            }
            boltTimer = 3f;
        }
        sized = true;
        launchTimer = 0.3f;
    }

    private void buildTorus(int rings) {
        final int steps = 28;
        rings = Math.max(6, Math.min(24, rings));
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
        long nowMs = frameTime / 1_000_000;
        long dt = lastFrame == 0 ? FRAME_INTERVAL_MS : nowMs - lastFrame;
        if (dt >= FRAME_INTERVAL_MS && sized) {
            lastFrame = nowMs;
            step(Math.min(dt, 100f) / 1000f);
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void step(float dt) {
        frameCount++;
        dt *= speedMul;
        time += dt;
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
        } else if (TYPE_FIREWORKS.equals(type)) {
            stepFireworks(dt);
        } else if (TYPE_WARP.equals(type)) {
            stepWarp(dt);
        } else if (TYPE_RIPPLE.equals(type)) {
            stepRipple(dt);
        } else if (TYPE_BUBBLES.equals(type)) {
            stepBubbles(dt);
        } else if (TYPE_FIREFLIES.equals(type)) {
            stepFlies(dt);
        } else if (TYPE_COMET.equals(type)) {
            stepComet(dt);
        } else if (TYPE_RAIN.equals(type)) {
            stepRain(dt);
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

    private void stepWarp(float dt) {
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < WARP_STARS; i++) {
            warpX[i] += warpVx[i] * dt;
            warpY[i] += warpVy[i] * dt;
            warpVx[i] *= 1f + 0.35f * dt;
            warpVy[i] *= 1f + 0.35f * dt;
            if (warpX[i] < -80f * density || warpX[i] > w + 80f * density
                    || warpY[i] < -80f * density || warpY[i] > h + 80f * density) {
                respawnWarp(i, w, h);
            }
        }
    }

    private void respawnWarp(int i, int w, int h) {
        double angle = random.nextDouble() * Math.PI * 2;
        float r = random.nextFloat() * random.nextFloat() * Math.min(w, h) * 0.35f + 4f * density;
        float speed = (60f + random.nextFloat() * 130f) * density;
        warpX[i] = w / 2f + (float) Math.cos(angle) * r;
        warpY[i] = h / 2f + (float) Math.sin(angle) * r;
        warpVx[i] = (float) Math.cos(angle) * speed;
        warpVy[i] = (float) Math.sin(angle) * speed;
    }

    private void stepRipple(float dt) {
        rippleTimer -= dt;
        if (rippleTimer <= 0) {
            rippleTimer = 0.9f + random.nextFloat() * 1.2f;
            for (int i = 0; i < RIPPLE_SLOTS; i++) {
                if (rippleLife[i] <= 0) {
                    rippleX[i] = getWidth() * (0.15f + random.nextFloat() * 0.7f);
                    rippleY[i] = getHeight() * (0.15f + random.nextFloat() * 0.7f);
                    rippleLife[i] = 1f;
                    rippleDuration[i] = 2.4f + random.nextFloat() * 1.4f;
                    break;
                }
            }
        }
        for (int i = 0; i < RIPPLE_SLOTS; i++) {
            if (rippleLife[i] > 0) rippleLife[i] -= dt / rippleDuration[i];
        }
    }

    private void stepBubbles(float dt) {
        int h = getHeight();
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            bubbleY[i] -= bubbleSpeed[i] * dt;
            if (bubbleY[i] < -8f * density) {
                bubbleY[i] = h + 8f * density;
                bubbleBaseX[i] = random.nextFloat() * getWidth();
                bubbleSpeed[i] = (18f + random.nextFloat() * 30f) * density;
            }
        }
    }

    private void stepFlies(float dt) {
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < FLY_COUNT; i++) {
            flyAngle[i] += 1.2f * (float) Math.sin(time * 0.9f + flyPhase[i]) * dt;
            flyX[i] += (float) Math.cos(flyAngle[i]) * flySpeed[i] * dt;
            flyY[i] += (float) Math.sin(flyAngle[i]) * flySpeed[i] * dt;
            if (flyX[i] < 0) flyX[i] += w; else if (flyX[i] > w) flyX[i] -= w;
            if (flyY[i] < 0) flyY[i] += h; else if (flyY[i] > h) flyY[i] -= h;
        }
    }

    private void stepComet(float dt) {
        cometTimer -= dt;
        if (cometTimer <= 0) {
            cometTimer = (2.5f + random.nextFloat() * 3f) / Math.max(0.2f, amountMul);
            for (int i = 0; i < COMET_SLOTS; i++) {
                if (!cometActive[i]) {
                    boolean fromLeft = random.nextBoolean();
                    float margin = 60f * density;
                    cometX[i] = fromLeft ? -margin : getWidth() + margin;
                    cometY[i] = getHeight() * random.nextFloat() * 0.45f;
                    float speed = (520f + random.nextFloat() * 260f) * density;
                    double angle = fromLeft
                            ? Math.PI / 9 + random.nextDouble() * Math.PI / 9
                            : Math.PI - Math.PI / 9 - random.nextDouble() * Math.PI / 9;
                    cometVx[i] = (float) Math.cos(angle) * speed;
                    cometVy[i] = (float) Math.abs(Math.sin(angle)) * speed;
                    cometActive[i] = true;
                    break;
                }
            }
        }
        for (int i = 0; i < COMET_SLOTS; i++) {
            if (!cometActive[i]) continue;
            cometX[i] += cometVx[i] * dt;
            cometY[i] += cometVy[i] * dt;
            if (cometX[i] < -150f * density || cometX[i] > getWidth() + 150f * density
                    || cometY[i] > getHeight() + 150f * density) {
                cometActive[i] = false;
            }
        }
    }

    private void stepRain(float dt) {
        float vx = windPower * windDir * 520f * density;
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < RAIN_DROPS; i++) {
            dropX[i] += vx * dt;
            dropY[i] += dropSpeed[i] * dt;
            if (dropX[i] < -60f * density) dropX[i] += w + 120f * density;
            else if (dropX[i] > w + 60f * density) dropX[i] -= w + 120f * density;
            if (dropY[i] > h + 30f * density) {
                dropY[i] = -dropLen[i] - random.nextFloat() * 80f * density;
                dropX[i] = random.nextFloat() * (w + 240f * density) - 120f * density;
            }
        }
        if (cloudMode == 1) {
            float drift = (12f + windPower * 130f) * windDir * density;
            for (int i = 0; i < CLOUD_COUNT; i++) {
                cloudX[i] += drift * dt;
                if (cloudX[i] > w + 220f * density) cloudX[i] -= w + 440f * density;
                else if (cloudX[i] < -220f * density) cloudX[i] += w + 440f * density;
            }
        }
        if (lightningOn) {
            boltTimer -= dt;
            if (boltTimer <= 0) {
                boltTimer = 6f / Math.max(0.05f, lightningRate / 25f) * (0.5f + random.nextFloat());
                strike();
            }
        } else {
            boltLife = 0f;
            flashLife = 0f;
        }
        if (boltLife > 0) boltLife -= dt;
        if (flashLife > 0) flashLife -= dt;
    }

    private void strike() {
        float x = getWidth() * (0.12f + random.nextFloat() * 0.76f);
        float y = getHeight() * 0.06f;
        float drift = windPower * windDir * 26f * density;
        float endY = getHeight() * (0.5f + random.nextFloat() * 0.3f);
        int n = 0;
        while (y < endY && n < BOLT_POINTS) {
            boltX[n] = x;
            boltY[n] = y;
            n++;
            x += (random.nextFloat() - 0.5f) * 64f * density + drift;
            y += (36f + random.nextFloat() * 46f) * density;
        }
        boltLength = n;
        boltLife = 0.26f;
        flashLife = 0.14f;
    }

    private void drawRain(Canvas canvas) {
        if (cloudsOn) drawClouds(canvas);
        paint.setStrokeWidth(1.2f * density);
        float vx = windPower * windDir * 520f * density;
        int drawn = Math.min(RAIN_DROPS, Math.max(1, Math.round(RAIN_DROPS * amountMul)));
        for (int i = 0; i < drawn; i++) {
            float tailX = dropX[i] - vx * dropLen[i] / dropSpeed[i];
            paint.setColor(shade(0.3f + 0.15f * dropSpeed[i] / (780f * density), 1f, 0f));
            canvas.drawLine(dropX[i], dropY[i], tailX, dropY[i] - dropLen[i], paint);
        }
        if (boltLife > 0 && frameCount % 4 != 0) {
            paint.setStrokeWidth(2.2f * density);
            paint.setColor(shade(0.95f, 1f, 0f));
            for (int i = 1; i < boltLength; i++) {
                canvas.drawLine(boltX[i - 1], boltY[i - 1], boltX[i], boltY[i], paint);
            }
        }
        if (flashLife > 0) {
            paint.setColor(Color.argb(Math.round(70 * flashLife / 0.14f), 235, 240, 255));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    private void drawClouds(Canvas canvas) {
        float baseGray = dark ? 205f : 96f;
        int alphaBase = dark ? 26 : 34;
        for (int i = 0; i < CLOUD_COUNT; i++) {
            float x = cloudX[i];
            if (cloudMode == 0) {
                x += (10f + windPower * 34f) * density * (float) Math.sin(time * 0.22f + cloudSeed[i]);
            }
            float s = cloudScale[i] * density;
            int alpha = Math.round(alphaBase * (0.8f + 0.2f * (float) Math.sin(time * 0.5f + cloudSeed[i] * 3f)));
            paint.setColor(Color.argb(alpha, Math.round(baseGray), Math.round(baseGray + (dark ? 6 : 2)), dark ? 216 : 108));
            canvas.drawCircle(x, cloudY[i], 30f * s, paint);
            canvas.drawCircle(x + 26f * s, cloudY[i] + 6f * s, 22f * s, paint);
            canvas.drawCircle(x - 24f * s, cloudY[i] + 8f * s, 19f * s, paint);
            canvas.drawCircle(x + 4f * s, cloudY[i] - 12f * s, 20f * s, paint);
        }
    }

    private void burst(float x, float y) {
        int count = Math.round((42 + random.nextInt(20)) * amountMul);
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
        else if (TYPE_WARP.equals(type)) drawWarp(canvas);
        else if (TYPE_RIPPLE.equals(type)) drawRipple(canvas);
        else if (TYPE_BUBBLES.equals(type)) drawBubbles(canvas);
        else if (TYPE_FIREFLIES.equals(type)) drawFlies(canvas);
        else if (TYPE_AURORA.equals(type)) drawAurora(canvas);
        else if (TYPE_GRID.equals(type)) drawGrid(canvas);
        else if (TYPE_COMET.equals(type)) drawComet(canvas);
        else if (TYPE_RAIN.equals(type)) drawRain(canvas);
    }

    private void drawMatrix(Canvas canvas) {
        paint.setTextSize(textSize);
        int bucket = frameCount / 6;
        int drawn = Math.max(1, Math.round(columns * amountMul));
        for (int c = 0; c < drawn; c++) {
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
        float[] alphas = {0.14f, 0.24f, 0.36f, 0.52f, 0.62f};
        int layers = layersOverride > 0 ? Math.min(layersOverride, alphas.length) : WAVE_LAYERS;
        for (int i = 0; i < layers; i++) {
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
            paint.setColor(shade(alphas[i], 1f, 0f));
            canvas.drawPath(wavePath, paint);
        }
    }

    private void drawWarp(Canvas canvas) {
        paint.setStrokeWidth(1.5f * density);
        int drawn = Math.max(1, Math.round(WARP_STARS * Math.min(1f, amountMul)));
        for (int i = 0; i < drawn; i++) {
            float speed = (float) Math.hypot(warpVx[i], warpVy[i]);
            float nx = warpVx[i] / speed;
            float ny = warpVy[i] / speed;
            float trail = Math.min(90f * density, speed * 0.35f);
            paint.setColor(shade(0.25f + 0.45f * speed / (260f * density), 1f, 0f));
            canvas.drawLine(warpX[i], warpY[i], warpX[i] - nx * trail, warpY[i] - ny * trail, paint);
        }
    }

    private void drawRipple(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        float maxR = Math.min(getWidth(), getHeight()) * 0.42f * amountMul;
        for (int i = 0; i < RIPPLE_SLOTS; i++) {
            if (rippleLife[i] <= 0) continue;
            float progress = 1f - rippleLife[i];
            float r = maxR * (0.08f + 0.92f * progress);
            float fade = (1f - progress) * (1f - progress);
            paint.setColor(shade(0.55f * fade, 1f, 0f));
            canvas.drawCircle(rippleX[i], rippleY[i], r, paint);
            paint.setColor(shade(0.3f * fade, 1f, 0f));
            canvas.drawCircle(rippleX[i], rippleY[i], r * 0.72f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBubbles(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        int drawn = Math.max(1, Math.round(BUBBLE_COUNT * Math.min(1f, amountMul)));
        for (int i = 0; i < drawn; i++) {
            float x = bubbleBaseX[i] + bubbleAmp[i] * (float) Math.sin(time * bubbleFreq[i] + bubblePhase[i]);
            paint.setStrokeWidth(Math.max(1f, bubbleR[i] * 0.28f));
            paint.setColor(shade(bubbleAlpha[i], 1f, 0f));
            canvas.drawCircle(x, bubbleY[i], bubbleR[i], paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawFlies(Canvas canvas) {
        int drawn = Math.max(1, Math.round(FLY_COUNT * Math.min(1f, amountMul)));
        for (int i = 0; i < drawn; i++) {
            float blink = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(time * (1.5f + flyPhase[i] % 1f) + flyPhase[i] * 7f));
            paint.setColor(shade(0.12f * blink, 1f, 0f));
            canvas.drawCircle(flyX[i], flyY[i], 4.5f * density, paint);
            paint.setColor(shade(0.9f * blink, 1f, 0f));
            canvas.drawCircle(flyX[i], flyY[i], 1.6f * density, paint);
        }
    }

    private void drawAurora(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float sample = 24f * density;
        float[] alphas = {0.16f, 0.12f, 0.09f, 0.07f, 0.05f};
        float[] hueOffsets = {0f, 18f, -22f, 30f, -35f};
        float[] tops = {0.02f, 0.08f, 0.13f, 0.18f, 0.23f};
        float[] lengths = {0.52f, 0.44f, 0.36f, 0.3f, 0.26f};
        int layers = layersOverride > 0 ? Math.min(layersOverride, alphas.length) : AURORA_LAYERS;
        for (int i = 0; i < layers; i++) {
            float anchor = h * tops[i];
            float amp = (30f + i * 10f) * density;
            float wavelength = w / (1.1f + i * 0.35f);
            float phase = time * (0.18f + i * 0.07f) + i * 2.4f;
            float bottom = anchor + h * lengths[i];
            wavePath.reset();
            boolean first = true;
            for (float x = 0; x <= w + sample; x += sample) {
                float y = anchor + amp * (float) Math.sin(x / wavelength * (float) Math.PI * 2 + phase);
                if (first) {
                    wavePath.moveTo(x, y);
                    first = false;
                } else {
                    wavePath.lineTo(x, y);
                }
            }
            for (float x = w; x >= -sample; x -= sample) {
                float y = bottom + amp * 0.6f
                        * (float) Math.sin(x / (wavelength * 1.4f) * (float) Math.PI * 2 - phase * 0.8f);
                wavePath.lineTo(x, y);
            }
            wavePath.close();
            paint.setColor(shade(alphas[i], 1f, hueOffsets[i]));
            canvas.drawPath(wavePath, paint);
        }
    }

    private void drawGrid(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float horizon = h * 0.42f;
        float cx = w / 2f;
        paint.setStrokeWidth(1.5f * density);
        int spokes = Math.max(3, Math.round(9 * amountMul));
        for (int j = -spokes; j <= spokes; j++) {
            float bottomX = cx + j * (w / 9f) * 0.9f;
            paint.setColor(shade(j == 0 ? 0.5f : 0.3f, 1f, 0f));
            canvas.drawLine(cx, horizon, bottomX, h, paint);
        }
        float scroll = time * 0.6f;
        int rows = Math.max(4, Math.round(14 * amountMul));
        for (int i = 0; i < rows; i++) {
            float z = i + 1f - (scroll % 1f);
            float y = horizon + (h - horizon) / z;
            if (y > h) continue;
            float fade = 1f / (1f + z * 0.35f);
            paint.setColor(shade(0.15f + 0.45f * fade, 1f, 0f));
            canvas.drawLine(0, y, w, y, paint);
        }
    }

    private void drawComet(Canvas canvas) {
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 0.8f + starPhase[i] * 9f);
            paint.setColor(shade(0.12f + 0.3f * twinkle, 1f, 0f));
            canvas.drawCircle(starX[i], starY[i], (i % 3 == 0 ? 1.6f : 1.1f) * density, paint);
        }
        for (int i = 0; i < COMET_SLOTS; i++) {
            if (!cometActive[i]) continue;
            float speed = (float) Math.hypot(cometVx[i], cometVy[i]);
            float nx = cometVx[i] / speed;
            float ny = cometVy[i] / speed;
            float tail = 130f * density;
            paint.setStrokeWidth(2f * density);
            paint.setColor(shade(0.28f, 1f, 0f));
            canvas.drawLine(cometX[i], cometY[i], cometX[i] - nx * tail, cometY[i] - ny * tail, paint);
            paint.setColor(shade(0.95f, 1f, 0f));
            canvas.drawCircle(cometX[i], cometY[i], 2.2f * density, paint);
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
