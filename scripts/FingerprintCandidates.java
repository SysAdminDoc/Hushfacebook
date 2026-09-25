/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ranks the methods of a new Facebook build by how likely each one is to be a given method of an
 * older build, under the names Redex gave it this time.
 *
 * <p>Facebook renames almost every class and method on every weekly build, so a patch finds its
 * target by kept names, log literals and method shapes. When one of those anchors moves, the patch
 * fails at patch time, and somebody has to find where the method went. This does the looking. It
 * captures what survives a rebuild about the old method: its strings, its literals with the version
 * bytes Facebook changes masked, the references it makes with the obfuscated names taken out, a
 * sketch of its opcodes, its prototype, its class and who calls it. Then it scores every method of
 * the new build against that, each shared token weighed by how rare it is there.
 *
 * <p>It only reports. It never edits a patch and never picks a candidate for anybody: a run that
 * finds one candidate standing out still says to check it by hand, and a run where no candidate
 * stands out fails closed and suggests none. Callers are compared for a shortlist of the closest
 * methods only, so a candidate stands out only when no method left off it could come close either.
 *
 * <pre>
 *   java -cp &lt;cli jar&gt; FingerprintCandidates.java capture   &lt;apk&gt; &lt;method&gt; &lt;signature.json&gt;
 *   java -cp &lt;cli jar&gt; FingerprintCandidates.java rank      &lt;signature.json&gt; &lt;apk&gt; &lt;report&gt; [--top N]
 *   java -cp &lt;cli jar&gt; FingerprintCandidates.java calibrate &lt;calibration&gt; &lt;oldApk&gt; &lt;newApk&gt; &lt;report&gt; [--top N]
 * </pre>
 *
 * <p>Exit codes: 0 when a signature was written, one candidate stands out, or every calibrated case
 * ranked its known replacement within the top N; 1 when the run fails closed (no candidate stands
 * out or scores enough, or a calibrated case ranked its replacement lower); 2 when the input was
 * wrong.
 */
public final class FingerprintCandidates {

    static final String SCHEMA = "hushfacebook-fingerprint-signature";
    static final int SCHEMA_VERSION = 1;
    static final int DEFAULT_TOP = 5;

    /**
     * Candidates that go on to the call-neighbourhood comparison, per signature, at first, with every
     * method tied with the last of them ...
     */
    static final int SHORTLIST = 200;
    /**
     * ... and at most, when a method left off could still score enough with its callers to change
     * the answer. Each round takes four times as many.
     */
    static final int MAX_SHORTLIST = 51_200;

    /** A top candidate stands out only at or above this score ... */
    static final double MIN_SCORE = 0.45;
    /** ... and only this far ahead of the next one. */
    static final double MIN_MARGIN = 0.08;

    /** A class's strings kept as context: the ones with the smallest hashes, a stable sample. */
    static final int CLASS_STRINGS = 48;
    /** Callers compared, per method. */
    static final int MAX_CALLERS = 400;

    // The weight of each part of a score. Strings, literals, references and kept names do most of
    // the work, each weighed by how rare it is in the new build; the rest tells apart the methods
    // that share them.
    static final double W_TOKENS = 3.0;
    static final double W_OPCODES = 2.0;
    static final double W_PROTOTYPE = 1.5;
    static final double W_SIZE = 0.5;
    static final double W_CONTEXT = 1.5;
    static final double W_STATIC = 0.5;
    static final double W_CALLERS = 1.5;

    static final class InputException extends Exception {
        InputException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) throw new InputException(usage());
            int top = DEFAULT_TOP;
            List<String> rest = new ArrayList<>();
            for (int k = 1; k < args.length; k++) {
                if (args[k].equals("--top") && k + 1 < args.length) {
                    top = parseTop(args[++k]);
                } else {
                    rest.add(args[k]);
                }
            }
            int code;
            switch (args[0]) {
                case "capture":
                    if (rest.size() != 3) throw new InputException(usage());
                    code = capture(new File(rest.get(0)), rest.get(1), output(rest.get(2)));
                    break;
                case "rank":
                    if (rest.size() != 3) throw new InputException(usage());
                    code = rank(new File(rest.get(0)), new File(rest.get(1)), output(rest.get(2)), top);
                    break;
                case "calibrate":
                    if (rest.size() != 4) throw new InputException(usage());
                    code = calibrate(new File(rest.get(0)), new File(rest.get(1)), new File(rest.get(2)), output(rest.get(3)), top);
                    break;
                default:
                    throw new InputException(usage());
            }
            System.exit(code);
        } catch (InputException e) {
            System.out.println("[fingerprint] " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.out.println("[fingerprint] " + e);
            e.printStackTrace(System.out);
            System.exit(2);
        }
    }

    static String usage() {
        return "usage: FingerprintCandidates capture <apk> <method> <signature.json> | "
                + "rank <signature.json> <apk> <report> [--top N] | "
                + "calibrate <calibration> <oldApk> <newApk> <report> [--top N]";
    }

    static int parseTop(String value) throws InputException {
        try {
            int top = Integer.parseInt(value);
            if (top < 1 || top > 50) throw new NumberFormatException();
            return top;
        } catch (NumberFormatException e) {
            throw new InputException("--top takes a number from 1 to 50, not " + value);
        }
    }

    /**
     * The one file a run writes. It is never patch source: this reports, and a report or a signature
     * has no business in the patches tree or in a Kotlin or Java file.
     */
    static File output(String path) throws InputException {
        File file;
        try {
            // Normalized as Windows reads it, where a '..' at a drive's root stays at the root. Left
            // as typed, W:\..\x would climb out of the folder a subst drive stands for on paper while
            // the write lands inside it.
            file = new File(path).toPath().toAbsolutePath().normalize().toFile();
        } catch (java.nio.file.InvalidPathException e) {
            throw new InputException("Refusing to write " + path + ": " + e.getMessage());
        }
        // Windows drops the dots and spaces a name ends with, so Candidate.kt. is written as Candidate.kt.
        if (sourceName(file.getName().replaceAll("[. ]+$", ""))) {
            throw new InputException("Refusing to write " + file + ": it would be source, and this tool only reports.");
        }
        // The path as the file system has it, from the nearest part that exists: NTFS takes PATCHES
        // and patches. for patches, and a junction or a link reaches it under any name. A subst
        // drive is a root of its own that toRealPath keeps, so it's swapped for its folder first:
        // W:\src would never show the patches it stands for.
        File real = unsubst(file);
        while (real != null && !real.exists()) real = real.getParentFile();
        try {
            if (real != null) real = real.toPath().toRealPath().toFile();
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new InputException("Refusing to write " + file + ": " + e.getMessage());
        }
        if (real != null && real.isFile() && sourceName(real.getName())) {
            throw new InputException("Refusing to write " + file + ": it leads to " + real + ", which is source, and this tool only reports.");
        }
        for (File dir = real; dir != null; dir = dir.getParentFile()) {
            if (dir.getName().equalsIgnoreCase("patches") && new File(dir, "src").isDirectory()) {
                throw new InputException("Refusing to write " + file + ": it is inside the patch sources at " + dir
                        + ", and this tool never edits a patch.");
            }
        }
        return file;
    }

    static boolean sourceName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".kt") || lower.endsWith(".kts") || lower.endsWith(".java");
    }

    /**
     * The path with a drive subst made swapped for the folder it stands for, as often as that folder
     * is on another subst drive, or the path itself.
     */
    static File unsubst(File file) throws InputException {
        String path = file.getPath();
        String first = substFolder(path);
        if (first == null) return file;
        String rest = path.substring(2);
        String swapped = path;
        for (int hop = 0; ; hop++) {
            if (hop == 26) throw new InputException("Refusing to write " + file + ": its subst drives stand for each other in a circle.");
            String folder = substFolder(swapped);
            if (folder == null) break;
            swapped = folder + swapped.substring(2);
        }
        // A folder read back wrong (subst prints in the OEM code page) would check some other path
        // than the one written, so a drive whose folder isn't there is refused, not passed.
        if (!new File(swapped.substring(0, swapped.length() - rest.length()) + File.separator).isDirectory()) {
            throw new InputException("Refusing to write " + file + ": " + path.substring(0, 2)
                    + " is a subst drive for " + first + ", which isn't a folder this tool can find.");
        }
        return new File(swapped);
    }

    /** The folder the drive a path starts with stands for, when subst made that drive. */
    static String substFolder(String path) throws InputException {
        if (path.length() < 2 || path.charAt(1) != ':') return null;
        return substDrives().get(path.substring(0, 2).toUpperCase(Locale.ROOT));
    }

    static Map<String, String> substCache;

    /** Each subst drive and its folder, from subst's own listing ("W:\: => C:\folder"). */
    static Map<String, String> substDrives() throws InputException {
        if (substCache != null) return substCache;
        // The tests hand in a listing, so they need no drive of their own.
        String listing = System.getProperty("hushfacebook.subst");
        if (listing == null && System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                Process subst = new ProcessBuilder("subst").redirectErrorStream(true).start();
                listing = new String(subst.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                if (subst.waitFor() != 0) throw new IOException("subst exited " + subst.exitValue());
            } catch (IOException e) {
                throw new InputException("Refusing to write a report: subst couldn't list the drives it made (" + e.getMessage()
                        + "), so a path through one can't be checked.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InputException("Refusing to write a report: interrupted while listing the subst drives.");
            }
        }
        Map<String, String> drives = new HashMap<>();
        if (listing != null) {
            for (String line : listing.split("\\R")) {
                java.util.regex.Matcher m = SUBST_LINE.matcher(line.trim());
                if (m.matches()) drives.put(m.group(1).toUpperCase(Locale.ROOT), m.group(2).trim());
            }
        }
        substCache = drives;
        return drives;
    }

    static final java.util.regex.Pattern SUBST_LINE = java.util.regex.Pattern.compile("([A-Za-z]:)\\\\: => (.+)");

    // ---- names Redex keeps and names it makes up -------------------------------------------------

    /** A class Redex renamed: every one of them lands in package X. */
    static boolean obfuscatedType(String type) {
        int d = 0;
        while (d < type.length() && type.charAt(d) == '[') d++;
        return type.startsWith("LX/", d) || type.startsWith("LY/", d);
    }

    /** A member name of the shape Redex gives: an upper-case letter and two more, as A0g or BQd. */
    static boolean obfuscatedName(String name) {
        if (name.length() != 3 || name.charAt(0) < 'A' || name.charAt(0) > 'Z') return false;
        for (int k = 1; k < 3; k++) {
            char c = name.charAt(k);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** A type with an obfuscated class written as LX; so two builds agree on it. */
    static String normType(String type) {
        if (!obfuscatedType(type)) return type;
        int d = 0;
        while (type.charAt(d) == '[') d++;
        return type.substring(0, d) + "LX;";
    }

    static String normClass(String type) {
        return obfuscatedType(type) ? "X" : type;
    }

    static String normProto(Collection<? extends CharSequence> parameters, String returnType) {
        StringBuilder b = new StringBuilder("(");
        for (CharSequence p : parameters) b.append(normType(p.toString()));
        return b.append(')').append(normType(returnType)).toString();
    }

    static String descriptor(Method m) {
        StringBuilder b = new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    static String referenceKey(MethodReference r) {
        StringBuilder b = new StringBuilder(r.getDefiningClass()).append("->").append(r.getName()).append('(');
        for (CharSequence p : r.getParameterTypes()) b.append(p);
        return b.append(')').append(r.getReturnType()).toString();
    }

    /** 64-bit FNV-1a, for the call index. */
    static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int k = 0; k < s.length(); k++) {
            h ^= s.charAt(k);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ---- opcodes -----------------------------------------------------------------------------------

    /**
     * The opcode with its width taken off: const/4, const/16 and const are one family, and so are
     * invoke-virtual and invoke-virtual/range. Register allocation moves between those forms from one
     * build to the next without the code changing.
     */
    static String family(Opcode opcode) {
        String name = opcode.name;
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(0, slash);
    }

    /** One letter per family, for a sketch a person can compare by eye. */
    static char letter(String family) {
        if (family.startsWith("move-result")) return 'r';
        if (family.equals("move-exception")) return 'x';
        if (family.startsWith("move")) return 'm';
        if (family.startsWith("return")) return 'R';
        if (family.equals("const-string")) return 's';
        if (family.equals("const-class")) return 'K';
        if (family.startsWith("const")) return 'k';
        if (family.startsWith("monitor")) return 'L';
        if (family.equals("check-cast")) return 'C';
        if (family.equals("instance-of")) return 'I';
        if (family.equals("new-instance")) return 'n';
        if (family.equals("new-array") || family.startsWith("filled-new-array") || family.equals("fill-array-data")
                || family.equals("array-length")) return 'A';
        if (family.equals("throw")) return 'T';
        if (family.startsWith("goto")) return 'g';
        if (family.endsWith("switch")) return 'w';
        if (family.startsWith("cmp")) return '=';
        if (family.startsWith("if-")) return '?';
        if (family.startsWith("aget") || family.startsWith("aput")) return 'a';
        if (family.startsWith("iget")) return 'f';
        if (family.startsWith("iput")) return 'F';
        if (family.startsWith("sget")) return 'e';
        if (family.startsWith("sput")) return 'E';
        if (family.equals("invoke-static")) return 't';
        if (family.equals("invoke-virtual")) return 'v';
        if (family.equals("invoke-interface")) return 'i';
        if (family.equals("invoke-direct")) return 'd';
        if (family.equals("invoke-super")) return 'u';
        if (family.startsWith("invoke")) return 'p';
        if (family.equals("nop") || family.endsWith("payload")) return '.';
        return '+';
    }

    /** Opcode trigrams, with the start and end marked so a short method has some too. */
    static int[] trigrams(List<String> families) {
        int n = families.size() + 2;
        int[] hashes = new int[n];
        hashes[0] = "^".hashCode();
        for (int k = 0; k < families.size(); k++) hashes[k + 1] = families.get(k).hashCode();
        hashes[n - 1] = "$".hashCode();
        int[] out = new int[n - 2];
        for (int k = 0; k + 2 < n; k++) out[k] = (hashes[k] * 0x9E3779B1 + hashes[k + 1]) * 0x9E3779B1 + hashes[k + 2];
        return sortedUnique(out);
    }

    static int[] sortedUnique(int[] values) {
        if (values.length == 0) return values;
        int[] copy = values.clone();
        Arrays.sort(copy);
        int w = 1;
        for (int k = 1; k < copy.length; k++) if (copy[k] != copy[w - 1]) copy[w++] = copy[k];
        return w == copy.length ? copy : Arrays.copyOf(copy, w);
    }

    // ---- literals ----------------------------------------------------------------------------------

    /**
     * The tokens a literal contributes, or none. A resource id is left out: 86% of them moved between
     * 577 and 580. A MobileConfig id never matched exactly (0 of 851), because it carries the config's
     * version, but 95% matched once that is masked: 0x0081_0537_018523_49 on 577 is
     * 0x0081_0532_018523_49 on 580. The second family, as 0x0101_098e_0022_5045, moves its version and
     * its slot and keeps its type and id.
     */
    static void literalTokens(long value, boolean wide, Collection<String> out) {
        if (!wide) {
            if (value >= 0x7f000000L && value <= 0x7fffffffL) return;
            if (Math.abs(value) >= 256) out.add("N:" + value);
            return;
        }
        if (Math.abs(value) < 256) return;
        out.add("W:" + value);
        long top = value >>> 56;
        if (value >= 0 && (value >>> 55) == 1) {
            out.add(String.format(Locale.ROOT, "WA:%02x:%06x", (value >>> 48) & 0xff, (value >>> 8) & 0xffffff));
        } else if (top == 0x01 || top == 0x02 || top == 0x20 || top == 0x21 || (top == 0 && value >= (1L << 40))) {
            out.add(String.format(Locale.ROOT, "WB:%04x:%04x", (value >>> 48) & 0xffff, (value >>> 16) & 0xffff));
        }
    }

    // ---- a build -----------------------------------------------------------------------------------

    /** One APK's classes and methods, and an index of every call it makes. */
    static final class Build {
        final File apk;
        final Map<String, ClassDef> classes = new HashMap<>(1 << 18);
        final List<ClassDef> classList = new ArrayList<>();
        final List<Method> methods = new ArrayList<>();
        final List<ClassDef> methodClass = new ArrayList<>();
        final Map<String, Integer> indexOf = new HashMap<>(1 << 20);
        long[] callKey = new long[1 << 20];
        int[] caller = new int[1 << 20];
        int calls;

        Build(File apk) throws Exception {
            this.apk = apk;
            if (!apk.isFile()) throw new InputException("No APK at " + apk);
            MultiDexContainer<? extends DexFile> container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
            for (String entry : container.getDexEntryNames()) {
                for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                    if (classes.putIfAbsent(cd.getType(), cd) == null) classList.add(cd);
                }
            }
            if (classList.isEmpty()) throw new InputException(apk + " holds no dex classes");
        }

        /** Adds one method with a body, and every call it makes to the index. */
        int add(Method m, ClassDef cd, String descriptor) {
            int id = methods.size();
            methods.add(m);
            methodClass.add(cd);
            indexOf.put(descriptor, id);
            for (Instruction i : m.getImplementation().getInstructions()) {
                if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) {
                    if (calls == callKey.length) {
                        callKey = Arrays.copyOf(callKey, calls * 2);
                        caller = Arrays.copyOf(caller, calls * 2);
                    }
                    callKey[calls] = hash64(referenceKey((MethodReference) ((ReferenceInstruction) i).getReference()));
                    caller[calls] = id;
                    calls++;
                }
            }
            return id;
        }

        /** Every type the class could be called through: itself, its superclasses and its interfaces. */
        List<String> dispatchTypes(String type) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> work = new ArrayDeque<>();
            work.add(type);
            while (!work.isEmpty()) {
                String t = work.poll();
                if (!seen.add(t)) continue;
                out.add(t);
                ClassDef cd = classes.get(t);
                if (cd == null) continue;
                if (cd.getSuperclass() != null) work.add(cd.getSuperclass());
                work.addAll(cd.getInterfaces());
            }
            return out;
        }

        /** The call-index keys that reach this method: through its own class or a supertype. */
        long[] keysFor(Method m) {
            StringBuilder tail = new StringBuilder("->").append(m.getName()).append('(');
            for (CharSequence p : m.getParameterTypes()) tail.append(p);
            tail.append(')').append(m.getReturnType());
            List<String> types = dispatchTypes(m.getDefiningClass());
            long[] keys = new long[types.size()];
            for (int k = 0; k < keys.length; k++) keys[k] = hash64(types.get(k) + tail);
            return keys;
        }

        /** The methods that call each of these, directly or through a supertype, in one pass over the calls. */
        Map<Integer, TreeSet<Integer>> callersOf(Collection<Integer> targets) {
            Map<Long, List<Integer>> keyToMethod = new HashMap<>();
            for (int method : targets) {
                for (long key : keysFor(methods.get(method))) {
                    keyToMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(method);
                }
            }
            Map<Integer, TreeSet<Integer>> out = new HashMap<>();
            for (int k = 0; k < calls; k++) {
                List<Integer> hit = keyToMethod.get(callKey[k]);
                if (hit == null) continue;
                for (int t : hit) out.computeIfAbsent(t, x -> new TreeSet<>()).add(caller[k]);
            }
            return out;
        }

        /** The caller tokens of one method, and how many callers it has. */
        CallerInfo callerInfo(TreeSet<Integer> set, Map<ClassDef, List<String>> contexts) {
            CallerInfo info = new CallerInfo();
            info.count = set == null ? 0 : set.size();
            if (set == null) return info;
            int taken = 0;
            for (int c : set) {
                if (taken++ >= MAX_CALLERS) break;
                ClassDef cd = methodClass.get(c);
                List<String> ctx = contexts.computeIfAbsent(cd, x -> classContext(this, x, classStrings(x)));
                callerTokens(extract(methods.get(c), cd, ctx), info.tokens);
            }
            return info;
        }
    }

    static final class CallerInfo {
        int count;
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
    }

    /** Everything this tool knows about one method. The signature file holds exactly this. */
    static final class Features {
        String descriptor;
        String definingClass;
        String name;
        boolean classKept;
        boolean nameKept;
        int access;
        String prototype;
        String returnType;
        List<String> parameters = new ArrayList<>();
        int instructions;
        int registers;
        LinkedHashSet<String> strings = new LinkedHashSet<>();
        LinkedHashSet<String> literals = new LinkedHashSet<>();
        LinkedHashSet<String> references = new LinkedHashSet<>();
        List<String> context = new ArrayList<>();
        List<String> opcodes = new ArrayList<>();
        int callerCount;
        LinkedHashSet<String> callerTokens = new LinkedHashSet<>();
        String source = "";
        String sourceSha256 = "";

        boolean isStatic() {
            return AccessFlags.STATIC.isSet(access);
        }

        boolean isConstructor() {
            return name.equals("<init>") || name.equals("<clinit>");
        }

        /**
         * The tokens held to the new build's methods: strings, literals, references, and the name
         * when Redex kept it. Each weighs what its rarity says: addNewEdgeToCollection is one method
         * in 580, run is eleven thousand.
         */
        List<String> bodyTokens() {
            List<String> out = new ArrayList<>(strings.size() + literals.size() + references.size() + 1);
            for (String s : strings) out.add("S:" + s);
            out.addAll(literals);
            out.addAll(references);
            if (nameKept) out.add("NM:" + name);
            return out;
        }

        String sketch() {
            StringBuilder b = new StringBuilder(opcodes.size());
            for (String f : opcodes) b.append(letter(f));
            return b.toString();
        }
    }

    /** What one method's body says, with the names Redex makes up taken out. */
    static Features extract(Method m, ClassDef cd, List<String> context) {
        Features f = new Features();
        f.descriptor = descriptor(m);
        f.definingClass = m.getDefiningClass();
        f.name = m.getName();
        f.classKept = !obfuscatedType(f.definingClass);
        f.nameKept = !obfuscatedName(f.name);
        f.access = m.getAccessFlags();
        f.returnType = normType(m.getReturnType());
        for (CharSequence p : m.getParameterTypes()) f.parameters.add(normType(p.toString()));
        f.prototype = normProto(m.getParameterTypes(), m.getReturnType());
        f.context = context;
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return f;
        f.registers = impl.getRegisterCount();
        for (Instruction i : impl.getInstructions()) {
            f.instructions++;
            Opcode opcode = i.getOpcode();
            f.opcodes.add(family(opcode));
            if (i instanceof WideLiteralInstruction && opcode.setsWideRegister()) {
                literalTokens(((WideLiteralInstruction) i).getWideLiteral(), true, f.literals);
            } else if (i instanceof NarrowLiteralInstruction) {
                literalTokens(((NarrowLiteralInstruction) i).getNarrowLiteral(), false, f.literals);
            }
            if (!(i instanceof ReferenceInstruction)) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r instanceof StringReference) {
                f.strings.add(((StringReference) r).getString());
            } else if (r instanceof TypeReference) {
                String type = ((TypeReference) r).getType();
                if (!obfuscatedType(type)) f.references.add("T:" + type);
            } else if (r instanceof FieldReference) {
                FieldReference fr = (FieldReference) r;
                f.references.add("F:" + normClass(fr.getDefiningClass()) + "->"
                        + (obfuscatedName(fr.getName()) ? "?" : fr.getName()) + ":" + normType(fr.getType()));
                if (!obfuscatedType(fr.getDefiningClass())) f.references.add("C:" + fr.getDefiningClass());
            } else if (r instanceof MethodReference) {
                MethodReference mr = (MethodReference) r;
                f.references.add("M:" + normClass(mr.getDefiningClass()) + "->"
                        + (obfuscatedName(mr.getName()) ? "?" : mr.getName()) + normProto(mr.getParameterTypes(), mr.getReturnType()));
                if (!obfuscatedType(mr.getDefiningClass())) f.references.add("C:" + mr.getDefiningClass());
            }
        }
        return f;
    }

    /**
     * The class around a method: its own name when Redex kept it, the name Redex left in a static
     * field, its nearest kept superclass, its kept interfaces, and a stable sample of the strings its
     * methods use.
     */
    static List<String> classContext(Build build, ClassDef cd, Collection<String> classStrings) {
        List<String> out = new ArrayList<>();
        String type = cd.getType();
        if (!obfuscatedType(type)) out.add("K:" + type);
        for (Field field : cd.getStaticFields()) {
            if (field.getName().equals("__redex_internal_original_name") && field.getInitialValue() instanceof StringEncodedValue) {
                out.add("KR:" + ((StringEncodedValue) field.getInitialValue()).getValue());
            }
        }
        String ancestor = cd.getSuperclass();
        int guard = 0;
        while (ancestor != null && obfuscatedType(ancestor) && guard++ < 64) {
            ClassDef parent = build.classes.get(ancestor);
            ancestor = parent == null ? null : parent.getSuperclass();
        }
        if (ancestor != null) out.add("KS:" + ancestor);
        for (String iface : cd.getInterfaces()) if (!obfuscatedType(iface)) out.add("KI:" + iface);
        List<String> sample = new ArrayList<>(classStrings);
        sample.sort((a, b) -> {
            int c = Integer.compare(a.hashCode(), b.hashCode());
            return c != 0 ? c : a.compareTo(b);
        });
        for (int k = 0; k < Math.min(CLASS_STRINGS, sample.size()); k++) out.add("KC:" + sample.get(k));
        return out;
    }

    static Set<String> classStrings(ClassDef cd) {
        Set<String> out = new HashSet<>();
        for (Method m : cd.getMethods()) {
            MethodImplementation impl = m.getImplementation();
            if (impl == null) continue;
            for (Instruction i : impl.getInstructions()) {
                if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof StringReference) {
                    out.add(((StringReference) ((ReferenceInstruction) i).getReference()).getString());
                }
            }
        }
        return out;
    }

    /**
     * What the callers of a method have that survives a rebuild: their strings, their classes, their
     * kept names and their prototypes. The tokens are spelled as the method tokens are, so each weighs
     * what the same token weighs in a body.
     */
    static void callerTokens(Features caller, Collection<String> out) {
        for (String s : caller.strings) out.add("S:" + s);
        out.addAll(caller.context);
        if (caller.nameKept) out.add("NM:" + caller.name);
        out.add("PR:" + caller.prototype);
    }

    // ---- the old side: a signature -----------------------------------------------------------------

    /** Indexes a build's calls and captures the signature of each named method. */
    static Map<String, Features> captureAll(Build build, List<String> wanted) throws Exception {
        System.out.println("[fingerprint] reading " + build.apk.getName());
        for (ClassDef cd : build.classList) {
            for (Method m : cd.getMethods()) {
                if (m.getImplementation() != null) build.add(m, cd, descriptor(m));
            }
        }
        List<Integer> targets = new ArrayList<>();
        for (String w : wanted) {
            Integer id = build.indexOf.get(w);
            if (id == null) {
                throw new InputException("No method " + w + " with a body in " + build.apk.getName()
                        + ". Give the full descriptor, as LX/7f5;->A0g(LX/7ej;I)J.");
            }
            targets.add(id);
        }
        Map<Integer, TreeSet<Integer>> callers = build.callersOf(targets);
        Map<String, Features> out = new LinkedHashMap<>();
        Map<ClassDef, List<String>> contexts = new HashMap<>();
        for (String w : wanted) {
            int id = build.indexOf.get(w);
            ClassDef cd = build.methodClass.get(id);
            List<String> context = contexts.computeIfAbsent(cd, c -> classContext(build, c, classStrings(c)));
            Features f = extract(build.methods.get(id), cd, context);
            CallerInfo info = build.callerInfo(callers.get(id), contexts);
            f.callerCount = info.count;
            f.callerTokens = info.tokens;
            f.source = build.apk.getName();
            out.put(w, f);
        }
        return out;
    }

    static int capture(File apk, String method, File signatureOut) throws Exception {
        Build build = new Build(apk);
        Features f = captureAll(build, List.of(method)).get(method);
        f.sourceSha256 = sha256(apk);
        writeText(signatureOut, Json.write(toJson(f)));
        System.out.println("[fingerprint] captured " + method + ": " + f.instructions + " instructions, "
                + f.strings.size() + " strings, " + f.literals.size() + " literals, " + f.references.size()
                + " references, " + f.callerCount + " callers");
        System.out.println("[fingerprint] signature written to " + signatureOut);
        return 0;
    }

    // ---- the new side: every method, scored --------------------------------------------------------

    /** Token ids, and how many of the new build's methods hold each. */
    static final class Dictionary {
        final HashMap<String, Integer> ids = new HashMap<>(1 << 22);
        final List<String> names = new ArrayList<>();
        int[] df = new int[1 << 20];
        double[] idf = new double[0];
        int documents;
        double unseen;

        int add(String token) {
            Integer id = ids.get(token);
            if (id != null) return id;
            int next = names.size();
            ids.put(token, next);
            names.add(token);
            if (next == df.length) df = Arrays.copyOf(df, df.length * 2);
            return next;
        }

        int lookup(String token) {
            Integer id = ids.get(token);
            return id == null ? -1 : id;
        }

        /** Fixes the weights once every method is counted. */
        void seal(int documents) {
            this.documents = documents;
            unseen = Math.log(1.0 + documents);
            idf = new double[names.size()];
            for (int k = 0; k < idf.length; k++) idf[k] = Math.log(1.0 + (double) documents / (1.0 + df[k]));
        }

        /** How much a token says: a token no method of the new build holds weighs the most. */
        double idf(int id) {
            return id >= 0 && id < idf.length ? idf[id] : unseen;
        }
    }

    /** The new build, with every method featurized for scoring. */
    static final class Indexed {
        final Build build;
        final Dictionary dict = new Dictionary();
        int[][] body;
        double[] bodyWeight;
        int[][] context;
        double[] contextWeight;
        int[][] grams;
        int[] protoId;
        int[] returnId;
        int[][] params;
        int[] size;
        boolean[] isStatic;
        boolean[] isConstructor;
        final Map<Integer, CallerInfo> callers = new HashMap<>();

        Indexed(Build build) {
            this.build = build;
        }
    }

    static Indexed index(Build build) {
        System.out.println("[fingerprint] reading " + build.apk.getName() + " and every method in it");
        Indexed ix = new Indexed(build);
        Dictionary dict = ix.dict;
        List<int[]> body = new ArrayList<>(), context = new ArrayList<>(), grams = new ArrayList<>(), params = new ArrayList<>();
        int capacity = 1 << 20;
        int[] protoId = new int[capacity], returnId = new int[capacity], size = new int[capacity];
        boolean[] isStatic = new boolean[capacity], isConstructor = new boolean[capacity];
        for (ClassDef cd : build.classList) {
            List<Method> withCode = new ArrayList<>();
            for (Method m : cd.getMethods()) if (m.getImplementation() != null) withCode.add(m);
            if (withCode.isEmpty()) continue;
            Set<String> classStrings = new HashSet<>();
            List<Features> features = new ArrayList<>();
            for (Method m : withCode) {
                Features f = extract(m, cd, List.of());
                features.add(f);
                classStrings.addAll(f.strings);
            }
            int[] ctx = ids(dict, classContext(build, cd, classStrings));
            for (int k = 0; k < withCode.size(); k++) {
                Features f = features.get(k);
                int id = build.add(withCode.get(k), cd, f.descriptor);
                if (id == capacity) {
                    capacity *= 2;
                    protoId = Arrays.copyOf(protoId, capacity);
                    returnId = Arrays.copyOf(returnId, capacity);
                    size = Arrays.copyOf(size, capacity);
                    isStatic = Arrays.copyOf(isStatic, capacity);
                    isConstructor = Arrays.copyOf(isConstructor, capacity);
                }
                body.add(ids(dict, f.bodyTokens()));
                context.add(ctx);
                grams.add(trigrams(f.opcodes));
                int[] p = new int[f.parameters.size()];
                for (int q = 0; q < p.length; q++) p[q] = dict.add("P:" + f.parameters.get(q));
                params.add(p);
                protoId[id] = dict.add("PR:" + f.prototype);
                returnId[id] = dict.add("P:" + f.returnType);
                size[id] = f.instructions;
                isStatic[id] = f.isStatic();
                isConstructor[id] = f.isConstructor();
            }
        }
        int n = build.methods.size();
        for (int k = 0; k < n; k++) {
            for (int t : body.get(k)) dict.df[t]++;
            for (int t : context.get(k)) dict.df[t]++;
            dict.df[protoId[k]]++;
        }
        dict.seal(n);
        ix.body = body.toArray(new int[0][]);
        ix.context = context.toArray(new int[0][]);
        ix.grams = grams.toArray(new int[0][]);
        ix.params = params.toArray(new int[0][]);
        ix.protoId = Arrays.copyOf(protoId, n);
        ix.returnId = Arrays.copyOf(returnId, n);
        ix.size = Arrays.copyOf(size, n);
        ix.isStatic = Arrays.copyOf(isStatic, n);
        ix.isConstructor = Arrays.copyOf(isConstructor, n);
        ix.bodyWeight = new double[n];
        ix.contextWeight = new double[n];
        for (int k = 0; k < n; k++) {
            ix.bodyWeight[k] = weight(dict, ix.body[k]);
            ix.contextWeight[k] = weight(dict, ix.context[k]);
        }
        System.out.println("[fingerprint] " + n + " methods with code, " + dict.names.size() + " distinct tokens, "
                + build.calls + " calls");
        return ix;
    }

    static int[] ids(Dictionary dict, Collection<String> tokens) {
        int[] out = new int[tokens.size()];
        int k = 0;
        for (String t : tokens) out[k++] = dict.add(t);
        return sortedUnique(out);
    }

    static double weight(Dictionary dict, int[] tokens) {
        double sum = 0;
        for (int t : tokens) sum += dict.idf(t);
        return sum;
    }

    /** A signature held against the new build's dictionary. */
    static final class Query {
        final Features f;
        final int[] body;
        final double bodyWeight;
        final int[] context;
        final double contextWeight;
        final int[] grams;
        final int protoId;
        final int returnId;
        final int[] params;
        final boolean isStatic;
        final boolean isConstructor;
        final int[] callers;
        final double callerWeight;
        /** The best callers score any method can have: the part of callerWeight the new build holds. */
        final double callerCeiling;

        Query(Features f, Dictionary dict) {
            this.f = f;
            Holder b = known(dict, f.bodyTokens());
            body = b.ids;
            bodyWeight = b.weight;
            Holder c = known(dict, f.context);
            context = c.ids;
            contextWeight = c.weight;
            grams = trigrams(f.opcodes);
            protoId = dict.lookup("PR:" + f.prototype);
            returnId = dict.lookup("P:" + f.returnType);
            params = new int[f.parameters.size()];
            for (int k = 0; k < params.length; k++) params[k] = dict.lookup("P:" + f.parameters.get(k));
            isStatic = f.isStatic();
            isConstructor = f.isConstructor();
            Holder k = known(dict, f.callerTokens);
            callers = k.ids;
            callerWeight = k.weight;
            // Shared weight over the union is at most shared over this side's weight, and only the
            // tokens the new build has can be shared.
            callerCeiling = callerWeight > 0 ? Math.min(1.0, weight(dict, callers) / callerWeight) : 0;
        }

        /** The weight of the parts that need no callers: every part but the class when this has none. */
        double partialWeight() {
            return W_TOKENS + W_OPCODES + W_PROTOTYPE + W_SIZE + W_STATIC + (contextWeight > 0 ? W_CONTEXT : 0);
        }
    }

    static final class Holder {
        int[] ids;
        double weight;
    }

    /**
     * The tokens the new build has, and the weight of all of them. A token it lacks can't be shared,
     * but it still counts against every candidate alike.
     */
    static Holder known(Dictionary dict, Collection<String> tokens) {
        Holder h = new Holder();
        List<Integer> ids = new ArrayList<>();
        for (String t : new LinkedHashSet<>(tokens)) {
            int id = dict.lookup(t);
            h.weight += dict.idf(id);
            if (id >= 0) ids.add(id);
        }
        int[] a = new int[ids.size()];
        for (int k = 0; k < a.length; k++) a[k] = ids.get(k);
        h.ids = sortedUnique(a);
        return h;
    }

    /** Shared weight over the weight of both, for sorted id arrays. */
    static double weightedJaccard(Dictionary dict, int[] a, double aWeight, int[] b, double bWeight) {
        if (aWeight <= 0 && bWeight <= 0) return 0;
        double shared = 0;
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            int x = a[i], y = b[j];
            if (x == y) {
                shared += dict.idf(x);
                i++;
                j++;
            } else if (x < y) {
                i++;
            } else {
                j++;
            }
        }
        double union = aWeight + bWeight - shared;
        return union <= 0 ? 0 : shared / union;
    }

    static double jaccard(int[] a, int[] b) {
        if (a.length == 0 && b.length == 0) return 1;
        int shared = 0, i = 0, j = 0;
        while (i < a.length && j < b.length) {
            int x = a[i], y = b[j];
            if (x == y) {
                shared++;
                i++;
                j++;
            } else if (x < y) {
                i++;
            } else {
                j++;
            }
        }
        return (double) shared / (a.length + b.length - shared);
    }

    /** Longest common subsequence of two parameter lists, with a reusable row buffer. */
    static int lcs(int[] a, int[] b, int[][] rows) {
        if (a.length == 0 || b.length == 0) return 0;
        if (rows[0].length <= b.length) {
            rows[0] = new int[b.length + 1];
            rows[1] = new int[b.length + 1];
        }
        int[] prev = rows[0], cur = rows[1];
        Arrays.fill(prev, 0, b.length + 1, 0);
        cur[0] = 0;
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                cur[j] = a[i - 1] == b[j - 1] && a[i - 1] >= 0 ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length];
    }

    static double prototypeSimilarity(Query q, Indexed ix, int c, int[][] rows) {
        if (q.protoId >= 0 && q.protoId == ix.protoId[c]) return 1.0;
        double ret = q.returnId >= 0 && q.returnId == ix.returnId[c] ? 1.0 : 0.0;
        int[] p = ix.params[c];
        double params = q.params.length + p.length == 0 ? 1.0 : 2.0 * lcs(q.params, p, rows) / (q.params.length + p.length);
        return 0.35 * ret + 0.65 * params;
    }

    /** The scores of one candidate, by part, and the total. */
    static final class Score {
        int method;
        double tokens, opcodes, prototype, size, context, callers = -1;
        boolean staticMatch;
        double total;
        double partial;
        double partialWeight;
    }

    static Score score(Query q, Indexed ix, int c, int[][] rows) {
        Score s = new Score();
        s.method = c;
        s.tokens = weightedJaccard(ix.dict, q.body, q.bodyWeight, ix.body[c], ix.bodyWeight[c]);
        s.opcodes = jaccard(q.grams, ix.grams[c]);
        s.prototype = prototypeSimilarity(q, ix, c, rows);
        int a = q.f.instructions, b = ix.size[c];
        s.size = (Math.min(a, b) + 1.0) / (Math.max(a, b) + 1.0);
        s.staticMatch = q.isStatic == ix.isStatic[c] && q.isConstructor == ix.isConstructor[c];
        double sum = W_TOKENS * s.tokens + W_OPCODES * s.opcodes + W_PROTOTYPE * s.prototype + W_SIZE * s.size
                + W_STATIC * (s.staticMatch ? 1 : 0);
        if (q.contextWeight > 0) {
            s.context = weightedJaccard(ix.dict, q.context, q.contextWeight, ix.context[c], ix.contextWeight[c]);
            sum += W_CONTEXT * s.context;
        }
        s.partial = sum;
        s.partialWeight = q.partialWeight();
        s.total = sum / s.partialWeight;
        return s;
    }

    /** One signature's ranking over the whole new build. */
    static final class Ranking {
        Query query;
        List<Score> ranked = new ArrayList<>();
        String verdict;
        boolean standsOut;
        /** The best score before callers of a method left off the shortlist, or -1 when none was. */
        double excluded = -1;
        /** The most a method left off could score once its callers count, or -1 when none was left off. */
        double bound = -1;
        /** A method left off could still take first place, or come too close to one that stands out. */
        boolean unsure;
        /** In a calibration: a method left off could still rank above the known replacement. */
        boolean knownUnsure;
    }

    /**
     * The best methods of the new build for one query, by the parts that need no callers: the first
     * size of them and every method tied with the last one, so the order of the dex never decides
     * which of a tie go on. A tie too big to compare stays out whole, and counts in the bound.
     */
    static Ranking shortlist(Query q, Indexed ix, int size) {
        int n = ix.build.methods.size();
        int[][] rows = {new int[64], new int[64]};
        double[] totals = new double[n];
        for (int c = 0; c < n; c++) totals[c] = score(q, ix, c, rows).total;
        double cut = Double.NEGATIVE_INFINITY;
        if (n > size) {
            double[] sorted = totals.clone();
            Arrays.sort(sorted);
            int last = n - size;
            cut = sorted[last];
            int first = last;
            while (first > 0 && sorted[first - 1] == cut) first--;
            if (n - first > MAX_SHORTLIST) {
                while (last < n && sorted[last] == cut) last++;
                cut = last < n ? sorted[last] : Double.POSITIVE_INFINITY;
            }
        }
        Ranking r = new Ranking();
        r.query = q;
        for (int c = 0; c < n; c++) {
            if (totals[c] >= cut) r.ranked.add(score(q, ix, c, rows));
            else r.excluded = Math.max(r.excluded, totals[c]);
        }
        r.ranked.sort(FingerprintCandidates::byTotal);
        return r;
    }

    static List<Ranking> rankAll(Indexed ix, List<Features> signatures) {
        return rankAll(ix, signatures, null);
    }

    /**
     * Ranks each signature. Callers are compared for the shortlisted methods only, so once they
     * count, each ranking works out the most a method left off could score: its score so far, with
     * callers matching as well as the new build allows. While that could still change the answer, or
     * in a calibration the known replacement's rank, the shortlist grows fourfold, up to MAX_SHORTLIST;
     * past that the verdict says what could still overtake it.
     */
    static List<Ranking> rankAll(Indexed ix, List<Features> signatures, int[] known) {
        List<Query> queries = new ArrayList<>();
        for (Features f : signatures) queries.add(new Query(f, ix.dict));
        Ranking[] rankings = new Ranking[queries.size()];
        Map<Integer, int[]> callerIds = new HashMap<>();
        Map<Integer, Double> callerWeights = new HashMap<>();
        Map<ClassDef, List<String>> contexts = new HashMap<>();
        List<Integer> open = new ArrayList<>();
        for (int k = 0; k < queries.size(); k++) open.add(k);
        for (int size = SHORTLIST; !open.isEmpty(); size *= 4) {
            int cutTo = size;
            open.parallelStream().forEach(k -> rankings[k] = shortlist(queries.get(k), ix, cutTo));

            // The call neighbourhood of the shortlisted methods not compared yet: one pass over the call index.
            Set<Integer> fresh = new TreeSet<>();
            for (int k : open) for (Score s : rankings[k].ranked) if (!ix.callers.containsKey(s.method)) fresh.add(s.method);
            Map<Integer, TreeSet<Integer>> callers = ix.build.callersOf(fresh);
            for (int method : fresh) {
                CallerInfo info = ix.build.callerInfo(callers.get(method), contexts);
                ix.callers.put(method, info);
                Holder h = known(ix.dict, info.tokens);
                callerIds.put(method, h.ids);
                callerWeights.put(method, h.weight);
            }

            List<Integer> wider = new ArrayList<>();
            for (int k : open) {
                Ranking r = rankings[k];
                Query q = r.query;
                if (q.f.callerCount > 0) {
                    for (Score s : r.ranked) {
                        s.callers = weightedJaccard(ix.dict, q.callers, q.callerWeight,
                                callerIds.get(s.method), callerWeights.get(s.method));
                        s.total = (s.partial + W_CALLERS * s.callers) / (s.partialWeight + W_CALLERS);
                    }
                    r.ranked.sort(FingerprintCandidates::byTotal);
                    r.bound = r.excluded < 0 ? -1
                            : (r.excluded * q.partialWeight() + W_CALLERS * q.callerCeiling) / (q.partialWeight() + W_CALLERS);
                } else {
                    r.bound = r.excluded;
                }
                decide(r, known == null ? -1 : known[k]);
                if ((r.unsure || r.knownUnsure) && size < MAX_SHORTLIST) wider.add(k);
            }
            open = wider;
        }
        return Arrays.asList(rankings);
    }

    static int byTotal(Score a, Score b) {
        int c = Double.compare(b.total, a.total);
        return c != 0 ? c : Integer.compare(a.method, b.method);
    }

    /**
     * Whether the top candidate stands out, or the run fails closed. A method left off the shortlist
     * could still score the bound, so a candidate stands out only when the bound is under it by the
     * margin too, and the ranking is unsure while a method left off could take first place.
     */
    static void decide(Ranking r, int known) {
        r.standsOut = false;
        r.unsure = false;
        r.knownUnsure = false;
        if (known >= 0 && r.bound >= 0) {
            Score k = null;
            for (Score s : r.ranked) if (s.method == known) k = s;
            r.knownUnsure = k == null || r.bound >= k.total;
        }
        if (r.ranked.isEmpty()) {
            r.verdict = r.excluded < 0 ? "the new build has no method with code to compare"
                    : String.format(Locale.ROOT, "more than %d methods tie for the best score before callers count, "
                            + "too many to compare their callers", MAX_SHORTLIST);
            return;
        }
        double best = r.ranked.get(0).total;
        double next = r.ranked.size() > 1 ? r.ranked.get(1).total : 0;
        String leftOff = String.format(Locale.ROOT, "; callers were compared for the closest %d methods only, "
                + "and one left off could score up to %.3f", r.ranked.size(), r.bound);
        if (best < MIN_SCORE) {
            r.unsure = r.bound > best;
            r.verdict = String.format(Locale.ROOT, "no candidate scores %.2f or more (the best scores %.3f)", MIN_SCORE, best)
                    + (r.unsure ? leftOff : "");
        } else if (best - next < MIN_MARGIN) {
            r.unsure = r.bound > best;
            r.verdict = String.format(Locale.ROOT, "no candidate stands out: the best two score %.3f and %.3f, less than %.2f apart",
                    best, next, MIN_MARGIN) + (r.unsure ? leftOff : "");
        } else if (r.bound > best - MIN_MARGIN) {
            r.unsure = true;
            r.verdict = String.format(Locale.ROOT, "no candidate stands out for certain: the best scores %.3f and the next %.3f",
                    best, next) + leftOff;
        } else {
            r.standsOut = true;
            r.verdict = r.bound > next
                    ? String.format(Locale.ROOT, "one candidate stands out: it scores %.3f, and no other more than %.3f", best, r.bound)
                    : String.format(Locale.ROOT, "one candidate stands out: it scores %.3f, and the next %.3f", best, next);
        }
    }

    static int rank(File signatureFile, File apk, File report, int top) throws Exception {
        Features f = fromJson(Json.parse(readText(signatureFile)), signatureFile);
        Indexed ix = index(new Build(apk));
        Ranking r = rankAll(ix, List.of(f)).get(0);
        StringBuilder out = new StringBuilder();
        header(out, "Candidates for " + f.descriptor, f.source, apk.getName());
        describe(out, r, ix, top, null);
        writeText(report, out.toString());
        System.out.println("[fingerprint] report written to " + report);
        if (!r.standsOut) {
            // No name here: a list on the console reads as an answer. The report has the closest
            // ones and their evidence, for somebody to read.
            System.out.println("[fingerprint] FAIL: " + r.verdict + ". This fails closed and suggests no candidate; "
                    + "the report lists the closest " + Math.min(top, r.ranked.size()) + " for review.");
            return 1;
        }
        for (int k = 0; k < Math.min(top, r.ranked.size()); k++) {
            Score s = r.ranked.get(k);
            System.out.println(String.format(Locale.ROOT, "[fingerprint]   #%d %.3f %s", k + 1, s.total,
                    descriptor(ix.build.methods.get(s.method))));
        }
        System.out.println("[fingerprint] " + r.verdict + ". Nothing was accepted: check "
                + descriptor(ix.build.methods.get(r.ranked.get(0).method))
                + " against the report, then change the fingerprint by hand.");
        return 0;
    }

    // ---- calibration -------------------------------------------------------------------------------

    static final class Case {
        String id;
        String patch = "";
        String role = "";
        String oldMethod;
        String newMethod;
        List<String> evidence = new ArrayList<>();
        int line;
    }

    static List<Case> readCalibration(File file) throws Exception {
        if (!file.isFile()) throw new InputException("No calibration file at " + file);
        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Case current = null;
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            String value = space < 0 ? "" : line.substring(space + 1).strip();
            if (key.equals("case")) {
                if (value.isEmpty() || value.contains(" ") || !ids.add(value)) {
                    throw new InputException("Calibration line " + lineNumber + ": a case needs a unique id with no spaces");
                }
                current = new Case();
                current.id = value;
                current.line = lineNumber;
                cases.add(current);
                continue;
            }
            if (current == null) throw new InputException("Calibration line " + lineNumber + ": " + key + " before any case");
            switch (key) {
                case "patch": current.patch = value; break;
                case "role": current.role = value; break;
                case "old": current.oldMethod = value; break;
                case "new": current.newMethod = value; break;
                case "evidence": current.evidence.add(value); break;
                default: throw new InputException("Calibration line " + lineNumber + ": unknown key " + key);
            }
        }
        for (Case c : cases) {
            if (c.oldMethod == null || c.newMethod == null || c.patch.isEmpty() || c.evidence.isEmpty()) {
                throw new InputException("Calibration case " + c.id + " (line " + c.line + ") needs patch, old, new and evidence");
            }
            if (!c.oldMethod.contains("->") || !c.newMethod.contains("->")) {
                throw new InputException("Calibration case " + c.id + ": old and new are method descriptors, as LX/7f5;->A0g(LX/7ej;I)J");
            }
        }
        if (cases.isEmpty()) throw new InputException("The calibration file " + file + " holds no case");
        return cases;
    }

    /** Whether the build has this method with code, before its methods are indexed. */
    static boolean hasBody(Build build, String descriptor) {
        int arrow = descriptor.indexOf("->");
        ClassDef cd = arrow < 0 ? null : build.classes.get(descriptor.substring(0, arrow));
        if (cd == null) return false;
        for (Method m : cd.getMethods()) if (m.getImplementation() != null && descriptor(m).equals(descriptor)) return true;
        return false;
    }

    static int calibrate(File calibration, File oldApk, File newApk, File report, int top) throws Exception {
        List<Case> cases = readCalibration(calibration);
        List<String> olds = new ArrayList<>();
        for (Case c : cases) if (!olds.contains(c.oldMethod)) olds.add(c.oldMethod);
        // A list names one build's methods: given another old build, say that rather than ask for a descriptor.
        Build old = new Build(oldApk);
        for (Case c : cases) {
            if (!hasBody(old, c.oldMethod)) {
                throw new InputException("Calibration case " + c.id + ": no method " + c.oldMethod + " with a body in "
                        + oldApk.getName() + ", so the calibration does not describe this old build");
            }
        }
        Map<String, Features> signatures = captureAll(old, olds);
        Indexed ix = index(new Build(newApk));
        for (Case c : cases) {
            if (!ix.build.indexOf.containsKey(c.newMethod)) {
                throw new InputException("Calibration case " + c.id + ": no method " + c.newMethod + " with a body in "
                        + newApk.getName() + ", so the calibration no longer describes this build");
            }
        }
        List<Features> list = new ArrayList<>();
        int[] knownIds = new int[cases.size()];
        for (int k = 0; k < cases.size(); k++) {
            list.add(signatures.get(cases.get(k).oldMethod));
            knownIds[k] = ix.build.indexOf.get(cases.get(k).newMethod);
        }
        List<Ranking> rankings = rankAll(ix, list, knownIds);

        StringBuilder out = new StringBuilder();
        header(out, "Calibration: " + cases.size() + " cases from " + calibration.getName(), oldApk.getName(), newApk.getName());
        int failures = 0;
        List<String> summary = new ArrayList<>();
        for (int k = 0; k < cases.size(); k++) {
            Case c = cases.get(k);
            Ranking r = rankings.get(k);
            int known = knownIds[k];
            int rank = -1;
            for (int j = 0; j < r.ranked.size(); j++) if (r.ranked.get(j).method == known) rank = j + 1;
            // A rank a method left off the shortlist could still push down proves nothing.
            boolean sure = !r.knownUnsure;
            boolean ok = rank >= 1 && rank <= top && sure;
            if (!ok) failures++;
            String rankText = rank < 0 ? "outside-" + r.ranked.size() : sure ? String.valueOf(rank) : rank + "-or-lower";
            double score = rank < 0 ? 0 : r.ranked.get(rank - 1).total;
            summary.add(String.format(Locale.ROOT, "[fingerprint] case %s rank %s score %.3f %s %s", c.id, rankText, score,
                    ok ? "ok" : "FAIL", r.standsOut ? "stands-out" : "fails-closed"));
            out.append("== case ").append(c.id).append(" (").append(c.patch).append(")\n");
            if (!c.role.isEmpty()) out.append("   ").append(c.role).append('\n');
            out.append("   old   ").append(c.oldMethod).append('\n');
            out.append("   known ").append(c.newMethod).append("   rank ").append(rankText)
                    .append(ok ? "" : sure ? "   <<< NOT IN THE TOP " + top : "   <<< NOT SURE TO BE IN THE TOP " + top)
                    .append('\n');
            for (String e : c.evidence) out.append("   evidence: ").append(e).append('\n');
            describe(out, r, ix, top, known);
        }
        out.append("Calibration: ").append(cases.size() - failures).append(" of ").append(cases.size())
                .append(" cases ranked the known replacement within the top ").append(top).append('\n');
        writeText(report, out.toString());
        for (String line : summary) System.out.println(line);
        System.out.println("[fingerprint] calibration: " + (cases.size() - failures) + " of " + cases.size()
                + " cases ranked the known replacement within the top " + top);
        System.out.println("[fingerprint] report written to " + report);
        if (failures != 0) {
            System.out.println("[fingerprint] FAIL: " + failures + " calibrated case" + (failures == 1 ? "" : "s")
                    + " did not rank the known replacement within the top " + top + " for certain");
            return 1;
        }
        return 0;
    }

    // ---- the report --------------------------------------------------------------------------------

    static void header(StringBuilder out, String title, String oldName, String newName) {
        out.append(title).append('\n');
        out.append("old build: ").append(oldName).append('\n');
        out.append("new build: ").append(newName).append('\n');
        out.append("This report ranks candidates and accepts none. A candidate is only a place to look: check it,\n");
        out.append("then change the fingerprint by hand so it finds the method on every declared build.\n\n");
    }

    static void describe(StringBuilder out, Ranking r, Indexed ix, int top, Integer known) {
        Features q = r.query.f;
        out.append("   signature: ").append(q.descriptor).append('\n');
        out.append("     prototype ").append(q.prototype).append(q.isStatic() ? " static" : "").append(", ")
                .append(q.instructions).append(" instructions, ").append(q.strings.size()).append(" strings, ")
                .append(q.literals.size()).append(" literals, ").append(q.references.size()).append(" references, ")
                .append(q.callerCount).append(" callers\n");
        out.append("     strings ").append(list(quoted(q.strings), 6)).append('\n');
        out.append("     literals ").append(list(q.literals, 6)).append('\n');
        out.append("     opcodes ").append(clip(q.sketch(), 80)).append('\n');
        out.append("   verdict: ").append(r.verdict).append(r.standsOut ? ". Nothing is accepted." : ". This fails closed.").append('\n');
        out.append("   callers compared for ").append(r.ranked.size()).append(r.bound < 0 ? " methods, every one with code"
                : String.format(Locale.ROOT, " methods; no other could score over %.3f", r.bound)).append("\n\n");
        List<Integer> shown = new ArrayList<>();
        for (int k = 0; k < Math.min(top, r.ranked.size()); k++) shown.add(k);
        if (known != null) {
            for (int k = top; k < r.ranked.size(); k++) if (r.ranked.get(k).method == known) shown.add(k);
        }
        for (int k : shown) {
            Score s = r.ranked.get(k);
            Features c = extract(ix.build.methods.get(s.method), ix.build.methodClass.get(s.method), List.of());
            c.context = tokensOf(ix.dict, ix.context[s.method]);
            CallerInfo callers = ix.callers.get(s.method);
            out.append(String.format(Locale.ROOT, "   #%d  %.3f  %s%s%n", k + 1, s.total, c.descriptor,
                    known != null && s.method == known ? "   <- the known replacement" : ""));
            out.append(String.format(Locale.ROOT, "       scores: tokens %.2f, opcodes %.2f, prototype %.2f, size %.2f, class %.2f%s%n",
                    s.tokens, s.opcodes, s.prototype, s.size, s.context,
                    s.callers >= 0 ? String.format(Locale.ROOT, ", callers %.2f", s.callers) : ""));
            out.append("       prototype: ").append(c.prototype)
                    .append(c.prototype.equals(q.prototype) ? " (the same)" : " (was " + q.prototype + ")")
                    .append(c.isStatic() != q.isStatic() ? (c.isStatic() ? ", now static" : ", no longer static") : "").append('\n');
            diff(out, "strings", quoted(q.strings), quoted(c.strings));
            diff(out, "literals", q.literals, c.literals);
            out.append("       opcodes: old ").append(q.instructions).append(' ').append(clip(q.sketch(), 60)).append('\n');
            out.append("                new ").append(c.instructions).append(' ').append(clip(c.sketch(), 60)).append('\n');
            diff(out, "references", keptOnly(q.references), keptOnly(c.references));
            List<String> oldObfuscated = obfuscatedOnly(q.references), newObfuscated = obfuscatedOnly(c.references);
            out.append("       obfuscated references: ").append(shared(oldObfuscated, newObfuscated)).append(" shared of ")
                    .append(oldObfuscated.size()).append(" old and ").append(newObfuscated.size()).append(" new\n");
            diff(out, "class", q.context, c.context);
            out.append("       callers: old ").append(q.callerCount).append(", new ").append(callers == null ? 0 : callers.count).append('\n');
            diff(out, "call neighbourhood", q.callerTokens, callers == null ? List.of() : callers.tokens);
        }
        out.append('\n');
    }

    static List<String> tokensOf(Dictionary dict, int[] ids) {
        List<String> out = new ArrayList<>();
        for (int id : ids) out.add(dict.names.get(id));
        return out;
    }

    static List<String> keptOnly(Collection<String> references) {
        List<String> out = new ArrayList<>();
        for (String r : references) if (!r.startsWith("M:X->") && !r.startsWith("F:X->")) out.add(r);
        return out;
    }

    static List<String> obfuscatedOnly(Collection<String> references) {
        List<String> out = new ArrayList<>();
        for (String r : references) if (r.startsWith("M:X->") || r.startsWith("F:X->")) out.add(r);
        return out;
    }

    static int shared(Collection<String> a, Collection<String> b) {
        Set<String> s = new HashSet<>(a);
        s.retainAll(new HashSet<>(b));
        return s.size();
    }

    static void diff(StringBuilder out, String label, Collection<String> old, Collection<String> now) {
        Set<String> o = new LinkedHashSet<>(old), n = new LinkedHashSet<>(now);
        List<String> both = new ArrayList<>(), onlyOld = new ArrayList<>(), onlyNew = new ArrayList<>();
        for (String s : o) (n.contains(s) ? both : onlyOld).add(s);
        for (String s : n) if (!o.contains(s)) onlyNew.add(s);
        out.append("       ").append(label).append(": ").append(both.size()).append(" shared ").append(list(both, 4))
                .append("; only old ").append(list(onlyOld, 4)).append("; only new ").append(list(onlyNew, 4)).append('\n');
    }

    static List<String> quoted(Collection<String> strings) {
        List<String> out = new ArrayList<>();
        for (String s : strings) out.add("\"" + printable(s) + "\"");
        return out;
    }

    static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < s.length() && b.length() < 90; k++) {
            char c = s.charAt(k);
            if (c >= 0x20 && c < 0x7f) b.append(c);
            else b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        }
        if (b.length() >= 90) b.append("...");
        return b.toString();
    }

    static String list(Collection<String> items, int max) {
        if (items.isEmpty()) return "-";
        StringBuilder b = new StringBuilder("[");
        int k = 0;
        for (String s : items) {
            if (k == max) {
                b.append(", +").append(items.size() - max).append(" more");
                break;
            }
            if (k++ > 0) b.append(", ");
            b.append(printable(s));
        }
        return b.append(']').toString();
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- signature files ---------------------------------------------------------------------------

    static Map<String, Object> toJson(Features f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("version", SCHEMA_VERSION);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("apk", f.source);
        source.put("sha256", f.sourceSha256);
        m.put("source", source);
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("descriptor", f.descriptor);
        method.put("definingClass", f.definingClass);
        method.put("name", f.name);
        method.put("classKept", f.classKept);
        method.put("nameKept", f.nameKept);
        method.put("access", f.access);
        method.put("prototype", f.prototype);
        method.put("returnType", f.returnType);
        method.put("parameters", new ArrayList<>(f.parameters));
        method.put("instructions", f.instructions);
        method.put("registers", f.registers);
        m.put("method", method);
        m.put("strings", new ArrayList<>(f.strings));
        m.put("literals", new ArrayList<>(f.literals));
        m.put("references", new ArrayList<>(f.references));
        m.put("context", new ArrayList<>(f.context));
        m.put("opcodes", new ArrayList<>(f.opcodes));
        Map<String, Object> callers = new LinkedHashMap<>();
        callers.put("count", f.callerCount);
        callers.put("tokens", new ArrayList<>(f.callerTokens));
        m.put("callers", callers);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Features fromJson(Object json, File file) throws InputException {
        try {
            Map<String, Object> m = (Map<String, Object>) json;
            if (!SCHEMA.equals(m.get("schema"))) throw new InputException(file + " is not a " + SCHEMA + " file");
            if (((Number) m.get("version")).intValue() != SCHEMA_VERSION) {
                throw new InputException(file + " is signature version " + m.get("version") + ", and this tool reads " + SCHEMA_VERSION);
            }
            Features f = new Features();
            Map<String, Object> source = (Map<String, Object>) m.get("source");
            f.source = (String) source.get("apk");
            f.sourceSha256 = (String) source.get("sha256");
            Map<String, Object> method = (Map<String, Object>) m.get("method");
            f.descriptor = (String) method.get("descriptor");
            f.definingClass = (String) method.get("definingClass");
            f.name = (String) method.get("name");
            f.classKept = (Boolean) method.get("classKept");
            f.nameKept = (Boolean) method.get("nameKept");
            f.access = ((Number) method.get("access")).intValue();
            f.prototype = (String) method.get("prototype");
            f.returnType = (String) method.get("returnType");
            for (Object p : (List<Object>) method.get("parameters")) f.parameters.add((String) p);
            f.instructions = ((Number) method.get("instructions")).intValue();
            f.registers = ((Number) method.get("registers")).intValue();
            for (Object s : (List<Object>) m.get("strings")) f.strings.add((String) s);
            for (Object s : (List<Object>) m.get("literals")) f.literals.add((String) s);
            for (Object s : (List<Object>) m.get("references")) f.references.add((String) s);
            for (Object s : (List<Object>) m.get("context")) f.context.add((String) s);
            for (Object s : (List<Object>) m.get("opcodes")) f.opcodes.add((String) s);
            Map<String, Object> callers = (Map<String, Object>) m.get("callers");
            f.callerCount = ((Number) callers.get("count")).intValue();
            for (Object s : (List<Object>) callers.get("tokens")) f.callerTokens.add((String) s);
            if (f.descriptor == null || f.prototype == null || f.name == null || f.returnType == null) throw new NullPointerException();
            return f;
        } catch (ClassCastException | NullPointerException e) {
            throw new InputException(file + " does not hold a signature of the shape " + SCHEMA + " version " + SCHEMA_VERSION + " describes");
        }
    }

    /** A small JSON reader and writer: the signature is the only JSON this tool reads. */
    static final class Json {
        final String text;
        int at;

        Json(String text) {
            this.text = text;
        }

        static Object parse(String text) throws InputException {
            Json j = new Json(text);
            try {
                Object value = j.value();
                j.space();
                if (j.at != text.length()) throw new InputException("The signature has more after its JSON value, at offset " + j.at);
                return value;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                throw new InputException("The signature is not valid JSON near offset " + j.at);
            }
        }

        void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        Object value() throws InputException {
            space();
            char c = text.charAt(at);
            if (c == '{') {
                at++;
                Map<String, Object> m = new LinkedHashMap<>();
                space();
                if (text.charAt(at) == '}') {
                    at++;
                    return m;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    m.put(key, value());
                    space();
                    if (text.charAt(at) == ',') {
                        at++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                at++;
                List<Object> l = new ArrayList<>();
                space();
                if (text.charAt(at) == ']') {
                    at++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    space();
                    if (text.charAt(at) == ',') {
                        at++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            if (text.startsWith("true", at)) {
                at += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            if (start == at) throw new InputException("Unexpected character in the signature at offset " + at);
            String number = text.substring(start, at);
            return number.contains(".") || number.contains("e") || number.contains("E")
                    ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
        }

        void expect(char c) throws InputException {
            if (text.charAt(at) != c) throw new InputException("Expected '" + c + "' in the signature at offset " + at);
            at++;
        }

        String string() throws InputException {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = text.charAt(at++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default: throw new InputException("Bad escape in the signature at offset " + at);
                }
            }
        }

        /** Writes ASCII only: anything else is escaped, so a signature survives any file encoding. */
        static String write(Object value) {
            StringBuilder b = new StringBuilder();
            write(value, b, "");
            return b.append('\n').toString();
        }

        @SuppressWarnings("unchecked")
        static void write(Object value, StringBuilder b, String indent) {
            if (value instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) value;
                b.append("{\n");
                int k = 0;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    b.append(indent).append("  ");
                    quote(e.getKey(), b);
                    b.append(": ");
                    write(e.getValue(), b, indent + "  ");
                    if (++k < m.size()) b.append(',');
                    b.append('\n');
                }
                b.append(indent).append('}');
            } else if (value instanceof List) {
                List<Object> l = (List<Object>) value;
                if (l.isEmpty()) {
                    b.append("[]");
                    return;
                }
                b.append("[\n");
                for (int k = 0; k < l.size(); k++) {
                    b.append(indent).append("  ");
                    write(l.get(k), b, indent + "  ");
                    if (k + 1 < l.size()) b.append(',');
                    b.append('\n');
                }
                b.append(indent).append(']');
            } else if (value instanceof String) {
                quote((String) value, b);
            } else {
                b.append(value);
            }
        }

        static void quote(String s, StringBuilder b) {
            b.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c >= 0x20 && c < 0x7f) b.append(c);
                else b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            }
            b.append('"');
        }
    }

    // ---- files -------------------------------------------------------------------------------------

    static String readText(File file) throws Exception {
        if (!file.isFile()) throw new InputException("No file at " + file);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        // A new file moved over the name, never a write through it: the name can be a hard link to a
        // file anywhere, patch source included, and writing through it would change that file.
        java.nio.file.Path target = file.toPath().toAbsolutePath();
        java.nio.file.Path part = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".part");
        try {
            Files.write(part, text.getBytes(StandardCharsets.UTF_8));
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}
