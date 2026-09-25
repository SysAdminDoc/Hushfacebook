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
import java.util.Map;

/**
 * Writes the dex files scripts/test-fingerprint-candidates.ps1 holds FingerprintCandidates.java to,
 * each a build with Redex-style names: an old build with one target method and a caller, and three
 * new builds. In "moved" the target is renamed into another class beside two decoys, so one
 * candidate stands out. In "twins" it is there twice, identical, so none can stand out and the run
 * has to fail closed. In "gone" it is not there at all, and nothing scores enough.
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
        List<ImmutableMethodParameter> list = new ArrayList<>();
        for (String p : parameters) list.add(new ImmutableMethodParameter(p, null, null));
        return new ImmutableMethod(owner, name, list, returns, AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(),
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

        for (Map.Entry<String, List<ClassDef>> e : dexes.entrySet()) {
            DexPool.writeTo(new File(out, e.getKey() + ".dex").getPath(), new ImmutableDexFile(Opcodes.forApi(30), e.getValue()));
        }
        System.out.println("[fixture] wrote " + dexes.size() + " dex files to " + out.getPath());
    }
}
