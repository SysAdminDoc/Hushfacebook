/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/1f1f81a29ffbe8468a41067b22c05a50283ef9c4/extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/L10n.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: moved into the shared library so the settings
 * code there reads the same tables, the pseudo-locales en-XA and ar-XB, list joining and
 * capitalization in the text's own language, and numbers formatted the phone's way.
 */
package app.morphe.extension.shared;

import android.content.Context;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hushfacebook's own text in the language Facebook shows: the one picked in Facebook's own
 * settings, or the phone's when none was picked. It's read from the application's configuration,
 * so every string on a screen comes out in one language whichever context builds it.
 *
 * <p>The English string in the code is the lookup key. The translations are generated into
 * {@link L10nTranslations} from extensions/shared/library/src/main/l10n by scripts/gen-l10n.py,
 * so they travel in the extension's own code. Hushfacebook adds no resources to Facebook, and a
 * string resource would have to be merged into Facebook's resource table to be read.
 *
 * <p>A language with no table, or a string nobody translated yet, falls back to the English
 * text, so nothing here can leave a label empty. Nothing here changes Facebook's own language
 * either: the phone's languages are read, never set.
 *
 * <p>Android's two pseudo-locales work here as they do for resources. en-XA draws every string
 * accented, bracketed and about a third longer, and ar-XB draws it mirrored, right to left, so
 * a screen can be checked for clipped and truncated text without a translation. Both are
 * offered only with Developer options on.
 */
public final class L10n {
    /** A set of language tags and the answer they came to, so neither can go stale alone. */
    private static final class Table {
        final String key;
        final Map<String, String> translations;
        final String tag;

        Table(String key, Map<String, String> translations, String tag) {
            this.key = key;
            this.translations = translations;
            this.tag = tag;
        }
    }

    private static volatile Table cached;

    /** The tag of the accented pseudo-locale, en-XA, the way {@link #tags} writes it. */
    static final String ACCENTED = "en-rxa";

    /** The tag of the mirrored pseudo-locale, ar-XB. */
    static final String MIRRORED = "ar-rxb";

    /** A format placeholder, numbered or not, and a literal percent sign. Pseudo-locales leave both alone. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");

    // Built from code points so no invisible character sits in this file.
    private static final String FIRST_STRONG_ISOLATE = String.valueOf((char) 0x2068);
    private static final String POP_DIRECTIONAL_ISOLATE = String.valueOf((char) 0x2069);
    private static final String RIGHT_TO_LEFT_MARK = String.valueOf((char) 0x200F);
    private static final String RIGHT_TO_LEFT_OVERRIDE = String.valueOf((char) 0x202E);
    private static final String POP_DIRECTIONAL_FORMATTING = String.valueOf((char) 0x202C);

    /** The accented letters en-XA draws for a to z and A to Z, the ones aapt2 uses. */
    private static final int[] ACCENTS = {
            0x00e5, 0x0253, 0x00e7, 0x00f0, 0x00e9, 0x0192, 0x011d, 0x0125, 0x00ee, 0x0135, 0x0137,
            0x013c, 0x1e3f, 0x00f1, 0x00f6, 0x00fe, 0x0051, 0x0155, 0x0161, 0x0163, 0x00fb, 0x0056,
            0x0175, 0x0445, 0x00fd, 0x017e,
            0x00c5, 0x03b2, 0x00c7, 0x00d0, 0x00c9, 0x0046, 0x011c, 0x0124, 0x00ce, 0x0134, 0x0136,
            0x013b, 0x1e3e, 0x00d1, 0x00d6, 0x00de, 0x0071, 0x0154, 0x0160, 0x0162, 0x00db, 0x03bd,
            0x0174, 0x00d7, 0x00dd, 0x017d,
    };

    /** The words en-XA pads a string with, so a label has to have room for a longer language. */
    private static final String[] PADDING = {
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen", "twenty",
    };

    private L10n() {
    }

    /** {@code english} in the phone's language, read from the context the extension was given. */
    public static String t(String english) {
        return t(context(), english);
    }

    /** Text that may be anything: only plain strings are looked up, styled text passes through. */
    public static CharSequence t(Context context, CharSequence text) {
        return text instanceof String ? t(context, (String) text) : text;
    }

    public static String t(Context context, String english) {
        if (english == null || english.isEmpty()) {
            return english;
        }
        return lookUp(tags(context), english);
    }

    /** {@link String#format} over the translated form of {@code english}. */
    public static String f(String english, Object... args) {
        return f(context(), english, args);
    }

    public static String f(Context context, String english, Object... args) {
        return format(context, t(context, english), english, args);
    }

    /**
     * A count and its noun, in the phone's language, chosen by that language's plural rule.
     *
     * <p>{@code one} is the English for exactly one ("1 result") and {@code other} the English
     * for the rest ("%1$d results"); both are keys. Which one a count takes is the language's
     * decision, not {@code count == 1}: Indonesian has no "one" form and takes the other row for
     * 1, and a language with more forms than English (Polish, Russian, Arabic) keeps its extra
     * forms as rows keyed {@code other + "|" + category}, where the category is the CLDR name
     * ("few", "many", "zero", "two"). A table with no such row falls back to its other form.
     */
    public static String quantity(Context context, long count, String one, String other) {
        return quantity(context, count, one, other, new Object[]{count});
    }

    /** {@link #quantity(Context, long, String, String, Object...)} with the extension's context. */
    public static String quantity(long count, String one, String other, Object... args) {
        return quantity(context(), count, one, other, args);
    }

    /**
     * As above, with the values both forms are formatted from. Both keys number their
     * placeholders the same way, so the one form simply leaves the count unused.
     */
    public static String quantity(Context context, long count, String one, String other, Object... args) {
        List<String> tags = tags(context);
        String category = pluralCategory(context, count);
        String english = "one".equals(category) ? one : other;
        String row = pseudo(tags.get(0)) ? pseudolocalize(tags.get(0), english)
                : pluralRow(category, one, other, tableFor(tags).translations);
        return format(context, row, english, args);
    }

    /**
     * The row a category takes: the one form's translation for "one", a {@code |category} row
     * for a form English does not have, and the other form's translation for everything else.
     * English itself when the table has no row. Kept apart from the phone so a table with more
     * forms than any shipped language can be checked without one.
     */
    static String pluralRow(String category, String one, String other, Map<String, String> table) {
        if ("one".equals(category)) {
            return rowOrKey(table, one);
        }
        if (!"other".equals(category) && table != null) {
            String variant = table.get(other + "|" + category);
            if (variant != null && !variant.isEmpty()) return variant;
        }
        return rowOrKey(table, other);
    }

    private static String rowOrKey(Map<String, String> table, String key) {
        String translated = table == null ? null : table.get(key);
        return translated == null || translated.isEmpty() ? key : translated;
    }

    /**
     * The plural category {@code count} takes in the language the text comes out in. Read from
     * the phone's first language instead, a French phone reading English called 0 "one", as
     * French does, and got the English singular for no items.
     */
    static String pluralCategory(Context context, long count) {
        Locale locale = textLocale(context);
        try {
            return android.icu.text.PluralRules.forLocale(locale).select(count);
        } catch (Throwable ignored) {
            // ICU with no rules for the language: the two English forms.
            return count == 1 ? "one" : "other";
        }
    }

    /**
     * Numbers the way the phone writes them, in whichever language the text came out in: a
     * phone set to French reads "4,2 MB" inside an English sentence, as it would in any app
     * with no French. A translation that fails to format falls back to the English.
     */
    private static String format(Context context, String translated, String english, Object... args) {
        try {
            return String.format(locale(context), translated, args);
        } catch (Throwable ignored) {
            return String.format(Locale.ROOT, english, args);
        }
    }

    /**
     * Text from outside our tables (a version, a folder, a file name) set inside a sentence.
     *
     * <p>Wrapped in Unicode's first-strong isolate so that a right-to-left sentence keeps a
     * version number or a path in the order it was written, and so that such a value doesn't
     * bend the words around it. A screen reader doesn't voice the marks.
     */
    public static String isolate(CharSequence text) {
        if (text == null || text.length() == 0) return "";
        return FIRST_STRONG_ISOLATE + text + POP_DIRECTIONAL_ISOLATE;
    }

    /** {@link #join(Context, List)} with the extension's context. */
    public static String join(List<? extends CharSequence> items) {
        return join(context(), items);
    }

    /**
     * "a", "a and b", "a, b, and c", in the language the text came out in. Joined with the
     * phone's language instead, a French phone with no French table would read "a, b et c"
     * inside an English sentence.
     */
    public static String join(Context context, List<? extends CharSequence> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0).toString();
        String first = tags(context).get(0);
        if (pseudo(first)) {
            // A pseudo-locale draws the "and" as it draws every other word, so a screen check
            // can tell English left outside the catalog from the words that went through it.
            return joinWith(items, ACCENTED.equals(first) ? accent("and") : mirror("and"));
        }
        try {
            return android.icu.text.ListFormatter.getInstance(textLocale(context)).format(items);
        } catch (Throwable ignored) {
            return joinWith(items, "and");
        }
    }

    /** "a, b and c" with [and] between the last two. */
    private static String joinWith(List<? extends CharSequence> items, String and) {
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) joined.append(index == items.size() - 1 ? " " + and + " " : ", ");
            joined.append(items.get(index));
        }
        return joined.toString();
    }

    /** {@link #capitalize(Context, String)} with the extension's context. */
    public static String capitalize(String text) {
        return capitalize(context(), text);
    }

    /** {@code text} with its first letter raised the way the text's language does it. */
    public static String capitalize(Context context, String text) {
        if (text == null || text.isEmpty()) return text;
        // The first letter, past a quotation mark, a bracket or an isolate mark in front of it.
        int at = 0;
        while (at < text.length() && !Character.isLetter(text.codePointAt(at))) {
            at += Character.charCount(text.codePointAt(at));
        }
        if (at >= text.length()) return text;
        int end = at + Character.charCount(text.codePointAt(at));
        // Turkish raises i to a dotted capital, which Character.toUpperCase can't know.
        return text.substring(0, at) + text.substring(at, end).toUpperCase(textLocale(context)) + text.substring(end);
    }

    /** {@link #locale(Context)} with the extension's context. */
    public static Locale locale() {
        return locale(context());
    }

    /** The first language Facebook runs in, or the default when there is no context to ask. */
    public static Locale locale(Context context) {
        return locales(context).get(0);
    }

    /**
     * The language the text comes out in: the one whose table answers, or English when none
     * does. Pseudo-locales are English underneath.
     */
    static Locale textLocale(Context context) {
        List<String> tags = tags(context);
        if (pseudo(tags.get(0))) return Locale.ENGLISH;
        String tag = tableFor(tags).tag;
        if (tag == null) return Locale.ENGLISH;
        int region = tag.indexOf("-r");
        return region < 0 ? new Locale(tag) : new Locale(tag.substring(0, region), tag.substring(region + 2));
    }

    /**
     * Every tag worth trying, in the order the phone asks for them, each one most specific
     * first, so a table for "pt-rbr" wins over one for "pt". Android resolves a string
     * resource against the whole language list rather than the first entry alone, and reading
     * the table by hand has to do the same or a second choice language never shows.
     */
    static List<String> tags(Context context) {
        List<String> tags = new ArrayList<>(4);
        for (Locale locale : locales(context)) {
            String language = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (language.isEmpty()) {
                continue;
            }
            // A second region of a language already listed still counts: with Portugal's
            // Portuguese first and Brazil's second, the Brazilian table is the one that answers.
            String country = locale.getCountry();
            if (country != null && !country.isEmpty()) {
                String regional = language + "-r" + country.toLowerCase(Locale.ROOT);
                if (!tags.contains(regional)) tags.add(regional);
            }
            if (!tags.contains(language)) tags.add(language);
        }
        if (tags.isEmpty()) {
            tags.add(Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT));
        }
        return tags;
    }

    /**
     * The context the extension was given, read without {@link Utils#getContext()}, which logs an
     * error while there is none. A toast or a label may be built that early.
     */
    private static Context context() {
        return Utils.context;
    }

    /**
     * The languages Facebook runs in, from the application's configuration whichever context
     * asks, or the default when there is no context to ask. Facebook sets its own language on
     * the application once it has started, and an activity's or a dialog's context can carry
     * another one. Read from each caller's own context, a Back label built from the activity
     * and a title built from the application came out in two languages on one screen.
     */
    private static List<Locale> locales(Context context) {
        try {
            Context application = context == null ? null : context.getApplicationContext();
            Context source = application != null ? application : context;
            Resources resources = source == null ? null : source.getResources();
            if (resources != null) {
                LocaleList list = resources.getConfiguration().getLocales();
                List<Locale> found = new ArrayList<>(list.size());
                for (int index = 0; index < list.size(); index++) {
                    found.add(list.get(index));
                }
                if (!found.isEmpty()) {
                    return found;
                }
            }
        } catch (Throwable ignored) {
            // No context yet, or none with resources: the default locale still answers.
        }
        return Collections.singletonList(Locale.getDefault());
    }

    /** The phone's first choice decides: a pseudo-locale second in the list is never reached. */
    private static String lookUp(List<String> tags, String english) {
        String first = tags.get(0);
        if (pseudo(first)) return pseudolocalize(first, english);
        return rowOrKey(tableFor(tags).translations, english);
    }

    private static boolean pseudo(String tag) {
        return ACCENTED.equals(tag) || MIRRORED.equals(tag);
    }

    /** The first tag with a table, remembered until the phone's languages change. */
    private static Table tableFor(List<String> tags) {
        StringBuilder builder = new StringBuilder();
        for (String tag : tags) {
            builder.append(tag).append(',');
        }
        String key = builder.toString();

        Table table = cached;
        if (table != null && key.equals(table.key)) {
            return table;
        }

        Map<String, String> found = null;
        String foundTag = null;
        for (String tag : tags) {
            // English is the language the keys are written in, so it answers where the phone
            // lists it, with no table. Read past, an English-first phone with German second got
            // the German table, which Android's own lookup never gives it.
            if (tag.equals("en") || tag.startsWith("en-r")) {
                foundTag = "en";
                break;
            }
            found = L10nTranslations.of(tag);
            if (found != null) {
                foundTag = tag;
                break;
            }
        }
        table = new Table(key, found, foundTag);
        cached = table;
        return table;
    }

    /**
     * {@code english} as the pseudo-locale {@code tag} draws it. Placeholders are left whole,
     * so the values formatted into them read as they are.
     */
    static String pseudolocalize(String tag, String english) {
        StringBuilder out = new StringBuilder(english.length() * 2);
        Matcher placeholder = PLACEHOLDER.matcher(english);
        int at = 0;
        boolean accented = ACCENTED.equals(tag);
        while (at < english.length()) {
            int next = placeholder.find(at) ? placeholder.start() : english.length();
            String run = english.substring(at, next);
            out.append(accented ? accent(run) : mirror(run));
            if (next < english.length()) {
                out.append(placeholder.group());
                at = placeholder.end();
            } else {
                at = next;
            }
        }
        if (!accented) return out.toString();
        return "[" + out + " " + padding(english.length()) + "]";
    }

    private static String accent(String run) {
        StringBuilder out = new StringBuilder(run.length());
        for (int index = 0; index < run.length(); index++) {
            char letter = run.charAt(index);
            if (letter >= 'a' && letter <= 'z') out.append((char) ACCENTS[letter - 'a']);
            else if (letter >= 'A' && letter <= 'Z') out.append((char) ACCENTS[26 + letter - 'A']);
            else out.append(letter);
        }
        return out.toString();
    }

    /** Each word drawn right to left, the way aapt2 writes ar-XB: a mark, an override, the word. */
    private static String mirror(String run) {
        StringBuilder out = new StringBuilder(run.length() * 2);
        int at = 0;
        while (at < run.length()) {
            if (Character.isWhitespace(run.charAt(at))) {
                out.append(run.charAt(at++));
                continue;
            }
            int end = at;
            while (end < run.length() && !Character.isWhitespace(run.charAt(end))) end++;
            out.append(RIGHT_TO_LEFT_MARK).append(RIGHT_TO_LEFT_OVERRIDE).append(run, at, end)
                    .append(POP_DIRECTIONAL_FORMATTING).append(RIGHT_TO_LEFT_MARK);
            at = end;
        }
        return out.toString();
    }

    /**
     * Whole words, about a third of the string's length and never less than one: a short label
     * grows most in a real translation, so it gets at least a word however short it is.
     */
    private static String padding(int length) {
        int wanted = Math.max(3, (length + 2) / 3);
        StringBuilder out = new StringBuilder();
        for (int index = 0; out.length() < wanted; index++) {
            if (out.length() > 0) out.append(' ');
            out.append(PADDING[index % PADDING.length]);
        }
        return out.toString();
    }
}
