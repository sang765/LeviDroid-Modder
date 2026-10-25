package org.levimc.launcher.util;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.HashMap;
import java.util.Map;

/** Renders Minecraft section-sign color codes as real text colors. */
public final class MinecraftTextFormatter {

    private static final Map<Character, Integer> COLORS = new HashMap<>();

    static {
        COLORS.put('0', 0xFF000000);
        COLORS.put('1', 0xFF0000AA);
        COLORS.put('2', 0xFF00AA00);
        COLORS.put('3', 0xFF00AAAA);
        COLORS.put('4', 0xFFAA0000);
        COLORS.put('5', 0xFFAA00AA);
        COLORS.put('6', 0xFFFFAA00);
        COLORS.put('7', 0xFFAAAAAA);
        COLORS.put('8', 0xFF555555);
        COLORS.put('9', 0xFF5555FF);
        COLORS.put('a', 0xFF55FF55);
        COLORS.put('b', 0xFF55FFFF);
        COLORS.put('c', 0xFFFF5555);
        COLORS.put('d', 0xFFFF55FF);
        COLORS.put('e', 0xFFFFFF55);
        COLORS.put('f', 0xFFFFFFFF);
    }

    private MinecraftTextFormatter() {
    }

    /** Strips unsupported codes and applies the supported color codes. */
    public static CharSequence format(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder plain = new StringBuilder(input.length());
        int[] colorAt = new int[input.length() + 1];
        int color = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '§' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(++i));
                Integer parsed = COLORS.get(code);
                if (parsed != null) {
                    color = parsed;
                } else if (code == 'r') {
                    color = 0;
                }
                continue;
            }
            colorAt[plain.length()] = color;
            plain.append(c);
        }
        colorAt[plain.length()] = color;

        SpannableString spannable = new SpannableString(plain);
        int runStart = 0;
        int runColor = colorAt[0];
        for (int i = 1; i <= plain.length(); i++) {
            if (colorAt[i] != runColor) {
                if (runColor != 0) {
                    spannable.setSpan(new ForegroundColorSpan(runColor), runStart, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                runStart = i;
                runColor = colorAt[i];
            }
        }
        return spannable;
    }
}
