package com.github.catvod.utils;

import java.util.Locale;

import org.skepsun.kototoro.core.text.ChineseScript;

/** Locale-gated wrapper over the shared [ChineseScript] table. */
public class Trans {

    private static final boolean ENABLE;

    static {
        ENABLE = "TW".equals(Locale.getDefault().getCountry());
    }

    public static boolean pass() {
        return !ENABLE;
    }

    public static String s2t(String text) {
        return text == null ? null : s2t(pass(), text);
    }

    public static String t2s(String text) {
        return text == null ? null : t2s(pass(), text);
    }

    public static String s2t(boolean pass, String text) {
        return pass || text.isEmpty() ? text : ChineseScript.INSTANCE.toTraditional(text);
    }

    public static String t2s(boolean pass, String text) {
        return pass || text.isEmpty() ? text : ChineseScript.INSTANCE.toSimplified(text);
    }
}
