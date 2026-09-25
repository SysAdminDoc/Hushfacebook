/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import app.morphe.extension.facebook.settings.PatchFamily;

/**
 * The catalog against its sources: the tables, the class generated from them, and the English
 * the code hands to {@link L10n}. It reads the files, and needs Robolectric only for PatchFamily.
 *
 * <p>What reaches the screen is held separately, by SettingsL10nTest under en-XA and in each
 * shipped language. This class holds the tables themselves and the code that feeds them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class L10nCatalogTest {
    // PatchFamily's switches are Settings, and a Setting needs the extension's context.
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Text that goes on screen as it is: the product's name and the address of its source. */
    private static final Set<String> NOT_TRANSLATED = new TreeSet<>(Arrays.asList(
            "Hushfacebook", "github.com/SysAdminDoc/Hushfacebook"));

    /** The calls that put text on the screen, a toast or a notification. */
    private static final Pattern SHOWS = Pattern.compile("\\b(?:setTitle|setSummary|setText|setContentDescription"
            + "|setContentTitle|setContentText|setShortLabel|setLongLabel|showToastShort|showToastLong|showToast"
            + "|makeText|show|setPositiveButton|setNegativeButton|setNeutralButton|setItems|setMessage|setDescription"
            + "|NotificationChannel|Builder|setClearAndUndoSummaries|say)\\s*\\(");

    /** The settings fragment's row helpers, which put their text on the screen. Elsewhere info() is a log line. */
    private static final Pattern ROW_HELPERS = Pattern.compile("\\b(?:toggle|info|category)\\s*\\(");

    private static final Pattern L10N_CALL = Pattern.compile("\\bL10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");

    @Test
    public void theGeneratedClassIsTheTables() throws IOException {
        // Every other check reads L10nTranslations, which is generated. A table edited without
        // running scripts/gen-l10n.py was invisible to all of them, and so was a generated file
        // edited by hand.
        List<String> problems = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = readTable(language);
            assertFalse(language + " has no rows", table.isEmpty());
            problems.addAll(differences(language, table, L10nTranslations.of(language)));
        }
        assertEquals("run scripts/gen-l10n.py: " + problems, 0, problems.size());
        assertEquals("a table on disk has no generated language",
                tableFiles().stream().map(L10nCatalogTest::language).collect(Collectors.toCollection(TreeSet::new)),
                new TreeSet<>(Arrays.asList(L10nTranslations.LANGUAGES)));
    }

    @Test
    public void theTableComparisonCanFail() throws IOException {
        Map<String, String> table = readTable("de");
        Map<String, String> changed = new LinkedHashMap<>(table);
        String key = table.keySet().iterator().next();
        changed.put(key, changed.get(key) + " x");
        assertEquals(1, differences("de", table, changed).size());
        changed.remove(key);
        assertEquals(1, differences("de", table, changed).size());
        changed.put("an extra row", "x");
        changed.put(key, table.get(key));
        assertEquals(1, differences("de", table, changed).size());
    }

    @Test
    public void everyTableCarriesExactlyTheEnglishTheCodeShows() throws IOException {
        Set<String> used = keysInCode();
        assertTrue("the scan found too few strings to mean anything: " + used.size(), used.size() > 80);
        for (String language : L10nTranslations.LANGUAGES) {
            Set<String> carried = new TreeSet<>(L10nTranslations.of(language).keySet());
            Set<String> missing = new TreeSet<>(used);
            missing.removeAll(carried);
            assertEquals(language + " has no row for what the code shows: " + missing, Collections.emptySet(), missing);
            Set<String> stale = new TreeSet<>(carried);
            stale.removeAll(used);
            assertEquals(language + " carries rows nothing shows any more: " + stale, Collections.emptySet(), stale);
        }
    }

    @Test
    public void everyTranslationKeepsItsKeysPlaceholders() {
        List<String> problems = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (Map.Entry<String, String> row : L10nTranslations.of(language).entrySet()) {
                String problem = placeholderProblem(row.getKey(), row.getValue());
                if (problem != null) problems.add(language + ": " + problem);
            }
        }
        assertEquals(problems.toString(), 0, problems.size());
        // The check has to be able to say no.
        assertTrue(placeholderProblem("Saved to %1$s", "Gespeichert") != null);
        assertTrue(placeholderProblem("%1$s of %2$s", "%1$s von %1$s") != null);
        assertTrue(placeholderProblem("Saved", "%1$s") != null);
        assertEquals(null, placeholderProblem("%1$s of %2$s", "%2$s: %1$s"));
    }

    /**
     * Public writing here uses no em or en dash, and no hyphen standing in for one. A control or
     * invisible formatting character in a table would reach the screen unseen.
     */
    @Test
    public void noTranslationCarriesADashOrAnInvisibleCharacter() {
        List<String> problems = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (Map.Entry<String, String> row : L10nTranslations.of(language).entrySet()) {
                String problem = textProblem(row.getValue());
                if (problem != null) problems.add(language + " " + problem + ": " + row.getKey());
            }
        }
        assertEquals(problems.toString(), 0, problems.size());
        assertTrue(textProblem("a " + (char) 0x2014 + " b") != null);
        assertTrue(textProblem("a " + (char) 0x2013 + " b") != null);
        assertTrue(textProblem("a - b") != null);
        assertTrue(textProblem("a" + (char) 0x200B + "b") != null);
        // Fillers and selectors that are neither control nor format characters, one from each
        // range, those past the BMP included.
        for (int invisible : new int[]{0x3164, 0x115F, 0x1160, 0xFFA0, 0x034F, 0xFE0F, 0x17B5, 0x2800,
                0x180B, 0xFFF0, 0x1BCA0, 0x1D173, 0xE0100}) {
            assertTrue(String.format("U+%04X passed", invisible),
                    textProblem("a" + new String(Character.toChars(invisible)) + "b") != null);
        }
        assertEquals(null, textProblem("Re-signed build fix"));
        assertEquals("accented letters are words, not invisible", null, textProblem("Ausblenden f" + (char) 0x00FC + "r"));
    }

    /** No sentence goes to a view, a toast or a notification without going through the catalog. */
    @Test
    public void noEnglishReachesTheScreenOutsideTheCatalog() throws IOException {
        List<String> unwrapped = new ArrayList<>();
        int calls = 0;
        for (Path file : sources()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            calls += unwrappedProse(file.getFileName().toString(), text, unwrapped);
        }
        assertTrue("the scan looked inside too few calls to mean anything: " + calls, calls > 60);
        assertEquals("text shown without L10n: " + unwrapped, 0, unwrapped.size());
    }

    /** The shapes the scan has to catch, put in front of it on purpose. */
    @Test
    public void theUnwrappedTextScanCanFail() {
        String sentence = "A whole sentence of English";
        String[] shapes = {
                "class A { void a(TextView v) { v.setText(\"" + sentence + "\"); } }",
                "class A { void a(Context c) { Toast.makeText(c, \"" + sentence + "\", 0).show(); } }",
                "class A { void a() { Utils.showToastLong(\"A whole \" + \"sentence of English\"); } }",
                "class A { void a(Builder b, boolean v) { b.setContentTitle(v ? \"" + sentence + "\" : L10n.t(\"x\")); } }",
                "class A { void a(Preference p) { p.setSummary(\n  // a comment\n  \"" + sentence + "\"); } }",
                "class A { void a(Preference p) { p.setTitle(L10n.t(\"Fine\") + \" " + sentence + "\"); } }",
        };
        for (String shape : shapes) {
            List<String> found = new ArrayList<>();
            unwrappedProse("A.java", shape, found);
            assertEquals("missed: " + shape, 1, found.size());
        }
        List<String> none = new ArrayList<>();
        unwrappedProse("A.java", "class A { void a(TextView v) { v.setText(L10n.f(\"" + sentence + " %1$s\", L10n.isolate(\"x\")));"
                + " v.setText(\"Hushfacebook\"); v.setText(\"%.1f MB\"); } }", none);
        assertEquals(none.toString(), 0, none.size());
    }

    /** The literal reader, which both scans lean on. */
    @Test
    public void theLiteralReaderJoinsWhatThePlusJoins() {
        String text = "x(\"one \" + /* c */ \"two\", \"three\\\"q\\\"\", c ? \"four\" : \"five\", 'x', \"%1$s\\n\")";
        byte[] kind = classify(text);
        assertEquals(Arrays.asList("one two", "three\"q\"", "four", "five", "%1$s\n"),
                literalsIn(text, kind, 1, text.length() - 1, null));
    }

    // ---- The code's English -------------------------------------------------------------

    /**
     * Every English string handed to L10n: the literals in each t, f and quantity call, the
     * stays texts PatchFamily hands over as fields, and the one constant.
     */
    static Set<String> keysInCode() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Path file : sources()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            byte[] kind = classify(text);
            Matcher call = L10N_CALL.matcher(text);
            while (call.find()) {
                if (kind[call.start()] != CODE) continue;
                int open = call.end() - 1;
                int close = closing(text, kind, open);
                assertTrue(file + ": an L10n call with no closing bracket", close > 0);
                keys.addAll(literalsIn(text, kind, open + 1, close, null));
            }
        }
        for (PatchFamily family : PatchFamily.values()) {
            if (family.staysWhilePaused != null) keys.add(family.staysWhilePaused);
        }
        keys.add("Stays in while paused");
        return keys;
    }

    /** Reports each prose literal inside a showing call and outside any L10n call; answers the calls read. */
    static int unwrappedProse(String name, String text, List<String> into) {
        byte[] kind = classify(text);
        byte[] outside = kind.clone();
        Matcher l10n = L10N_CALL.matcher(text);
        while (l10n.find()) {
            if (kind[l10n.start()] != CODE) continue;
            int close = closing(text, kind, l10n.end() - 1);
            for (int index = l10n.start(); index <= close && index >= 0; index++) outside[index] = WRAPPED;
        }
        int calls = 0;
        Matcher shows = (name.equals("HushfacebookPreferenceFragment.java")
                ? Pattern.compile(SHOWS.pattern() + "|" + ROW_HELPERS.pattern()) : SHOWS).matcher(text);
        while (shows.find()) {
            if (kind[shows.start()] != CODE) continue;
            int open = shows.end() - 1;
            int close = closing(text, kind, open);
            if (close < 0) continue;
            calls++;
            for (String literal : literalsIn(text, kind, open + 1, close, outside)) {
                if (isProse(literal)) into.add(name + ": " + literal);
            }
        }
        return calls;
    }

    /** Words for a reader: a word of three letters or more, and not a name or an address that stays as it is. */
    private static boolean isProse(String literal) {
        if (NOT_TRANSLATED.contains(literal)) return false;
        return Pattern.compile("\\p{L}{3}").matcher(literal.replaceAll("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]", ""))
                .find() && !literal.matches("[A-Z]{2,}");
    }

    // ---- Tables ---------------------------------------------------------------------------

    private static List<String> differences(String language, Map<String, String> table, Map<String, String> generated) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> row : table.entrySet()) {
            String was = generated.get(row.getKey());
            if (was == null) problems.add(language + " lacks " + row.getKey());
            else if (!was.equals(row.getValue())) problems.add(language + " differs for " + row.getKey());
        }
        for (String key : generated.keySet()) {
            if (!table.containsKey(key)) problems.add(language + " has an extra row " + key);
        }
        return problems;
    }

    /** One table, read the way scripts/gen-l10n.py reads it. */
    static Map<String, String> readTable(String language) throws IOException {
        for (File file : tableFiles()) {
            if (!language(file).equals(language)) continue;
            Map<String, String> rows = new TreeMap<>();
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || (line.startsWith("#") && !line.contains("\t"))) continue;
                int tab = line.indexOf('\t');
                assertTrue(file + ": a row with no tab: " + line, tab > 0);
                rows.put(line.substring(0, tab).replace("\\n", "\n"), line.substring(tab + 1).replace("\\n", "\n"));
            }
            return rows;
        }
        throw new AssertionError("no table for " + language);
    }

    private static List<File> tableFiles() {
        File folder = new File(root(), "extensions/shared/library/src/main/l10n");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".tsv"));
        assertTrue("no tables in " + folder, files != null && files.length > 0);
        List<File> tables = new ArrayList<>(Arrays.asList(files));
        Collections.sort(tables);
        return tables;
    }

    /** The tag a table's file name gives, lower case as L10nTranslations keys it. */
    private static String language(File table) {
        return table.getName().substring(0, table.getName().length() - 4).toLowerCase(java.util.Locale.ROOT);
    }

    static String placeholderProblem(String english, String translated) {
        List<String> wanted = all(PLACEHOLDER, english.replaceFirst("\\|(zero|one|two|few|many)$", ""));
        List<String> given = all(PLACEHOLDER, translated);
        Collections.sort(wanted);
        Collections.sort(given);
        return wanted.equals(given) ? null : "placeholders " + wanted + " became " + given + " in " + english;
    }

    /**
     * Characters that draw nothing without being control or format characters: Unicode's
     * Default_Ignorable fillers, joiners and selectors, and the blank braille cell. The same list
     * scripts/gen-l10n.py refuses.
     */
    private static final int[][] INVISIBLE_RANGES = {
            {0x034F, 0x034F}, {0x115F, 0x1160}, {0x17B4, 0x17B5}, {0x180B, 0x180F}, {0x2800, 0x2800},
            {0x3164, 0x3164}, {0xFE00, 0xFE0F}, {0xFFA0, 0xFFA0}, {0xFFF0, 0xFFF8}, {0x1BCA0, 0x1BCA3},
            {0x1D173, 0x1D17A}, {0xE0000, 0xE0FFF},
    };

    static String textProblem(String text) {
        for (int index = 0; index < text.length(); ) {
            int at = text.codePointAt(index);
            index += Character.charCount(at);
            if (at == 0x2013 || at == 0x2014) return "has a dash";
            int type = Character.getType(at);
            boolean invisible = type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
            for (int[] range : INVISIBLE_RANGES) invisible |= at >= range[0] && at <= range[1];
            if (at != '\n' && invisible) return String.format("has U+%04X", at);
        }
        return text.contains(" - ") ? "has a hyphen standing in for a dash" : null;
    }

    private static List<String> all(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    // ---- Sources --------------------------------------------------------------------------

    static List<Path> sources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String tree : new String[]{"extensions/shared/library/src/main/java", "extensions/facebook/src/main/java"}) {
            File folder = new File(root(), tree);
            assertTrue("no sources at " + folder, folder.isDirectory());
            try (Stream<Path> walk = Files.walk(folder.toPath())) {
                walk.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("L10nTranslations.java"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** The repository root, found from wherever Gradle runs the test. */
    static File root() {
        for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            if (new File(dir, "settings.gradle.kts").isFile() && new File(dir, "provenance.json").isFile()) return dir;
        }
        throw new AssertionError("no repository root above " + new File("").getAbsolutePath());
    }

    private static final byte CODE = 0;
    private static final byte COMMENT = 1;
    private static final byte LITERAL = 2;
    private static final byte WRAPPED = 3;

    /** Each character's kind: code, comment or literal (a string or a char, quotes included). */
    static byte[] classify(String text) {
        byte[] kind = new byte[text.length()];
        int at = 0;
        while (at < text.length()) {
            if (text.startsWith("//", at)) {
                int end = text.indexOf('\n', at);
                end = end < 0 ? text.length() : end;
                Arrays.fill(kind, at, end, COMMENT);
                at = end;
            } else if (text.startsWith("/*", at)) {
                int end = text.indexOf("*/", at + 2);
                end = end < 0 ? text.length() : end + 2;
                Arrays.fill(kind, at, end, COMMENT);
                at = end;
            } else if (text.charAt(at) == '"' || text.charAt(at) == '\'') {
                char quote = text.charAt(at);
                int end = at + 1;
                while (end < text.length() && text.charAt(end) != quote) end += text.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(end + 1, text.length());
                Arrays.fill(kind, at, end, LITERAL);
                at = end;
            } else {
                at++;
            }
        }
        return kind;
    }

    /** The bracket that closes the one at [open], counting only brackets in code. */
    static int closing(String text, byte[] kind, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            if (kind[index] != CODE) continue;
            if (text.charAt(index) == '(') depth++;
            if (text.charAt(index) == ')' && --depth == 0) return index;
        }
        return -1;
    }

    /**
     * The string literals between [start] and [end], each run joined by + read as one. With
     * [only], a literal counts only where [only] still marks it a literal, which is how the scan
     * above leaves out what sits inside an L10n call.
     */
    static List<String> literalsIn(String text, byte[] kind, int start, int end, byte[] only) {
        List<String> found = new ArrayList<>();
        int at = start;
        while (at < end) {
            if (kind[at] != LITERAL) {
                at++;
                continue;
            }
            int close = endOfLiteral(kind, at);
            if (text.charAt(at) != '"' || (only != null && only[at] != LITERAL)) {
                // A char literal, or a string inside an L10n call: passed over whole.
                at = close + 1;
                continue;
            }
            StringBuilder joined = new StringBuilder(unescape(text.substring(at + 1, close)));
            int next = skipBlank(text, kind, close + 1, end);
            while (next < end && kind[next] == CODE && text.charAt(next) == '+') {
                int after = skipBlank(text, kind, next + 1, end);
                if (after >= end || kind[after] != LITERAL || text.charAt(after) != '"'
                        || (only != null && only[after] != LITERAL)) break;
                close = endOfLiteral(kind, after);
                joined.append(unescape(text.substring(after + 1, close)));
                next = skipBlank(text, kind, close + 1, end);
            }
            found.add(joined.toString());
            at = close + 1;
        }
        return found;
    }

    /** The closing quote of the literal that opens at [at]: the last character of its run. */
    private static int endOfLiteral(byte[] kind, int at) {
        int close = at;
        while (close + 1 < kind.length && kind[close + 1] == LITERAL) close++;
        return close;
    }

    private static int skipBlank(String text, byte[] kind, int at, int end) {
        while (at < end && (kind[at] == COMMENT || Character.isWhitespace(text.charAt(at)))) at++;
        return at;
    }

    private static String unescape(String body) {
        StringBuilder out = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char at = body.charAt(index);
            if (at != '\\' || index + 1 >= body.length()) {
                out.append(at);
                continue;
            }
            char next = body.charAt(++index);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    out.append((char) Integer.parseInt(body.substring(index + 1, index + 5), 16));
                    index += 4;
                    break;
                default: out.append(next);
            }
        }
        return out.toString();
    }
}
