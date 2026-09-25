import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableArrayPayload;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSparseSwitchPayload;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the dex files scripts/test-injected-registers.ps1 holds DexDiff.java to: a clean host,
 * a patched build of it that passes every check, and one build per check that breaks exactly that
 * check and nothing else. javac and d8 can only write valid code, so these are built instruction
 * by instruction.
 *
 * <p>The host stands in for Facebook's feed collection: {@code addNewEdgeToCollection} is where
 * the one feed guard goes, and the bundle's {@code FeedFilter.hideEdge} is the guard, under the
 * same names the contract file holds the real APK to.
 *
 *   java -cp &lt;cli jar&gt; BadDexFixture.java &lt;outDir&gt;
 */
public class BadDexFixture {

    private static final String HOST = "Lfixture/Feed;";
    private static final String FILTER = "Lapp/morphe/extension/facebook/feed/FeedFilter;";
    private static final String OBJECT = "Ljava/lang/Object;";

    private static final ImmutableMethodReference HIDE_EDGE =
            method(FILTER, "hideEdge", "Z", OBJECT, OBJECT);
    private static final ImmutableMethodReference INSPECT =
            method(FILTER, "inspect", "V", OBJECT, OBJECT);
    private static final ImmutableMethodReference WIDE = method(FILTER, "wide", "V", "J");
    private static final ImmutableMethodReference RISKY = method(HOST, "risky", "I");

    private static final ImmutableTypeReference STRING_TYPE = new ImmutableTypeReference("Ljava/lang/String;");
    private static final ImmutableTypeReference INT_ARRAY = new ImmutableTypeReference("[I");
    private static final ImmutableTypeReference OBJECT_ARRAY = new ImmutableTypeReference("[Ljava/lang/Object;");

    private static ImmutableMethodReference method(String owner, String name, String returns, String... parameters) {
        return new ImmutableMethodReference(owner, name, Arrays.asList(parameters), returns);
    }

    private static Instruction op(Opcode opcode) {
        return new ImmutableInstruction10x(opcode);
    }

    private static Instruction op(Opcode opcode, int register) {
        return new ImmutableInstruction11x(opcode, register);
    }

    private static Instruction invoke(ImmutableMethodReference callee, int... registers) {
        int[] r = Arrays.copyOf(registers, 5);
        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, registers.length, r[0], r[1], r[2], r[3], r[4], callee);
    }

    private static Instruction ifEqz(int register, int offset) {
        return new ImmutableInstruction21t(Opcode.IF_EQZ, register, offset);
    }

    private static Instruction filledNewArray(ImmutableTypeReference type, int... registers) {
        int[] r = Arrays.copyOf(registers, 5);
        return new ImmutableInstruction35c(Opcode.FILLED_NEW_ARRAY, registers.length, r[0], r[1], r[2], r[3], r[4], type);
    }

    /** A fill-array-data payload of one int. */
    private static Instruction oneInt() {
        return new ImmutableArrayPayload(4, Collections.<Number>singletonList(1));
    }

    private static ImmutableMethodImplementation body(int registers, Instruction... instructions) {
        return new ImmutableMethodImplementation(registers, Arrays.asList(instructions), null, null);
    }

    private static ImmutableMethodImplementation body(int registers, List<ImmutableTryBlock> tries, Instruction... instructions) {
        return new ImmutableMethodImplementation(registers, Arrays.asList(instructions), tries, null);
    }

    private static ImmutableTryBlock tryBlock(int start, int units, int handler) {
        return new ImmutableTryBlock(start, units,
                Collections.singletonList(new ImmutableExceptionHandler("Ljava/lang/Exception;", handler)));
    }

    private static Method define(String owner, String name, String returns, boolean isStatic,
            ImmutableMethodImplementation implementation, String... parameters) {
        List<ImmutableMethodParameter> list = new ArrayList<>();
        for (String p : parameters) list.add(new ImmutableMethodParameter(p, null, null));
        int flags = AccessFlags.PUBLIC.getValue() | (isStatic ? AccessFlags.STATIC.getValue() : 0);
        return new ImmutableMethod(owner, name, list, returns, flags, null, null, implementation);
    }

    // The host's methods, clean.

    /** Where the one feed guard goes. Instance method: v0 this, v1 and v2 the arguments. */
    private static Method feedEdge(ImmutableMethodImplementation implementation) {
        return define(HOST, "addNewEdgeToCollection", "V", false, implementation, OBJECT, OBJECT);
    }

    private static final ImmutableMethodImplementation CLEAN_FEED_EDGE = body(3, op(Opcode.RETURN_VOID));

    /** Static, an object then a long: v0 the object, v1 and v2 the long. */
    private static Method staticHost(ImmutableMethodImplementation implementation) {
        return define(HOST, "staticHost", "V", true, implementation, OBJECT, "J");
    }

    private static final ImmutableMethodImplementation CLEAN_STATIC_HOST = body(3, op(Opcode.RETURN_VOID));

    /** A packed switch over the argument, v1. Case 0 lands at 5 and case 1 at 7. */
    private static Method switchHost(int caseOneOffset) {
        return switchHost(caseOneOffset, op(Opcode.RETURN, 0));
    }

    /** The same, with [atFour] as the one-unit instruction that ends the no-case path at 4. */
    private static Method switchHost(int caseOneOffset, Instruction atFour) {
        return define(HOST, "switchHost", "I", true, body(2,
                new ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 10), // 0
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),        // 3
                atFour,                                                    // 4
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),        // 5
                op(Opcode.RETURN, 0),                                      // 6
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 2),        // 7
                op(Opcode.RETURN, 0),                                      // 8
                op(Opcode.NOP),                                            // 9, aligns the payload
                new ImmutablePackedSwitchPayload(Arrays.asList(            // 10
                        new ImmutableSwitchElement(0, 5),
                        new ImmutableSwitchElement(1, caseOneOffset)))), "I");
    }

    /** risky() inside a try whose handler is a move-exception at 5. */
    private static Method tryHost(ImmutableTryBlock block) {
        return tryHost(block, op(Opcode.RETURN_VOID));
    }

    /** The same, with [beforeHandler] as the one-unit instruction that ends the try path at 4. */
    private static Method tryHost(ImmutableTryBlock block, Instruction beforeHandler) {
        return define(HOST, "tryHost", "V", true, body(1, Collections.singletonList(block),
                invoke(RISKY),                      // 0
                op(Opcode.MOVE_RESULT, 0),          // 3
                beforeHandler,                      // 4
                op(Opcode.MOVE_EXCEPTION, 0),       // 5
                op(Opcode.RETURN_VOID)));           // 6
    }

    private static final ImmutableTryBlock CLEAN_TRY = tryBlock(0, 3, 5);

    /** switchHost with case 1 sent to the move-result of an invoke on the no-case path. */
    private static Method switchToResult() {
        return define(HOST, "switchHost", "I", true, body(2,
                new ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 10), // 0
                invoke(RISKY),                                             // 3
                op(Opcode.MOVE_RESULT, 0),                                 // 6
                op(Opcode.RETURN, 0),                                      // 7
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),        // 8
                op(Opcode.RETURN, 0),                                      // 9
                new ImmutablePackedSwitchPayload(Arrays.asList(            // 10
                        new ImmutableSwitchElement(0, 8),
                        new ImmutableSwitchElement(1, 6)))), "I");
    }

    /** tryHost whose handler is the packed-switch payload at 10 instead of a move-exception. */
    private static Method tryHandlerAtPayload() {
        return define(HOST, "tryHost", "V", true, body(1, Collections.singletonList(tryBlock(0, 3, 10)),
                invoke(RISKY),                                             // 0
                op(Opcode.MOVE_RESULT, 0),                                 // 3
                new ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 6),  // 4 -> 10
                op(Opcode.RETURN_VOID),                                    // 7
                op(Opcode.RETURN_VOID),                                    // 8, case 0
                op(Opcode.NOP),                                            // 9, aligns the payload
                new ImmutablePackedSwitchPayload(Collections.singletonList( // 10
                        new ImmutableSwitchElement(0, 4)))));
    }

    /**
     * switchHost filling an int array, whose fill-array-data payload sits at 10, with case 0 sent
     * to that payload instead of an instruction.
     */
    private static Method switchToArrayPayload() {
        return define(HOST, "switchHost", "I", true, body(2,
                new ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 16),         // 0 -> 16
                new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 1, INT_ARRAY),    // 3
                new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 5),         // 5 -> 10
                op(Opcode.RETURN, 1),                                              // 8
                op(Opcode.NOP),                                                    // 9, aligns the payloads
                oneInt(),                                                          // 10
                new ImmutablePackedSwitchPayload(Collections.singletonList(        // 16
                        new ImmutableSwitchElement(0, 10)))), "I");
    }

    /** switchHost as a sparse switch, its one case sent to its own payload at 4. */
    private static Method sparseSwitchToOwnPayload() {
        return define(HOST, "switchHost", "I", true, body(2,
                new ImmutableInstruction31t(Opcode.SPARSE_SWITCH, 1, 4),           // 0 -> 4
                op(Opcode.RETURN, 1),                                              // 3
                new ImmutableSparseSwitchPayload(Collections.singletonList(        // 4
                        new ImmutableSwitchElement(7, 4)))), "I");
    }

    /** tryHost filling an int array, with the handler at its fill-array-data payload at 10. */
    private static Method tryHandlerAtArrayPayload() {
        return define(HOST, "tryHost", "V", true, body(1, Collections.singletonList(tryBlock(0, 3, 10)),
                invoke(RISKY),                                                     // 0
                op(Opcode.MOVE_RESULT, 0),                                         // 3
                new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 0, INT_ARRAY),    // 4
                new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 4),         // 6 -> 10
                op(Opcode.RETURN_VOID),                                            // 9
                oneInt()));                                                        // 10
    }

    /** tryHost with its handler moved to the top: the move-exception is the method's first instruction. */
    private static Method moveExceptionAtEntry() {
        return define(HOST, "tryHost", "V", true, body(1, Collections.singletonList(tryBlock(1, 3, 0)),
                op(Opcode.MOVE_EXCEPTION, 0),                              // 0
                invoke(RISKY),                                             // 1
                op(Opcode.MOVE_RESULT, 0),                                 // 4
                op(Opcode.RETURN_VOID)));                                  // 5
    }

    private static Method risky() {
        return define(HOST, "risky", "I", true, body(1,
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), op(Opcode.RETURN, 0)));
    }

    private static Method removable() {
        return define(HOST, "removable", "V", true, body(0, op(Opcode.RETURN_VOID)));
    }

    // The patched shapes.

    /** The guard as the bundle writes it: v0 free, v1 this, v2 and v3 the arguments. */
    private static final ImmutableMethodImplementation GUARDED_FEED_EDGE = body(4,
            invoke(HIDE_EDGE, 2, 3),   // 0
            op(Opcode.MOVE_RESULT, 0), // 3
            ifEqz(0, 3),               // 4 -> 7
            op(Opcode.RETURN_VOID),    // 6
            op(Opcode.RETURN_VOID));   // 7

    /** The object twice, then the long as a pair: every register the kind the callee takes. */
    private static final ImmutableMethodImplementation GOOD_STATIC_HOST = body(3,
            invoke(INSPECT, 0, 0),
            invoke(WIDE, 1, 2),
            op(Opcode.RETURN_VOID));

    /** A static long, where Compose-style code keeps a packed colour. */
    private static List<ImmutableField> packedField(String owner) {
        return Collections.singletonList(new ImmutableField(owner, "packed", "J",
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), null, null, null));
    }

    /**
     * A colour packed as Compose packs one: the int widened to a long, shifted up 32, stored.
     * On 580 the colour came in as a const-wide/32 and the AMOLED sweep left a narrow const.
     */
    private static Instruction[] packColor(Opcode constant, String owner) {
        return new Instruction[]{
                new ImmutableInstruction31i(constant, 0, BLACK),
                new ImmutableInstruction21s(Opcode.CONST_16, 2, 32),
                new ImmutableInstruction12x(Opcode.SHL_LONG_2ADDR, 0, 2),
                new ImmutableInstruction21c(Opcode.SPUT_WIDE, 0, new ImmutableFieldReference(owner, "packed", "J")),
                op(Opcode.RETURN_VOID)};
    }

    private static ClassDef host(Method feedEdge, Method staticHost, Method switchHost, Method tryHost, boolean keepRemovable) {
        List<Method> methods = new ArrayList<>(Arrays.asList(feedEdge, staticHost, switchHost, tryHost, risky()));
        if (keepRemovable) methods.add(removable());
        return new ImmutableClassDef(HOST, AccessFlags.PUBLIC.getValue(), OBJECT, null, null, null, packedField(HOST), methods);
    }

    private static ClassDef cleanHost() {
        return host(feedEdge(CLEAN_FEED_EDGE), staticHost(CLEAN_STATIC_HOST), switchHost(7), tryHost(CLEAN_TRY), true);
    }

    /** Opaque black as a colour int, the value the AMOLED sweep writes. */
    private static final int BLACK = -0x1000000;

    private static final ImmutableMethodReference REUSE = method(FILTER, "reuse", "V", "I");

    /**
     * The bundle's side. reuse(I) passes its int argument's register as an object only after a
     * path through a const-string wrote an object into it, and that write sits after the use in
     * the file: a check that walked the body top to bottom instead of along its branches would
     * call a valid method wrong. nulls, longs and ints are the valid widths: a zero constant
     * passed as an object, a const-wide/32 passed as a long, a const passed as an int. joins and
     * caught are where paths meet the way ART allows: a zero that is an object on one arm and a
     * zero that is an int on the other, a conflict that is only copied, and a handler that reads
     * what its register held before the instruction that threw. reads gives each instruction the
     * conflict builds fail a value of the kind ART takes there, so a read made stricter fails too.
     */
    private static ClassDef filter() {
        List<Method> methods = Arrays.asList(
                define(FILTER, "hideEdge", "Z", true, body(2,
                        new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), op(Opcode.RETURN, 0)), OBJECT, OBJECT),
                define(FILTER, "inspect", "V", true, body(2, op(Opcode.RETURN_VOID)), OBJECT, OBJECT),
                define(FILTER, "wide", "V", true, body(2, op(Opcode.RETURN_VOID)), "J"),
                define(FILTER, "reuse", "V", true, body(1,
                        new ImmutableInstruction10t(Opcode.GOTO, 5),                            // 0 -> 5
                        invoke(INSPECT, 0, 0),                                                   // 1
                        op(Opcode.RETURN_VOID),                                                  // 4
                        new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("edge")), // 5
                        new ImmutableInstruction10t(Opcode.GOTO, -6)), "I"),                    // 7 -> 1
                define(FILTER, "nulls", "V", true, body(1,
                        new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), invoke(INSPECT, 0, 0), op(Opcode.RETURN_VOID))),
                // The pair is broken after its last read, which is valid: only a read of it isn't.
                define(FILTER, "longs", "V", true, body(2,
                        new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK), invoke(WIDE, 0, 1),
                        new ImmutableInstruction11n(Opcode.CONST_4, 1, 0), invoke(REUSE, 1),
                        op(Opcode.RETURN_VOID))),
                define(FILTER, "ints", "V", true, body(1,
                        new ImmutableInstruction31i(Opcode.CONST, 0, BLACK), invoke(REUSE, 0), op(Opcode.RETURN_VOID))),
                define(FILTER, "packs", "V", true, body(3, packColor(Opcode.CONST_WIDE_32, FILTER))),
                // v0 is null on one arm and the object on the other, v1 zero or one: each is still
                // its kind where the arms meet. v2 is an object on one arm and an int on the
                // other, which only a use of it would fail. A move-object and a plain move only
                // copy it, and v3 is written again before anything reads the copy.
                define(FILTER, "joins", "V", true, body(5,
                        new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),                      // 0
                        new ImmutableInstruction11n(Opcode.CONST_4, 1, 0),                      // 1
                        new ImmutableInstruction21c(Opcode.CONST_STRING, 2, new ImmutableStringReference("edge")), // 2
                        ifEqz(4, 5),                                                             // 4 -> 9
                        new ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 4),                  // 6
                        new ImmutableInstruction11n(Opcode.CONST_4, 1, 1),                      // 7
                        new ImmutableInstruction11n(Opcode.CONST_4, 2, 1),                      // 8
                        invoke(INSPECT, 0, 0),                                                   // 9
                        invoke(REUSE, 1),                                                        // 12
                        new ImmutableInstruction12x(Opcode.MOVE_OBJECT, 3, 2),                  // 15
                        new ImmutableInstruction12x(Opcode.MOVE, 3, 2),                         // 16
                        new ImmutableInstruction11n(Opcode.CONST_4, 3, 0),                      // 17
                        op(Opcode.RETURN_VOID)), OBJECT),                                        // 18
                // The const-string can throw, and its handler reads v0 as the int it held before
                // the const-string wrote an object there, which is all ART's handler sees.
                define(FILTER, "caught", "V", true, body(2, Collections.singletonList(tryBlock(1, 2, 7)),
                        new ImmutableInstruction12x(Opcode.MOVE, 0, 1),                         // 0
                        new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("edge")), // 1
                        invoke(INSPECT, 0, 0),                                                   // 3
                        op(Opcode.RETURN_VOID),                                                  // 6
                        op(Opcode.MOVE_EXCEPTION, 1),                                            // 7
                        invoke(REUSE, 0),                                                        // 8
                        op(Opcode.RETURN_VOID)), "I"),                                           // 11
                // v4 the object, v5 the int: objects and ints tested for equality, an int ordered,
                // tested against zero and switched on, an object locked, cast and tested, an int
                // as a new array's size, the array measured and filled, ints and an object as a
                // new array's elements, and a null thrown.
                define(FILTER, "reads", "V", true, body(6,
                        new ImmutableInstruction22t(Opcode.IF_EQ, 4, 4, 3),                      // 0 -> 3
                        op(Opcode.NOP),                                                          // 2
                        new ImmutableInstruction22t(Opcode.IF_NE, 5, 5, 3),                      // 3 -> 6
                        op(Opcode.NOP),                                                          // 5
                        new ImmutableInstruction22t(Opcode.IF_LT, 5, 5, 3),                      // 6 -> 9
                        op(Opcode.NOP),                                                          // 8
                        new ImmutableInstruction21t(Opcode.IF_NEZ, 5, 3),                        // 9 -> 12
                        op(Opcode.NOP),                                                          // 11
                        new ImmutableInstruction21t(Opcode.IF_GEZ, 5, 3),                        // 12 -> 15
                        op(Opcode.NOP),                                                          // 14
                        op(Opcode.MONITOR_ENTER, 4),                                             // 15
                        op(Opcode.MONITOR_EXIT, 4),                                              // 16
                        new ImmutableInstruction21c(Opcode.CHECK_CAST, 4, STRING_TYPE),          // 17
                        new ImmutableInstruction22c(Opcode.INSTANCE_OF, 0, 4, STRING_TYPE),      // 19
                        new ImmutableInstruction22c(Opcode.NEW_ARRAY, 1, 5, INT_ARRAY),          // 21
                        new ImmutableInstruction12x(Opcode.ARRAY_LENGTH, 2, 1),                  // 23
                        new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 1, 14),              // 24 -> 38
                        filledNewArray(INT_ARRAY, 5, 0),                                         // 27
                        filledNewArray(OBJECT_ARRAY, 4),                                         // 30
                        new ImmutableInstruction31t(Opcode.PACKED_SWITCH, 5, 11),                // 33 -> 44
                        new ImmutableInstruction11n(Opcode.CONST_4, 3, 0),                       // 36
                        op(Opcode.THROW, 3),                                                     // 37
                        oneInt(),                                                                // 38
                        new ImmutablePackedSwitchPayload(Collections.singletonList(              // 44
                                new ImmutableSwitchElement(0, 3)))), OBJECT, "I"));
        return new ImmutableClassDef(FILTER, AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(),
                OBJECT, null, null, null, packedField(FILTER), methods);
    }

    private static ClassDef secondary() {
        return new ImmutableClassDef("Lfixture/Secondary;", AccessFlags.PUBLIC.getValue(), OBJECT, null, null, null, null,
                Collections.singletonList(define("Lfixture/Secondary;", "kept", "V", true, body(0, op(Opcode.RETURN_VOID)))));
    }

    private static List<ClassDef> patched(Method feedEdge, Method staticHost, Method switchHost, Method tryHost) {
        return Arrays.asList(host(feedEdge, staticHost, switchHost, tryHost, true), filter());
    }

    private static List<ClassDef> good() {
        return patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST), switchHost(7), tryHost(CLEAN_TRY));
    }

    private static List<ClassDef> withStaticHost(ImmutableMethodImplementation implementation) {
        return patched(feedEdge(GUARDED_FEED_EDGE), staticHost(implementation), switchHost(7), tryHost(CLEAN_TRY));
    }

    private static List<ClassDef> withFeedEdge(ImmutableMethodImplementation implementation) {
        return patched(feedEdge(implementation), staticHost(GOOD_STATIC_HOST), switchHost(7), tryHost(CLEAN_TRY));
    }

    private static List<ClassDef> withTry(ImmutableTryBlock block) {
        return withTryHost(tryHost(block));
    }

    private static List<ClassDef> withTryHost(Method tryHost) {
        return patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST), switchHost(7), tryHost);
    }

    /**
     * staticHost with v0 an object on one arm of a branch and an int on the other, which ART merges
     * into a conflict where the arms meet at 5, and [then] from there. v1 and v2 are free, v3 is
     * the object argument, and the long sits in v4 and v5.
     */
    private static List<ClassDef> conflictThen(Instruction... then) {
        List<Instruction> instructions = new ArrayList<>(Arrays.asList(
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("edge")), // 0
                ifEqz(3, 3),                                                                              // 2 -> 5
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1)));                                      // 4
        instructions.addAll(Arrays.asList(then));
        return withStaticHost(new ImmutableMethodImplementation(6, instructions, null, null));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: BadDexFixture <outDir>");
            System.exit(2);
        }
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("Cannot create " + out);

        Map<String, List<ClassDef>> dexes = new LinkedHashMap<>();
        dexes.put("clean", Collections.singletonList(cleanHost()));
        dexes.put("secondary", Collections.singletonList(secondary()));
        dexes.put("good", good());
        List<ClassDef> goodWithSecondary = new ArrayList<>(good());
        goodWithSecondary.add(secondary());
        dexes.put("good-with-secondary", goodWithSecondary);
        dexes.put("removed-method", Arrays.asList(host(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchHost(7), tryHost(CLEAN_TRY), false), filter()));

        // branch: the guard's if-eqz jumps back into the middle of its own invoke.
        dexes.put("bad-branch", withFeedEdge(body(4,
                invoke(HIDE_EDGE, 2, 3), op(Opcode.MOVE_RESULT, 0), ifEqz(0, -3),
                op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID))));
        // branch: the guard's if-eqz jumps to itself.
        dexes.put("bad-branch-self", withFeedEdge(body(4,
                invoke(HIDE_EDGE, 2, 3), op(Opcode.MOVE_RESULT, 0), ifEqz(0, 0),
                op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID))));
        // branch: the guard's if-eqz jumps back onto its own move-result.
        dexes.put("bad-branch-to-result", withFeedEdge(body(4,
                invoke(HIDE_EDGE, 2, 3), op(Opcode.MOVE_RESULT, 0), ifEqz(0, -1),
                op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID))));
        // branch: the try path jumps onto the handler's move-exception.
        dexes.put("bad-goto-to-handler", withTryHost(tryHost(CLEAN_TRY, new ImmutableInstruction10t(Opcode.GOTO, 1))));
        // try: the try path falls straight into the handler's move-exception.
        dexes.put("bad-fallthrough-handler", withTryHost(tryHost(CLEAN_TRY, op(Opcode.NOP))));
        // width: a patch borrows v2 as a free local, but it is the upper half of the long argument.
        dexes.put("bad-wide-high-clobber", withStaticHost(body(3,
                new ImmutableInstruction11n(Opcode.CONST_4, 2, 0), invoke(INSPECT, 0, 0), invoke(WIDE, 1, 2),
                op(Opcode.RETURN_VOID))));
        // width: the same borrow on one arm of a branch only, and the long read where the arms
        // meet. ART merges the upper half with the zero into a conflict there.
        dexes.put("bad-wide-high-clobber-branch", withStaticHost(body(3,
                ifEqz(0, 3),                                        // 0 -> 3
                new ImmutableInstruction11n(Opcode.CONST_4, 2, 0),  // 2
                invoke(INSPECT, 0, 0),                              // 3
                invoke(WIDE, 1, 2),                                 // 6
                op(Opcode.RETURN_VOID))));                          // 9
        // width: the long read at a loop's head, and the borrow on the edge back to it.
        dexes.put("bad-wide-high-clobber-loop", withStaticHost(body(3,
                invoke(WIDE, 1, 2),                                 // 0
                ifEqz(0, 4),                                        // 3 -> 7
                new ImmutableInstruction11n(Opcode.CONST_4, 2, 0),  // 5
                new ImmutableInstruction10t(Opcode.GOTO, -6),       // 6 -> 0
                op(Opcode.RETURN_VOID))));                          // 7
        // width: a patch borrows v1, the lower half of the long argument, and passes the upper
        // half on as an int.
        dexes.put("bad-wide-low-clobber", withStaticHost(body(3,
                new ImmutableInstruction11n(Opcode.CONST_4, 1, 0), invoke(REUSE, 2), op(Opcode.RETURN_VOID))));
        // width: a const-wide lands one register below the long argument, on v1 and v2, which
        // leaves v3 the upper half of a pair that no longer has a lower half. v1 is the object
        // argument here, and the long sits in v2 and v3.
        dexes.put("bad-wide-below-pair", withStaticHost(body(4,
                new ImmutableInstruction21s(Opcode.CONST_WIDE_16, 1, 0), invoke(REUSE, 3), op(Opcode.RETURN_VOID))));
        // branch: case 1 lands inside the packed-switch instruction itself.
        dexes.put("bad-switch-case", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchHost(2), tryHost(CLEAN_TRY)));
        // branch: case 1 lands on the switch's own payload, which ART reaches as data.
        dexes.put("bad-switch-to-payload", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchHost(10), tryHost(CLEAN_TRY)));
        // branch: case 1 lands on a move-result, cut off from the invoke it takes its result from.
        dexes.put("bad-switch-to-result", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchToResult(), tryHost(CLEAN_TRY)));
        // branch: the no-case path jumps into the switch's payload.
        dexes.put("bad-goto-to-payload", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchHost(7, new ImmutableInstruction10t(Opcode.GOTO, 6)), tryHost(CLEAN_TRY)));
        // branch: a goto sent to a fill-array-data payload instead of the return before it.
        dexes.put("bad-goto-to-array-payload", withStaticHost(body(5,
                new ImmutableInstruction11n(Opcode.CONST_4, 1, 1),                                 // 0
                new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 1, INT_ARRAY),                    // 1
                new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 5),                         // 3 -> 8
                new ImmutableInstruction10t(Opcode.GOTO, 2),                                       // 6 -> 8
                op(Opcode.RETURN_VOID),                                                            // 7
                oneInt())));                                                                       // 8
        // branch: a switch case sent to a fill-array-data payload.
        dexes.put("bad-switch-to-array-payload", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchToArrayPayload(), tryHost(CLEAN_TRY)));
        // branch: a sparse switch's case sent to its own payload.
        dexes.put("bad-sparse-case-to-payload", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                sparseSwitchToOwnPayload(), tryHost(CLEAN_TRY)));
        // branch: case 1 sent to the nop that aligns the payload, which falls into the table.
        dexes.put("bad-fallthrough-into-payload", patched(feedEdge(GUARDED_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchHost(9), tryHost(CLEAN_TRY)));
        // branch: a method that starts at a payload, which its entry runs into.
        dexes.put("bad-payload-at-entry", withStaticHost(body(3, oneInt())));
        // invoke: one register for a callee that takes two.
        dexes.put("bad-invoke-count", withStaticHost(body(3,
                invoke(INSPECT, 0), invoke(WIDE, 1, 2), op(Opcode.RETURN_VOID))));
        // invoke: the long's halves passed as v1 and v0, not a pair.
        dexes.put("bad-wide-split", withStaticHost(body(3,
                invoke(INSPECT, 0, 0), invoke(WIDE, 1, 0), op(Opcode.RETURN_VOID))));
        // parameter: written as if the method had a this, so p1 is taken for the object; in a
        // static method it is the low half of the long.
        dexes.put("bad-static-parameter", withStaticHost(body(3,
                invoke(INSPECT, 0, 1), invoke(WIDE, 1, 2), op(Opcode.RETURN_VOID))));
        // parameter: the upper half of the long read as an object.
        dexes.put("bad-wide-parameter", withStaticHost(body(4,
                new ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 3),
                invoke(INSPECT, 1, 0), invoke(WIDE, 2, 3), op(Opcode.RETURN_VOID))));
        // width: the AMOLED sweep's shape on 580, a narrow const where a const-wide/32 was,
        // then read as a long. v0 and v1 are locals here, so this is the body's width, not the
        // parameter layout.
        dexes.put("bad-narrow-for-wide", withStaticHost(body(5,
                new ImmutableInstruction31i(Opcode.CONST, 0, BLACK), invoke(WIDE, 0, 1), op(Opcode.RETURN_VOID))));
        // width: the same narrow const on one arm of a branch only. Where the arms meet, v0 is a
        // long on one path and an int on the other, which ART merges into a conflict.
        dexes.put("bad-narrow-for-wide-branch", withStaticHost(body(5,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK), // 0
                ifEqz(2, 5),                                                 // 3 -> 8
                new ImmutableInstruction31i(Opcode.CONST, 0, BLACK),         // 5
                invoke(WIDE, 0, 1),                                          // 8
                op(Opcode.RETURN_VOID))));                                   // 11
        // width: 580's own static initializer, a narrow const shifted as a long and stored.
        // v0 to v2 are locals, the arguments sit in v3 to v5.
        dexes.put("bad-narrow-shift", withStaticHost(body(6, packColor(Opcode.CONST, HOST))));
        // width: the reverse, a const-wide/32 passed where an int goes.
        dexes.put("bad-wide-for-narrow", withStaticHost(body(5,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK), invoke(REUSE, 0), op(Opcode.RETURN_VOID))));
        // width: a conflict read by each instruction that takes a value, one build each: tested
        // against zero, tested for equality, ordered, switched on, locked, thrown, cast, tested for
        // a type, measured, used as a new array's size and as its element, and filled. ART's
        // verifier fails every one of these reads of a conflict.
        dexes.put("bad-conflict-if-eqz", conflictThen(
                ifEqz(0, 3), op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID)));                    // 5 -> 8
        dexes.put("bad-conflict-if-ne", conflictThen(
                new ImmutableInstruction22t(Opcode.IF_NE, 0, 3, 3), op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-if-lt", conflictThen(new ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                new ImmutableInstruction22t(Opcode.IF_LT, 0, 1, 3), op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-switch", conflictThen(
                new ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 5),                           // 5 -> 10
                op(Opcode.RETURN_VOID),                                                            // 8
                op(Opcode.NOP),                                                                    // 9, aligns the payload
                new ImmutablePackedSwitchPayload(Collections.singletonList(                        // 10
                        new ImmutableSwitchElement(0, 3)))));
        dexes.put("bad-conflict-monitor-enter", conflictThen(op(Opcode.MONITOR_ENTER, 0), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-throw", conflictThen(op(Opcode.THROW, 0)));
        dexes.put("bad-conflict-check-cast", conflictThen(
                new ImmutableInstruction21c(Opcode.CHECK_CAST, 0, STRING_TYPE), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-instance-of", conflictThen(
                new ImmutableInstruction22c(Opcode.INSTANCE_OF, 1, 0, STRING_TYPE), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-array-length", conflictThen(
                new ImmutableInstruction12x(Opcode.ARRAY_LENGTH, 1, 0), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-new-array", conflictThen(
                new ImmutableInstruction22c(Opcode.NEW_ARRAY, 1, 0, INT_ARRAY), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-filled-new-array", conflictThen(filledNewArray(INT_ARRAY, 0), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-fill-array-data", conflictThen(
                new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 5),                         // 5 -> 10
                op(Opcode.RETURN_VOID),                                                            // 8
                op(Opcode.NOP),                                                                    // 9, aligns the payload
                oneInt()));                                                                        // 10
        // width: a long tested against zero, which takes a narrow value or an object.
        dexes.put("bad-wide-if-eqz", withStaticHost(body(5,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK),                       // 0
                ifEqz(0, 3),                                                                       // 3 -> 6
                op(Opcode.RETURN_VOID),                                                            // 5
                op(Opcode.RETURN_VOID))));                                                         // 6
        // width: a long whose upper half was overwritten on one arm, and its lower half moved
        // where the arms meet. A pair broken on one path stays broken: ART still holds a low half
        // there, which a move may not copy, where a conflict would be copied without complaint.
        dexes.put("bad-broken-low-move", withStaticHost(body(6,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK),                       // 0
                ifEqz(3, 3),                                                                       // 3 -> 6
                new ImmutableInstruction11n(Opcode.CONST_4, 1, 1),                                 // 5
                new ImmutableInstruction12x(Opcode.MOVE, 2, 0),                                    // 6
                op(Opcode.RETURN_VOID))));                                                         // 7
        // width: the same with the lower half overwritten, and the upper half moved.
        dexes.put("bad-broken-high-move", withStaticHost(body(6,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK),                       // 0
                ifEqz(3, 3),                                                                       // 3 -> 6
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),                                 // 5
                new ImmutableInstruction12x(Opcode.MOVE, 2, 1),                                    // 6
                op(Opcode.RETURN_VOID))));                                                         // 7
        // width: a conflict copied by move-object, which ART allows, and the copy passed on as an
        // object, which it doesn't. Then a plain move's copy passed on as an int.
        dexes.put("bad-conflict-object-copy", conflictThen(
                new ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 0), invoke(INSPECT, 1, 3), op(Opcode.RETURN_VOID)));
        dexes.put("bad-conflict-plain-copy", conflictThen(
                new ImmutableInstruction12x(Opcode.MOVE, 1, 0), invoke(REUSE, 1), op(Opcode.RETURN_VOID)));
        // width: a zero on one arm where the other has a long, read as a long. A zero joins an
        // object or a narrow value, and ART merges it with a low half into a conflict.
        dexes.put("bad-zero-for-wide-branch", withStaticHost(body(6,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK),                       // 0
                ifEqz(3, 3),                                                                       // 3 -> 6
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),                                 // 5
                invoke(WIDE, 0, 1),                                                                // 6
                op(Opcode.RETURN_VOID))));                                                         // 9
        // width: move-wide of a conflict. A narrow or object move may copy one, and a wide move
        // may not, since ART checks the pair it copies.
        dexes.put("bad-move-wide-conflict", withStaticHost(body(7,
                new ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, BLACK),                       // 0
                ifEqz(4, 3),                                                                       // 3 -> 6
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),                                 // 5
                new ImmutableInstruction12x(Opcode.MOVE_WIDE, 2, 0),                               // 6
                op(Opcode.RETURN_VOID))));                                                         // 7
        // result: a nop injected between the guard's invoke and its move-result.
        dexes.put("bad-move-result", withFeedEdge(body(4,
                invoke(HIDE_EDGE, 2, 3), op(Opcode.NOP), op(Opcode.MOVE_RESULT, 0), ifEqz(0, 3),
                op(Opcode.RETURN_VOID), op(Opcode.RETURN_VOID))));
        // try: the range starts inside the invoke it means to cover.
        dexes.put("bad-try-range", withTry(tryBlock(1, 2, 5)));
        // try: the handler starts inside the invoke.
        dexes.put("bad-try-handler", withTry(tryBlock(0, 3, 2)));
        // try: the handler starts at the invoke's move-result.
        dexes.put("bad-try-handler-result", withTry(tryBlock(0, 3, 3)));
        // try: the handler starts at a switch payload.
        dexes.put("bad-try-handler-payload", withTryHost(tryHandlerAtPayload()));
        // try: the handler starts at a fill-array-data payload.
        dexes.put("bad-try-handler-array-payload", withTryHost(tryHandlerAtArrayPayload()));
        // try: the handler's move-exception is the method's first instruction, which the entry reaches.
        dexes.put("bad-move-exception-entry", withTryHost(moveExceptionAtEntry()));
        // contract: two guards stacked on the feed method.
        dexes.put("bad-double-guard", withFeedEdge(body(4,
                invoke(HIDE_EDGE, 2, 3), op(Opcode.MOVE_RESULT, 0), ifEqz(0, 3), op(Opcode.RETURN_VOID),
                invoke(HIDE_EDGE, 2, 3), op(Opcode.MOVE_RESULT, 0), ifEqz(0, 3), op(Opcode.RETURN_VOID),
                op(Opcode.RETURN_VOID))));
        // contract: the guard on another method, and the feed method left alone.
        dexes.put("bad-guard-elsewhere", patched(feedEdge(CLEAN_FEED_EDGE), staticHost(body(3,
                invoke(HIDE_EDGE, 0, 0), op(Opcode.MOVE_RESULT, 0), invoke(WIDE, 1, 2), op(Opcode.RETURN_VOID))),
                switchHost(7), tryHost(CLEAN_TRY)));
        // contract: no guard at all.
        dexes.put("bad-no-guard", patched(feedEdge(CLEAN_FEED_EDGE), staticHost(GOOD_STATIC_HOST),
                switchHost(7), tryHost(CLEAN_TRY)));

        for (Map.Entry<String, List<ClassDef>> e : dexes.entrySet()) {
            File dex = new File(out, e.getKey() + ".dex");
            DexPool.writeTo(dex.getPath(), new ImmutableDexFile(Opcodes.forApi(30), e.getValue()));
        }
        System.out.println("[fixture] wrote " + dexes.size() + " dex files to " + out.getPath());
    }
}
