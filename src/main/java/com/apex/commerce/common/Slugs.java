package com.apex.commerce.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

public final class Slugs {

    private Slugs() {}

    public static String slugify(String input) {
        String s = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-$", "");
        }
        return s.isEmpty() ? "item" : s;
    }

    /** slugify(input), then append -2, -3... until {@code taken} says it's free. */
    public static String unique(String input, Predicate<String> taken) {
        String base = slugify(input);
        String candidate = base;
        for (int i = 2; taken.test(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }
}
