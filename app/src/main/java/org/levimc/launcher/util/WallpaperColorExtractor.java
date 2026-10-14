package org.levimc.launcher.util;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

public final class WallpaperColorExtractor {

    // Wallpaper bitmaps are huge; sampling keeps the color pass cheap on low-end devices.
    private static final int MAX_SAMPLE_EDGE = 128;
    private static final int BUCKET_BITS = 4;
    private static final int BUCKET_COUNT = 1 << (BUCKET_BITS * 3);

    private WallpaperColorExtractor() {
    }

    public static int extract(Context context) {
        WallpaperManager wallpaperManager = WallpaperManager.getInstance(context);
        try {
            WallpaperColors colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null && colors.getPrimaryColor() != null) {
                return colors.getPrimaryColor().toArgb();
            }
        } catch (Throwable ignored) {
        }

        try {
            Drawable wallpaper = wallpaperManager.getDrawable();
            if (wallpaper != null) {
                return dominantColor(wallpaper);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int dominantColor(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return 0;

        float scale = Math.min(1f, (float) MAX_SAMPLE_EDGE / Math.max(width, height));
        Bitmap sample = Bitmap.createBitmap(
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)),
                Bitmap.Config.ARGB_8888);
        drawable.setBounds(new Rect(0, 0, sample.getWidth(), sample.getHeight()));
        drawable.draw(new Canvas(sample));

        long[] sums = new long[BUCKET_COUNT * 3];
        int[] counts = new int[BUCKET_COUNT];
        int[] usableCounts = new int[BUCKET_COUNT];
        for (int y = 0; y < sample.getHeight(); y++) {
            for (int x = 0; x < sample.getWidth(); x++) {
                int pixel = sample.getPixel(x, y);
                if (Color.alpha(pixel) < 128) continue;
                int bucket = bucketOf(pixel);
                counts[bucket]++;
                sums[bucket * 3] += Color.red(pixel);
                sums[bucket * 3 + 1] += Color.green(pixel);
                sums[bucket * 3 + 2] += Color.blue(pixel);
                if (isUsableAccent(pixel)) usableCounts[bucket]++;
            }
        }
        sample.recycle();

        int best = bestBucket(usableCounts);
        if (best < 0) best = bestBucket(counts);
        if (best < 0 || counts[best] == 0) return 0;

        return Color.rgb((int) (sums[best * 3] / counts[best]),
                (int) (sums[best * 3 + 1] / counts[best]),
                (int) (sums[best * 3 + 2] / counts[best]));
    }

    private static int bucketOf(int color) {
        return ((Color.red(color) >> (8 - BUCKET_BITS)) << (BUCKET_BITS * 2))
                | ((Color.green(color) >> (8 - BUCKET_BITS)) << BUCKET_BITS)
                | (Color.blue(color) >> (8 - BUCKET_BITS));
    }

    // Near white/black or gray accents stay unreadable on top of the launcher UI.
    private static boolean isUsableAccent(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1] > 0.15f && hsv[2] > 0.15f && hsv[2] < 0.97f;
    }

    private static int bestBucket(int[] counts) {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            if (counts[i] > bestCount) {
                bestCount = counts[i];
                best = i;
            }
        }
        return best;
    }
}
