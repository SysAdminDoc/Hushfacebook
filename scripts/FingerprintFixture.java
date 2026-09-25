/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the dex files scripts/test-fingerprint-candidates.ps1 holds FingerprintCandidates.java to,
 * each a build with Redex-style names: an old build with one target method and a caller, and three
 * new builds. In "moved" the target is renamed into another class beside two decoys, so one
 * candidate stands out. In "twins" it is there twice, identical, so none can stand out and the run
 * has to fail closed. In "gone" it is not there at all, and nothing scores enough.
 *
 * <p>"getter" is a second old build, whose target is a two-instruction getter that only its caller
 * tells apart, and four new builds bury it among 250 getters just like it. In "crowd" the real one is
 * the last of 251 that tie before callers count, and a getter near the front of the dex has a caller
 * sharing one of the old caller's two markers: the real one ranks first, but not far enough ahead to
 * stand out. In "crowd-behind" the real one became an instance method, so before callers count it
 * scores a little under all 250, and with them it stands out. In "crowd-rival" an exact copy of the
 * old getter and its caller stands out above the crowd, until that instance method, with a caller
 * just as good, comes within the margin of it. In "crowd-star" the copy stands out for good, above
 * five instance getters answering a boolean, each with a caller like the old one's, that score under
 * the crowd until their callers count and above it after: a calibration naming a getter of the crowd
 * as the known method must not rank it second.
 *
 *   java -cp &lt;cli jar&gt; FingerprintFixture.java &lt;outDir&gt;
 */
public class FingerprintFixture {

    static final String OBJECT = "Ljava/lang/Object;";
    static final String STRING = "Ljava/lang/String;";

    static final ImmutableMethodReference LOG_D = new ImmutableMethodReference("Landroid/util/Log;", "d",
            Arrays.asList(STRING, STRING), "I");
    static final ImmutableMethodReference INT_TO_STRING = new ImmutableMethodReference("Ljava/lang/Integer;", "toString",
            Collections.singletonList("I"), STRING);

    static Instruction op(Opcode opcode) {
        return new ImmutableInstruction10x(opcode);
    }

    static Instruction invoke(ImmutableMethodReference callee, int... registers) {
        int[] r = Arrays.copyOf(registers, 5);
        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, registers.length, r[0], r[1], r[2], r[3], r[4], callee);
    }

    static Instruction string(int register, String value) {
        return new ImmutableInstruction21c(Opcode.CONST_STRING, register, new ImmutableStringReference(value));
    }

    static Method method(String owner, String name, String returns, List<String> parameters, int registers, Instruction... body) {
        return method(AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), owner, name, returns, parameters,
                registers, body);
    }

    static Method method(int access, String owner, String name, String returns, List<String> parameters, int registers,
            Instruction... body) {
        List<ImmutableMethodParameter> list = new ArrayList<>();
        for (String p : parameters) list.add(new ImmutableMethodParameter(p, null, null));
        return new ImmutableMethod(owner, name, list, returns, access,
                null, null, new ImmutableMethodImplementation(registers, Arrays.asList(body), null, null));
    }

    static ClassDef type(String name, Method... methods) {
        return new ImmutableClassDef(name, AccessFlags.PUBLIC.getValue(), OBJECT, null, null, null, null, Arrays.asList(methods));
    }

    /**
     * The target: it logs a marker, adds a literal to its int and answers the sum as a string.
     * v0 and v1 are locals, the arguments sit in v2 and v3.
     */
    static Method target(String owner, String name) {
        return method(owner, name, STRING, Arrays.asList(STRING, "I"), 4,
                string(0, "fingerprint_fixture_marker"),
                invoke(LOG_D, 0, 2),
                new ImmutableInstruction31i(Opcode.CONST, 1, 0x12345),
                new ImmutableInstruction12x(Opcode.ADD_INT_2ADDR, 1, 3),
                invoke(INT_TO_STRING, 1),
                new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
    }

    /** A caller of the target, with a marker of its own. */
    static Method caller(String owner, String name, String... targets) {
        List<Instruction> body = new ArrayList<>();
        body.add(string(0, "fingerprint_fixture_caller"));
        body.add(new ImmutableInstruction31i(Opcode.CONST, 1, 7));
        for (String t : targets) {
            String[] parts = t.split("->");
            body.add(invoke(new ImmutableMethodReference(parts[0], parts[1], Arrays.asList(STRING, "I"), STRING), 0, 1));
        }
        body.add(op(Opcode.RETURN_VOID));
        return method(owner, name, "V", Collections.emptyList(), 2, body.toArray(new Instruction[0]));
    }

    static final String[] MARKERS = {"caller_marker_one", "caller_marker_two"};

    /** A getter answering 1 (an int, or true), static or not: the shape thousands of Facebook's methods have. */
    static Method getter(String owner, String returns, boolean isStatic) {
        int access = AccessFlags.PUBLIC.getValue() | (isStatic ? AccessFlags.STATIC.getValue() : 0);
        return method(access, owner, "A00", returns, Collections.emptyList(), isStatic ? 1 : 2,
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                new ImmutableInstruction11x(Opcode.RETURN, 0));
    }

    /** A caller of a getter, holding the markers given. An instance getter is called on null. */
    static Method getterCaller(String owner, String getter, String returns, boolean isStatic, String... markers) {
        List<Instruction> body = new ArrayList<>();
        for (String m : markers) body.add(string(0, m));
        ImmutableMethodReference callee = new ImmutableMethodReference(getter, "A00", Collections.emptyList(), returns);
        if (isStatic) {
            body.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, callee));
        } else {
            body.add(new ImmutableInstruction11n(Opcode.CONST_4, 1, 0));
            body.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, callee));
        }
        body.add(op(Opcode.RETURN_VOID));
        return method(owner, "A00", "V", Collections.emptyList(), 2, body.toArray(new Instruction[0]));
    }

    /** 250 getters just like the old target, in classes that sort before the real one's. */
    static List<ClassDef> getterCrowd() {
        List<ClassDef> out = new ArrayList<>();
        for (int k = 0; k < 250; k++) {
            String owner = String.format(Locale.ROOT, "LX/Da%03d;", k);
            out.add(type(owner, getter(owner, "I", true)));
        }
        return out;
    }

    /** Methods that share nothing but a prototype with the target, or nothing at all. */
    static List<ClassDef> decoys() {
        return Arrays.asList(
                type("LX/Dc1;", method("LX/Dc1;", "A00", STRING, Arrays.asList(STRING, "I"), 2,
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                type("LX/Dc2;", method("LX/Dc2;", "A01", "V", Collections.singletonList(STRING), 2,
                        string(0, "fingerprint_fixture_decoy"),
                        invoke(LOG_D, 0, 1),
                        op(Opcode.RETURN_VOID))),
                type("LX/Dc3;", method("LX/Dc3;", "A02", "I", Collections.singletonList("I"), 1,
                        new ImmutableInstruction11x(Opcode.RETURN, 0))));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: FingerprintFixture <outDir>");
            System.exit(2);
        }
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("Cannot create " + out);

        Map<String, List<ClassDef>> dexes = new LinkedHashMap<>();
        dexes.put("old", Arrays.asList(
                type("LX/Ab1;", target("LX/Ab1;", "A0q")),
                type("LX/Ab2;", caller("LX/Ab2;", "A00", "LX/Ab1;->A0q"))));

        List<ClassDef> moved = new ArrayList<>(decoys());
        moved.add(type("LX/Zz9;", target("LX/Zz9;", "B1c")));
        moved.add(type("LX/Zz8;", caller("LX/Zz8;", "A00", "LX/Zz9;->B1c")));
        dexes.put("moved", moved);

        List<ClassDef> twins = new ArrayList<>(decoys());
        twins.add(type("LX/Zz9;", target("LX/Zz9;", "B1c")));
        twins.add(type("LX/Zz7;", target("LX/Zz7;", "B1c")));
        twins.add(type("LX/Zz8;", caller("LX/Zz8;", "A00", "LX/Zz9;->B1c", "LX/Zz7;->B1c")));
        dexes.put("twins", twins);

        dexes.put("gone", new ArrayList<>(decoys()));

        dexes.put("getter", Arrays.asList(
                type("LX/Ab1;", getter("LX/Ab1;", "I", true)),
                type("LX/Ab2;", getterCaller("LX/Ab2;", "LX/Ab1;", "I", true, MARKERS))));

        List<ClassDef> crowd = new ArrayList<>(getterCrowd());
        crowd.add(type("LX/Cc1;", getterCaller("LX/Cc1;", "LX/Da000;", "I", true, MARKERS[0])));
        crowd.add(type("LX/Zz9;", getter("LX/Zz9;", "I", true)));
        crowd.add(type("LX/Zz8;", getterCaller("LX/Zz8;", "LX/Zz9;", "I", true, MARKERS)));
        dexes.put("crowd", crowd);

        List<ClassDef> behind = new ArrayList<>(getterCrowd());
        behind.add(type("LX/Zz9;", getter("LX/Zz9;", "I", false)));
        behind.add(type("LX/Zz8;", getterCaller("LX/Zz8;", "LX/Zz9;", "I", false, MARKERS)));
        dexes.put("crowd-behind", behind);

        List<ClassDef> rival = new ArrayList<>(getterCrowd());
        rival.add(type("LX/St1;", getter("LX/St1;", "I", true)));
        rival.add(type("LX/St2;", getterCaller("LX/St2;", "LX/St1;", "I", true, MARKERS)));
        rival.add(type("LX/Zz9;", getter("LX/Zz9;", "I", false)));
        rival.add(type("LX/Zz8;", getterCaller("LX/Zz8;", "LX/Zz9;", "I", false, MARKERS)));
        dexes.put("crowd-rival", rival);

        List<ClassDef> star = new ArrayList<>(getterCrowd());
        star.add(type("LX/St1;", getter("LX/St1;", "I", true)));
        star.add(type("LX/St2;", getterCaller("LX/St2;", "LX/St1;", "I", true, MARKERS)));
        for (int k = 5; k <= 9; k++) {
            star.add(type("LX/Zz" + k + ";", getter("LX/Zz" + k + ";", "Z", false)));
            star.add(type("LX/Zy" + k + ";", getterCaller("LX/Zy" + k + ";", "LX/Zz" + k + ";", "Z", false, MARKERS)));
        }
        dexes.put("crowd-star", star);

        for (Map.Entry<String, List<ClassDef>> e : dexes.entrySet()) {
            DexPool.writeTo(new File(out, e.getKey() + ".dex").getPath(), new ImmutableDexFile(Opcodes.forApi(30), e.getValue()));
        }
        System.out.println("[fixture] wrote " + dexes.size() + " dex files to " + out.getPath());
    }
}
