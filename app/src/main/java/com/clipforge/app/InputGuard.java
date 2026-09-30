package com.clipforge.app;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class InputGuard {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,189}\\.[^\\s@]{2,63}$");
    private static final Pattern SAFE_NAME = Pattern.compile("^[\\p{L}\\p{M}0-9 ._'’-]{1,80}$");

    private InputGuard(){}

    public static String normalizeEmail(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validEmail(String value) {
        String email = normalizeEmail(value);
        return email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    public static boolean validPassword(String value) {
        return value != null && value.length() >= 8 && value.length() <= 128;
    }

    public static boolean validName(String value) {
        if (value == null) return false;
        String n = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        return SAFE_NAME.matcher(n).matches();
    }

    public static String safeFileName(String value) {
        String name = value == null ? "video.mp4" : Normalizer.normalize(value, Normalizer.Form.NFKC);
        name = name.replaceAll("[\\r\\n\\t\\u0000-\\u001F\\u007F]", "_");
        name = name.replace('\\', '_').replace('/', '_');
        name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        while (name.contains("..")) name = name.replace("..", ".");
        name = name.trim();
        if (name.isEmpty()) name = "video.mp4";
        if (name.length() > 96) name = name.substring(name.length() - 96);
        return name;
    }
}
