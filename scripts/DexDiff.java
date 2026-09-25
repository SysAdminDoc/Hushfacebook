import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.ExceptionHandler;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.TryBlock;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Names every method a patched APK does not share with the clean one it was built from, and prints
 * the register evidence for each: how many registers the method declares, and every instruction the
 * two bodies do not have in common.
 *
 * <p>The point of it is the last line of the report. A patch that writes into a register the method
 * never declared assembles happily and only fails when a device verifies the class, so every
 * instruction that was not in the clean body is checked against the register count of the method it
 * landed in. Methods the patch adds outright, which is where a hand-written bridge lives and where
 * the registers are chosen rather than inherited, are checked the same way.
 *
 * <p>Three things this deliberately does not leave to chance:
 *
 * <ul>
 *   <li>A wide instruction names one register and occupies two, so a destination that sets a wide
 *       register counts as reaching one higher than it names. Missing that would let a
 *       {@code const-wide} one short of the ceiling read as safe.
 *   <li>The highest register travels with the rendered line rather than being read back out of the
 *       text, because this app has string constants that look like register names ({@code v4190},
 *       {@code v20200906}) and parsing the text flags five of them.
 *   <li>Absence of evidence is a failure, not a pass. A run that finds no changed methods, or no
 *       added methods of the extension's own, is comparing the wrong pair of files and says so
 *       instead of reporting nothing wrong.
 * </ul>
 *
 * <p>Branch targets and try-block ranges are part of a body's identity here, so a change that only
 * moves a jump or widens an exception range still shows up as a changed method.
 *
 * <p>Every changed and added method is also held to the structural rules Facebook's own crash
 * reports came from (FroggoMorphePatches issues 3, 16 and 21): a branch or switch case that lands
 * inside an instruction ("target dex pc is not at instruction start"), an invoke whose registers
 * don't match what the callee takes, a parameter register read as the wrong kind (the static and
 * wide off-by-one), a register the body wrote at the wrong width (a narrow const left where a
 * const-wide was, which the AMOLED sweep did on 580), a move-result cut off from its invoke, and a
 * try range or handler off an instruction boundary. A contract file adds
 * rules about the whole APK: the feed filter's guard has exactly one call site, in
 * addNewEdgeToCollection, because two guards stacked on that method is what broke Froggo's
 * builds. The device verifier stays the authority; these catch the known shapes without a phone.
 *
 *   java -cp &lt;cli jar&gt; DexDiff.java &lt;cleanApk&gt; &lt;patchedApk&gt; &lt;reportFile&gt;
 *       &lt;removalAllowlist&gt; [&lt;contracts&gt;]
 */
public class DexDiff {

    /** Anything under here is the bundle's own code rather than the host's. */
    private static final String OWN = "Lapp/morphe/";

    private static final class RemovalAllowlist {
        final Set<String> methods = new TreeSet<>();
        final Set<String> dexEntries = new TreeSet<>();
    }

    private static RemovalAllowlist readRemovalAllowlist(File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Removal allowlist not found: " + file);
        RemovalAllowlist allowlist = new RemovalAllowlist();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Set<String> target;
            String value;
            if (line.startsWith("method ")) {
                target = allowlist.methods;
                value = line.substring("method ".length()).trim();
            } else if (line.startsWith("dex ")) {
                target = allowlist.dexEntries;
                value = line.substring("dex ".length()).trim();
            } else {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": expected method or dex");
            }
            if (value.isEmpty() || !target.add(value)) {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": value is empty or duplicated");
            }
        }
        return allowlist;
    }

    /** "single-call &lt;method reference&gt; in &lt;caller method name&gt;": exactly one call site, there. */
    private static final class Contract {
        final String callee;
        final String callerName;

        Contract(String callee, String callerName) {
            this.callee = callee;
            this.callerName = callerName;
        }
    }

    private static List<Contract> readContracts(File file) throws Exception {
        List<Contract> contracts = new ArrayList<>();
        if (file == null) return contracts;
        if (!file.isFile()) throw new IllegalArgumentException("Contract file not found: " + file);
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 4 || !parts[0].equals("single-call") || !parts[2].equals("in")
                    || !parts[1].contains("->")) {
                throw new IllegalArgumentException("Invalid contract line " + lineNumber
                        + ": expected single-call <method reference> in <caller method name>");
            }
            contracts.add(new Contract(parts[1], parts[3]));
        }
        return contracts;
    }

    /** Every instruction of a body with its code-unit address, and where the body ends. */
    private static final class Layout {
        final List<Instruction> instructions = new ArrayList<>();
        final List<Integer> addresses = new ArrayList<>();
        final Map<Integer, Instruction> byAddress = new HashMap<>();
        int size;

        Layout(MethodImplementation impl) {
            int address = 0;
            for (Instruction i : impl.getInstructions()) {
                instructions.add(i);
                addresses.add(address);
                byAddress.put(address, i);
                address += i.getCodeUnits();
            }
            size = address;
        }

        boolean isStart(int address) {
            return byAddress.containsKey(address);
        }
    }

    /** Registers a parameter of this type takes: two for long and double, one for anything else. */
    private static int slots(CharSequence type) {
        char c = type.charAt(0);
        return c == 'J' || c == 'D' ? 2 : 1;
    }

    /** The kind a value of this type has in a register: L object, W wide (low half), I narrow. */
    private static char kindOf(CharSequence type) {
        char c = type.charAt(0);
        if (c == 'J' || c == 'D') return 'W';
        if (c == 'L' || c == '[') return 'L';
        return 'I';
    }

    private static boolean isStaticInvoke(Opcode opcode) {
        return opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE;
    }

    /** The invoke kinds whose registers are the callee's receiver and parameters, in order. */
    private static boolean isPlainInvoke(Opcode opcode) {
        switch (opcode) {
            case INVOKE_VIRTUAL: case INVOKE_SUPER: case INVOKE_DIRECT: case INVOKE_STATIC:
            case INVOKE_INTERFACE: case INVOKE_VIRTUAL_RANGE: case INVOKE_SUPER_RANGE:
            case INVOKE_DIRECT_RANGE: case INVOKE_STATIC_RANGE: case INVOKE_INTERFACE_RANGE:
                return true;
            default:
                return false;
        }
    }

    /** The registers an invoke passes, in argument order. */
    private static int[] invokeRegisters(Instruction i) {
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            int[] regs = new int[r.getRegisterCount()];
            for (int k = 0; k < regs.length; k++) regs[k] = r.getStartRegister() + k;
            return regs;
        }
        FiveRegisterInstruction r = (FiveRegisterInstruction) i;
        int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
        int[] regs = new int[r.getRegisterCount()];
        System.arraycopy(all, 0, regs, 0, regs.length);
        return regs;
    }

    /**
     * For each instruction, the parameter registers some path from the entry may have written
     * before it runs, as bits counted from the first parameter register, or null where no path
     * reaches it. A register no path has written still holds its argument, so its kind is known.
     * An exception edge carries the state after the throwing instruction, which only adds writes,
     * so wherever a path is in doubt the check that reads this stays quiet.
     */
    private static BitSet[] parameterWrites(MethodImplementation impl, Layout layout, int firstParameter) {
        int count = layout.instructions.size();
        BitSet[] before = new BitSet[count];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = new BitSet();
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);
            BitSet after = (BitSet) before[k].clone();
            if (opcode.setsRegister() && i instanceof OneRegisterInstruction) {
                int destination = ((OneRegisterInstruction) i).getRegisterA();
                int last = destination + (opcode.setsWideRegister() ? 1 : 0);
                for (int r = Math.max(destination, firstParameter); r <= last; r++) after.set(r - firstParameter);
            }
            List<Integer> next = new ArrayList<>();
            if (opcode.canContinue()) next.add(at + i.getCodeUnits());
            if (i instanceof OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA) {
                int target = at + ((OffsetInstruction) i).getCodeOffset();
                if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                    Instruction payload = layout.byAddress.get(target);
                    if (payload instanceof SwitchPayload) {
                        for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                            next.add(at + element.getOffset());
                        }
                    }
                } else {
                    next.add(target);
                }
            }
            if (opcode.canThrow()) {
                for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
                    int start = block.getStartCodeAddress();
                    if (at < start || at >= start + block.getCodeUnitCount()) continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) {
                        next.add(handler.getHandlerCodeAddress());
                    }
                }
            }
            for (int address : next) {
                Integer successor = indexAt.get(address);
                if (successor == null) continue;
                if (before[successor] == null) {
                    before[successor] = (BitSet) after.clone();
                    work.add(successor);
                    continue;
                }
                BitSet merged = (BitSet) before[successor].clone();
                merged.or(after);
                if (!merged.equals(before[successor])) {
                    before[successor] = merged;
                    work.add(successor);
                }
            }
        }
        return before;
    }

    private static boolean isMoveResult(Opcode opcode) {
        return opcode == Opcode.MOVE_RESULT || opcode == Opcode.MOVE_RESULT_WIDE
                || opcode == Opcode.MOVE_RESULT_OBJECT;
    }

    /**
     * The kind each register holds before each instruction, along every path from the entry, or
     * null where no path reaches it: L an object, I a narrow value, Z a zero constant (narrow or
     * null), W and w the halves of a wide value, B and b the halves of one whose other half was
     * overwritten, C a register the paths that meet there disagree about, T unknown. The
     * parameters start with their declared kinds; constants, moves, move-results and the plain
     * object producers set theirs; a wide result sets a pair; anything else leaves T, which no
     * check reads. Where paths meet, the kinds join the way ART merges them (see join), so a pair
     * broken or a width changed on one arm of a branch is still a finding after the arms rejoin.
     * An exception edge carries the kinds from before the throwing instruction, as ART's does: the
     * exception leaves before the instruction writes anything.
     */
    private static char[][] registerKinds(MethodImplementation impl, Layout layout,
            Map<Integer, Character> parameterKind) {
        int count = layout.instructions.size();
        int registers = impl.getRegisterCount();
        char[][] before = new char[count][];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        char[] entry = new char[registers];
        java.util.Arrays.fill(entry, 'T');
        for (Map.Entry<Integer, Character> p : parameterKind.entrySet()) {
            if (p.getKey() < registers) entry[p.getKey()] = p.getValue();
        }
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = entry;
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);
            char[] after = transfer(i, before[k]);
            List<Integer> next = new ArrayList<>();
            if (opcode.canContinue()) next.add(at + i.getCodeUnits());
            if (i instanceof OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA) {
                int target = at + ((OffsetInstruction) i).getCodeOffset();
                if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                    Instruction payload = layout.byAddress.get(target);
                    if (payload instanceof SwitchPayload) {
                        for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                            next.add(at + element.getOffset());
                        }
                    }
                } else {
                    next.add(target);
                }
            }
            List<Integer> handlers = new ArrayList<>();
            if (opcode.canThrow()) {
                for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
                    int start = block.getStartCodeAddress();
                    if (at < start || at >= start + block.getCodeUnitCount()) continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) {
                        handlers.add(handler.getHandlerCodeAddress());
                    }
                }
            }
            for (int address : next) flow(before, indexAt, work, address, after);
            for (int address : handlers) flow(before, indexAt, work, address, before[k]);
        }
        return before;
    }

    private static void flow(char[][] before, Map<Integer, Integer> indexAt, Deque<Integer> work,
            int address, char[] state) {
        Integer successor = indexAt.get(address);
        if (successor == null) return;
        if (before[successor] == null) {
            before[successor] = state.clone();
            work.add(successor);
            return;
        }
        boolean changed = false;
        char[] into = before[successor];
        for (int r = 0; r < into.length; r++) {
            char joined = join(into[r], state[r]);
            if (joined != into[r]) {
                into[r] = joined;
                changed = true;
            }
        }
        if (changed) work.add(successor);
    }

    /**
     * One register's kind where two paths meet, as ART merges it. A zero is a null or a zero, so it
     * takes the other side's object or narrow kind, and a pair broken on one path is broken. Any
     * other two known kinds make C, a conflict: ART lets a move copy one, and fails every other
     * read of it. T stays T, since a register this doesn't know is never judged.
     */
    private static char join(char a, char b) {
        if (a == b) return a;
        if (a == 'T' || b == 'T') return 'T';
        if (a == 'Z' && (b == 'L' || b == 'I')) return b;
        if (b == 'Z' && (a == 'L' || a == 'I')) return a;
        if ((a == 'W' && b == 'B') || (a == 'B' && b == 'W')) return 'B';
        if ((a == 'w' && b == 'b') || (a == 'b' && b == 'w')) return 'b';
        return 'C';
    }

    /** A narrow or object move: ART lets one copy a conflict, which fails only where it's used. */
    private static boolean copiesConflict(Opcode opcode) {
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                return true;
            default:
                return false;
        }
    }

    /** The kinds after one instruction. */
    private static char[] transfer(Instruction i, char[] in) {
        Opcode opcode = i.getOpcode();
        if (!opcode.setsRegister() || !(i instanceof OneRegisterInstruction)) return in;
        char[] out = in.clone();
        int a = ((OneRegisterInstruction) i).getRegisterA();
        // A move copies its source's kind, a conflict included, unless the read is already a
        // finding: then what it wrote is unknown, and the one mistake is reported once rather
        // than at every later use.
        if (opcode.setsWideRegister()) {
            char low = 'W', high = 'w';
            if (opcode == Opcode.MOVE_WIDE || opcode == Opcode.MOVE_WIDE_FROM16 || opcode == Opcode.MOVE_WIDE_16) {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                boolean wide = b + 1 < in.length && in[b] == 'W' && in[b + 1] == 'w';
                low = wide ? 'W' : 'T';
                high = wide ? 'w' : 'T';
            }
            write(out, a, low);
            write(out, a + 1, high);
            return out;
        }
        char kind;
        switch (opcode) {
            case CONST_4: case CONST_16: case CONST: case CONST_HIGH16:
                kind = ((com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) i)
                        .getNarrowLiteral() == 0 ? 'Z' : 'I';
                break;
            case MOVE: case MOVE_FROM16: case MOVE_16: {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                char source = b < in.length ? in[b] : 'T';
                kind = source == 'C' || (source != 'T' && readableAs(source, 'I')) ? source : 'T';
                break;
            }
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16: {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                char source = b < in.length ? in[b] : 'T';
                kind = source == 'C' || (source != 'T' && readableAs(source, 'L')) ? source : 'T';
                break;
            }
            case MOVE_RESULT: case INSTANCE_OF: case ARRAY_LENGTH:
            case IGET: case IGET_BOOLEAN: case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
            case SGET: case SGET_BOOLEAN: case SGET_BYTE: case SGET_CHAR: case SGET_SHORT:
            case AGET: case AGET_BOOLEAN: case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
                kind = 'I';
                break;
            case MOVE_RESULT_OBJECT: case MOVE_EXCEPTION: case CONST_STRING: case CONST_STRING_JUMBO:
            case CONST_CLASS: case NEW_INSTANCE: case NEW_ARRAY: case CHECK_CAST:
            case IGET_OBJECT: case SGET_OBJECT: case AGET_OBJECT:
                kind = 'L';
                break;
            default:
                // Arithmetic, compares and conversions that don't set a pair set a narrow value.
                kind = arithmeticOperands(opcode) != null ? 'I' : 'T';
        }
        write(out, a, kind);
        return out;
    }

    /**
     * One register's new kind, and the wide pair it breaks if it was half of one. ART keeps the
     * half that wasn't written as it was and checks the pair where it's read, so the half left
     * behind can't be read as anything: writing over the upper half leaves the lower half as B,
     * and writing over the lower half leaves the upper half as b. A patch that borrows either half
     * of a live long as a free local does this, and so does a wide write that lands one register
     * below a live pair.
     */
    private static void write(char[] out, int register, char kind) {
        if (register >= out.length) return;
        if (out[register] == 'W' && register + 1 < out.length && out[register + 1] == 'w' && kind != 'W') {
            out[register + 1] = 'b';
        }
        if (out[register] == 'w' && register > 0 && out[register - 1] == 'W' && kind != 'w') {
            out[register - 1] = 'B';
        }
        out[register] = kind;
    }

    /**
     * Whether a register of this kind can be read as that kind. T is never in doubt here; B, b, w
     * and C can't be read as anything, and W only as the pair it starts. E is what a test against
     * zero or an equality test takes: a narrow value or an object.
     */
    private static boolean readableAs(char have, char want) {
        if (have == 'T') return true;
        if (have == 'B') return false;
        switch (want) {
            case 'W': return have == 'W';
            case 'I': return have == 'I' || have == 'Z';
            case 'L': return have == 'L' || have == 'Z';
            case 'E': return have == 'I' || have == 'Z' || have == 'L';
            default: return true;
        }
    }

    private static final java.util.regex.Pattern WIDE_PAIR = java.util.regex.Pattern.compile(
            "(add|sub|mul|div|rem|and|or|xor)-(long|double)(/2addr)?|cmp-long|cmp[lg]-double");
    private static final java.util.regex.Pattern WIDE_SHIFT = java.util.regex.Pattern.compile(
            "(shl|shr|ushr)-long(/2addr)?");
    private static final java.util.regex.Pattern NARROW_PAIR = java.util.regex.Pattern.compile(
            "(add|sub|mul|div|rem|and|or|xor|shl|shr|ushr)-(int|float)(/2addr)?|cmp[lg]-float");
    private static final java.util.regex.Pattern NARROW_LITERAL = java.util.regex.Pattern.compile(
            "(add|rsub|mul|div|rem|and|or|xor|shl|shr|ushr)-int/lit(8|16)|rsub-int");
    private static final java.util.regex.Pattern WIDE_SINGLE = java.util.regex.Pattern.compile(
            "(neg|not)-(long|double)|(long|double)-to-(int|long|float|double)");
    private static final java.util.regex.Pattern NARROW_SINGLE = java.util.regex.Pattern.compile(
            "(neg|not)-(int|float)|(int|float)-to-(int|long|float|double|byte|char|short)");

    /** The kinds an arithmetic, compare or conversion instruction reads its operands as, or null. */
    private static char[] arithmeticOperands(Opcode opcode) {
        String name = opcode.name;
        if (WIDE_PAIR.matcher(name).matches()) return new char[]{'W', 'W'};
        if (WIDE_SHIFT.matcher(name).matches()) return new char[]{'W', 'I'};
        if (NARROW_PAIR.matcher(name).matches()) return new char[]{'I', 'I'};
        if (NARROW_LITERAL.matcher(name).matches() || NARROW_SINGLE.matcher(name).matches()) return new char[]{'I'};
        if (WIDE_SINGLE.matcher(name).matches()) return new char[]{'W'};
        return null;
    }

    private static void read(List<int[]> reads, int register, char kind) {
        reads.add(new int[]{register, kind});
    }

    /** Every register an instruction reads as a value, with the kind it reads it as. */
    private static List<int[]> valueReads(Instruction i) {
        List<int[]> reads = new ArrayList<>();
        Opcode opcode = i.getOpcode();
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case MOVE_WIDE: case MOVE_WIDE_FROM16: case MOVE_WIDE_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'W');
                return reads;
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case RETURN:
            case SPUT: case SPUT_BOOLEAN: case SPUT_BYTE: case SPUT_CHAR: case SPUT_SHORT:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'I');
                return reads;
            case RETURN_WIDE: case SPUT_WIDE:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'W');
                return reads;
            case RETURN_OBJECT: case SPUT_OBJECT:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'L');
                return reads;
            case IPUT: case IPUT_BOOLEAN: case IPUT_BYTE: case IPUT_CHAR: case IPUT_SHORT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'I');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IPUT_WIDE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'W');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IPUT_OBJECT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'L');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IGET: case IGET_BOOLEAN: case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
            case IGET_WIDE: case IGET_OBJECT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case APUT: case APUT_BOOLEAN: case APUT_BYTE: case APUT_CHAR: case APUT_SHORT:
            case APUT_WIDE: case APUT_OBJECT: {
                ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                char value = opcode == Opcode.APUT_WIDE ? 'W' : opcode == Opcode.APUT_OBJECT ? 'L' : 'I';
                read(reads, t.getRegisterA(), value);
                read(reads, t.getRegisterB(), 'L');
                read(reads, t.getRegisterC(), 'I');
                return reads;
            }
            case AGET: case AGET_BOOLEAN: case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
            case AGET_WIDE: case AGET_OBJECT: {
                ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                read(reads, t.getRegisterB(), 'L');
                read(reads, t.getRegisterC(), 'I');
                return reads;
            }
            // Each register as ART's verifier checks it. A test against zero or for equality takes
            // a narrow value or an object, an ordering, a switch or a new array's size takes a
            // narrow value, and a lock, a throw, a cast, a type test, or an array to measure or
            // fill takes an object. An equality test of an int with an object is left alone.
            case IF_EQZ: case IF_NEZ:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'E');
                return reads;
            case IF_EQ: case IF_NE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'E');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'E');
                return reads;
            case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ:
            case PACKED_SWITCH: case SPARSE_SWITCH:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'I');
                return reads;
            case IF_LT: case IF_GE: case IF_GT: case IF_LE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'I');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case MONITOR_ENTER: case MONITOR_EXIT: case THROW: case CHECK_CAST: case FILL_ARRAY_DATA:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'L');
                return reads;
            case INSTANCE_OF: case ARRAY_LENGTH:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case NEW_ARRAY:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case FILLED_NEW_ARRAY: case FILLED_NEW_ARRAY_RANGE: {
                // Each element as the array holds it: an object, or a narrow value.
                Reference type = ((ReferenceInstruction) i).getReference();
                if (!(type instanceof TypeReference)) return reads;
                char element = kindOf(((TypeReference) type).getType().substring(1));
                for (int register : invokeRegisters(i)) read(reads, register, element);
                return reads;
            }
            default:
                break;
        }
        char[] operands = arithmeticOperands(opcode);
        if (operands == null) return reads;
        int[] registers;
        if (i instanceof ThreeRegisterInstruction) {
            registers = new int[]{((ThreeRegisterInstruction) i).getRegisterB(), ((ThreeRegisterInstruction) i).getRegisterC()};
        } else if (i instanceof TwoRegisterInstruction && opcode.name.endsWith("/2addr")) {
            registers = new int[]{((TwoRegisterInstruction) i).getRegisterA(), ((TwoRegisterInstruction) i).getRegisterB()};
        } else if (i instanceof TwoRegisterInstruction) {
            registers = new int[]{((TwoRegisterInstruction) i).getRegisterB()};
        } else {
            return reads;
        }
        for (int k = 0; k < Math.min(operands.length, registers.length); k++) read(reads, registers[k], operands[k]);
        return reads;
    }

    /**
     * The structural findings for one method, each as "category: detail". The parameter check
     * reads a register only where no path from the entry has written it, where the value can
     * only be the argument the method was called with.
     */
    private static List<String> structuralFindings(ClassDef cd, Method m) {
        List<String> findings = new ArrayList<>();
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return findings;
        Layout layout = new Layout(impl);

        // A move-result takes the result of the instruction right before it, so an instruction
        // injected between an invoke and its move-result leaves nothing to take.
        for (int k = 0; k < layout.instructions.size(); k++) {
            if (!isMoveResult(layout.instructions.get(k).getOpcode())) continue;
            if (k == 0 || !layout.instructions.get(k - 1).getOpcode().setsResult()) {
                findings.add("result: " + layout.instructions.get(k).getOpcode().name + " at "
                        + layout.addresses.get(k) + " does not follow an invoke");
            }
        }

        // Branches and switch cases.
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            if (!(i instanceof OffsetInstruction)) continue;
            int at = layout.addresses.get(k);
            int target = at + ((OffsetInstruction) i).getCodeOffset();
            Opcode opcode = i.getOpcode();
            if (target == at && opcode != Opcode.GOTO_32) {
                findings.add("branch: " + opcode.name + " at " + at + " branches to itself");
                continue;
            }
            if (!layout.isStart(target)) {
                findings.add("branch: " + opcode.name + " at " + at + " targets " + target
                        + ", which is not the start of an instruction");
                continue;
            }
            Instruction payload = layout.byAddress.get(target);
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                if (!(payload instanceof SwitchPayload)) {
                    findings.add("branch: " + opcode.name + " at " + at + " points at " + target
                            + ", which is not a switch payload");
                    continue;
                }
                for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                    int caseTarget = at + element.getOffset();
                    if (!layout.isStart(caseTarget)) {
                        findings.add("branch: " + opcode.name + " at " + at + " sends case "
                                + element.getKey() + " to " + caseTarget
                                + ", which is not the start of an instruction");
                        continue;
                    }
                    String bad = badLanding(layout.byAddress.get(caseTarget));
                    if (bad != null) {
                        findings.add("branch: " + opcode.name + " at " + at + " sends case " + element.getKey()
                                + " to " + caseTarget + ", " + bad + ", which a branch may not land on");
                    }
                }
            } else if (opcode == Opcode.FILL_ARRAY_DATA) {
                if (!(payload instanceof ArrayPayload)) {
                    findings.add("branch: fill-array-data at " + at + " points at " + target
                            + ", which is not an array payload");
                }
            } else {
                String bad = badLanding(payload);
                if (bad != null) {
                    findings.add("branch: " + opcode.name + " at " + at + " targets " + target + ", " + bad
                            + ", which a branch may not land on");
                }
            }
        }

        // Try ranges and handlers.
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            int start = block.getStartCodeAddress();
            int end = start + block.getCodeUnitCount();
            if (block.getCodeUnitCount() <= 0) {
                findings.add("try: a try range at " + start + " covers nothing");
            }
            if (!layout.isStart(start)) {
                findings.add("try: a try range starts at " + start + ", which is not the start of an instruction");
            }
            if (end > layout.size || (end != layout.size && !layout.isStart(end))) {
                findings.add("try: a try range ends at " + end + ", which is not an instruction boundary");
            }
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                int address = handler.getHandlerCodeAddress();
                if (!layout.isStart(address)) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " is at "
                            + address + ", which is not the start of an instruction");
                } else if (isMoveResult(layout.byAddress.get(address).getOpcode())) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " starts with a move-result at "
                            + address);
                } else if (isPayload(layout.byAddress.get(address))) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " starts at a payload at "
                            + address);
                }
            }
        }

        // Invokes: as many registers as the callee takes, and each wide argument in a pair.
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            if (!isPlainInvoke(opcode) || !(i instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
            MethodReference callee = (MethodReference) ((ReferenceInstruction) i).getReference();
            List<Character> expected = expectedArguments(opcode, callee);
            int[] regs = invokeRegisters(i);
            int at = layout.addresses.get(k);
            if (regs.length != expected.size()) {
                findings.add("invoke: " + opcode.name + " at " + at + " passes " + regs.length
                        + (regs.length == 1 ? " register" : " registers") + " to " + callee
                        + ", which takes " + expected.size());
                continue;
            }
            for (int a = 0; a < regs.length; a++) {
                if (expected.get(a) == 'w' && regs[a] != regs[a - 1] + 1) {
                    findings.add("invoke: " + opcode.name + " at " + at + " splits a wide argument of "
                            + callee + " across v" + regs[a - 1] + " and v" + regs[a]);
                }
            }
        }

        // Parameters: registerCount minus the ins, then this, then each parameter in order.
        boolean isStatic = AccessFlags.STATIC.isSet(m.getAccessFlags());
        int ins = isStatic ? 0 : 1;
        for (CharSequence p : m.getParameterTypes()) ins += slots(p);
        int firstParameter = impl.getRegisterCount() - ins;
        if (firstParameter < 0) {
            findings.add("parameter: declares " + impl.getRegisterCount() + " registers but its parameters need " + ins);
            return findings;
        }
        Map<Integer, Character> parameterKind = new HashMap<>();
        int register = firstParameter;
        if (!isStatic) parameterKind.put(register++, 'L');
        for (CharSequence p : m.getParameterTypes()) {
            char kind = kindOf(p);
            parameterKind.put(register, kind);
            if (kind == 'W') parameterKind.put(register + 1, 'w');
            register += slots(p);
        }

        // Kinds: a read of a register that every path gives the wrong kind. Where no path has
        // written the register it still holds its argument, and the finding is the parameter
        // layout's (the static and wide off-by-one); otherwise the body wrote the wrong width,
        // such as a narrow const left where a const-wide was.
        BitSet[] writes = parameterWrites(impl, layout, firstParameter);
        char[][] kinds = registerKinds(impl, layout, parameterKind);
        for (int k = 0; k < layout.instructions.size(); k++) {
            if (kinds[k] == null || writes[k] == null) continue;
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);

            for (int[] read : valueReads(i)) {
                if (read[0] >= kinds[k].length) continue;
                char have = kinds[k][read[0]];
                if (readableAs(have, (char) read[1]) || (have == 'C' && copiesConflict(opcode))) continue;
                findings.add(origin(read[0], firstParameter, writes[k], have) + opcode.name + " at " + at
                        + " reads v" + read[0] + ", which holds " + describe(have)
                        + ", as " + describe((char) read[1]));
            }

            if (!isPlainInvoke(opcode) || !(i instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
            MethodReference callee = (MethodReference) ((ReferenceInstruction) i).getReference();
            List<Character> expected = expectedArguments(opcode, callee);
            int[] regs = invokeRegisters(i);
            if (regs.length != expected.size()) continue;
            for (int a = 0; a < regs.length; a++) {
                char want = expected.get(a);
                if (want == 'w' || regs[a] >= kinds[k].length) continue;
                char have = kinds[k][regs[a]];
                if (!readableAs(have, want)) {
                    findings.add(origin(regs[a], firstParameter, writes[k], have) + opcode.name + " at " + at
                            + " passes v" + regs[a] + ", which holds " + describe(have) + ", where "
                            + callee + " takes " + describe(want));
                }
            }
        }

        // A move-exception takes the exception a handler caught, so only a throw may reach it.
        // Falling into one from the instruction above is what ART calls flowing through to it,
        // and the method's entry reaches one at the very start the same way.
        if (!layout.instructions.isEmpty() && layout.instructions.get(0).getOpcode() == Opcode.MOVE_EXCEPTION) {
            findings.add("try: move-exception at 0 is reached from the method's entry");
        }
        for (int k = 1; k < layout.instructions.size(); k++) {
            if (layout.instructions.get(k).getOpcode() != Opcode.MOVE_EXCEPTION) continue;
            Instruction above = layout.instructions.get(k - 1);
            if (kinds[k - 1] != null && above.getOpcode().canContinue()) {
                findings.add("try: move-exception at " + layout.addresses.get(k) + " is reached by falling through from "
                        + above.getOpcode().name + " at " + layout.addresses.get(k - 1));
            }
        }

        // A payload is data, and ART fails the class wherever flow reaches one. A branch, a case
        // or a handler sent to one is reported above; falling into one from the instruction above
        // it, which a return or goto removed from before a switch table leaves, and a payload at
        // the very start are the other ways in.
        if (!layout.instructions.isEmpty() && isPayload(layout.instructions.get(0))) {
            findings.add("branch: the payload at 0 is reached from the method's entry");
        }
        for (int k = 1; k < layout.instructions.size(); k++) {
            if (!isPayload(layout.instructions.get(k))) continue;
            Instruction above = layout.instructions.get(k - 1);
            if (kinds[k - 1] != null && above.getOpcode().canContinue()) {
                findings.add("branch: the payload at " + layout.addresses.get(k) + " is reached by falling through from "
                        + above.getOpcode().name + " at " + layout.addresses.get(k - 1));
            }
        }
        return findings;
    }

    /**
     * "parameter" for a register that still holds its argument on every path, else "width". A
     * broken pair or a conflict is always the body's doing, even on an argument's register.
     */
    private static String origin(int register, int firstParameter, BitSet written, char have) {
        if (have == 'B' || have == 'b' || have == 'C') return "width: ";
        boolean argument = register >= firstParameter && !written.get(register - firstParameter);
        return argument ? "parameter: " : "width: ";
    }

    /** A switch or array payload: data inside the code, which ART fails the moment flow reaches it. */
    private static boolean isPayload(Instruction i) {
        return i instanceof SwitchPayload || i instanceof ArrayPayload;
    }

    /**
     * What a branch or switch case may not land on, or null: ART refuses a move-result or
     * move-exception there, and a payload is data ("encountered data table in instruction stream").
     */
    private static String badLanding(Instruction landing) {
        if (landing == null) return null;
        if (isPayload(landing)) return "a payload";
        Opcode opcode = landing.getOpcode();
        if (isMoveResult(opcode)) return "a move-result";
        if (opcode == Opcode.MOVE_EXCEPTION) return "a move-exception";
        return null;
    }

    /** The kind of each register an invoke passes: the receiver, then each parameter's slots. */
    private static List<Character> expectedArguments(Opcode opcode, MethodReference callee) {
        List<Character> expected = new ArrayList<>();
        if (!isStaticInvoke(opcode)) expected.add('L');
        for (CharSequence p : callee.getParameterTypes()) {
            expected.add(kindOf(p));
            if (slots(p) == 2) expected.add('w');
        }
        return expected;
    }

    private static String describe(char kind) {
        switch (kind) {
            case 'L': return "an object";
            case 'W': return "a wide value";
            case 'w': return "the upper half of a wide value";
            case 'B': return "the lower half of a wide value whose upper half was overwritten";
            case 'b': return "the upper half of a wide value whose lower half was overwritten";
            case 'C': return "a different kind depending on the path taken";
            case 'Z': return "a zero constant";
            case 'E': return "a narrow value or an object";
            default: return "a narrow value";
        }
    }

    /**
     * Holds every changed and added method of the patched APK to the structural rules, and counts
     * each contract's call sites across the whole APK. Returns method signature to findings, with
     * contract results under the pseudo-method "contract".
     */
    private static Map<String, List<String>> structuralPass(File apk, Set<String> wanted,
            List<Contract> contracts) throws Exception {
        Map<String, List<String>> out = new TreeMap<>();
        Map<String, List<String>> callSites = new LinkedHashMap<>();
        for (Contract contract : contracts) callSites.put(contract.callee, new ArrayList<>());
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    if (wanted.contains(s)) {
                        List<String> findings = structuralFindings(cd, m);
                        if (!findings.isEmpty()) out.put(s, findings);
                    }
                    if (callSites.isEmpty() || m.getImplementation() == null) continue;
                    for (Instruction i : m.getImplementation().getInstructions()) {
                        if (!(i instanceof ReferenceInstruction)) continue;
                        Reference r = ((ReferenceInstruction) i).getReference();
                        if (!(r instanceof MethodReference)) continue;
                        List<String> sites = callSites.get(r.toString());
                        if (sites != null) sites.add(s);
                    }
                }
            }
        }
        List<String> contractFindings = new ArrayList<>();
        for (Contract contract : contracts) {
            List<String> sites = callSites.get(contract.callee);
            System.out.println("[diff] contract " + contract.callee + ": " + sites.size() + " call site"
                    + (sites.size() == 1 ? "" : "s") + (sites.isEmpty() ? "" : ", in " + String.join(", ", sites)));
            if (sites.size() != 1) {
                contractFindings.add("contract: " + contract.callee + " has " + sites.size()
                        + " call sites, and must have exactly one, in " + contract.callerName
                        + (sites.isEmpty() ? "" : ": " + String.join(", ", sites)));
            } else if (!sites.get(0).contains("->" + contract.callerName + "(")) {
                contractFindings.add("contract: " + contract.callee + " is called from " + sites.get(0)
                        + ", not from " + contract.callerName);
            }
        }
        if (!contractFindings.isEmpty()) out.put("contract", contractFindings);
        return out;
    }

    private static Set<String> dexEntries(File apk) throws Exception {
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        return new TreeSet<>(container.getDexEntryNames());
    }

    /** Signature -> "registerCount:bodyHash", for every method of an APK. */
    private static Map<String, String> fingerprintAll(File apk) throws Exception {
        Map<String, String> out = new HashMap<>(1 << 20);
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    MethodImplementation impl = m.getImplementation();
                    StringBuilder body = new StringBuilder();
                    int registers = 0;
                    if (impl != null) {
                        registers = impl.getRegisterCount();
                        for (Instruction i : impl.getInstructions()) body.append(render(i)).append('\n');
                        for (String t : tryBlocks(impl)) body.append(t).append('\n');
                    }
                    digest.reset();
                    byte[] hash = digest.digest(body.toString().getBytes("UTF-8"));
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < 8; k++) hex.append(String.format("%02x", hash[k]));
                    out.put(sig(cd, m), registers + ":" + hex);
                }
            }
        }
        return out;
    }

    /** Signature -> rendered body, for the named methods only. */
    private static Map<String, List<String>> bodiesOf(File apk, Set<String> wanted) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    if (!wanted.contains(s)) continue;
                    List<String> body = new ArrayList<>();
                    MethodImplementation impl = m.getImplementation();
                    if (impl != null) {
                        body.add("# registers=" + impl.getRegisterCount());
                        for (Instruction i : impl.getInstructions()) body.add(render(i));
                        body.addAll(tryBlocks(impl));
                    }
                    out.put(s, body);
                }
            }
        }
        return out;
    }

    /** Each try block as a line, so an exception range that moved is a body that changed. */
    private static List<String> tryBlocks(MethodImplementation impl) {
        List<String> out = new ArrayList<>();
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            StringBuilder b = new StringBuilder("# try start=").append(block.getStartCodeAddress())
                    .append(" units=").append(block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                b.append(" catch(").append(handler.getExceptionType())
                        .append(")->").append(handler.getHandlerCodeAddress());
            }
            out.add(b.toString());
        }
        return out;
    }

    private static String sig(ClassDef cd, Method m) {
        StringBuilder b = new StringBuilder(cd.getType()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    /** One instruction as text: opcode, registers, branch offset, literal, reference, max register. */
    private static String render(Instruction i) {
        StringBuilder b = new StringBuilder(i.getOpcode().name);
        List<String> regs = new ArrayList<>();
        int maxReg = -1;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            regs.add("v" + r.getStartRegister() + "..v" + (r.getStartRegister() + r.getRegisterCount() - 1));
            maxReg = r.getStartRegister() + r.getRegisterCount() - 1;
        } else if (i instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction r = (FiveRegisterInstruction) i;
            int n = r.getRegisterCount();
            int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
            for (int k = 0; k < n; k++) { regs.add("v" + all[k]); maxReg = Math.max(maxReg, all[k]); }
        } else if (i instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            regs.add("v" + r.getRegisterC());
            maxReg = Math.max(r.getRegisterA(), Math.max(r.getRegisterB(), r.getRegisterC()));
        } else if (i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            maxReg = Math.max(r.getRegisterA(), r.getRegisterB());
        } else if (i instanceof OneRegisterInstruction) {
            regs.add("v" + ((OneRegisterInstruction) i).getRegisterA());
            maxReg = ((OneRegisterInstruction) i).getRegisterA();
        }
        // A wide destination names its low half and occupies the pair, so it reaches one higher
        // than it says. Only the destination: dexlib2 does not describe which sources are wide,
        // and guessing there would fail a valid narrow destination sitting above a wide source.
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction) {
            maxReg = Math.max(maxReg, ((OneRegisterInstruction) i).getRegisterA() + 1);
        }
        if (!regs.isEmpty()) b.append(' ').append(String.join(", ", regs));
        if (i instanceof OffsetInstruction) {
            b.append(", ").append(String.format("%+d", ((OffsetInstruction) i).getCodeOffset()));
        }
        if (i instanceof WideLiteralInstruction) {
            b.append(", #").append(((WideLiteralInstruction) i).getWideLiteral());
        }
        if (i instanceof ReferenceInstruction) {
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r != null) b.append(", ").append(r);
        }
        // A payload's cases and values are part of the body: a case moved to another target
        // changed nothing else, and left out of the text, the method read as untouched.
        if (i instanceof SwitchPayload) {
            for (SwitchElement e : ((SwitchPayload) i).getSwitchElements()) {
                b.append(' ').append(e.getKey()).append("->").append(String.format("%+d", e.getOffset()));
            }
        }
        if (i instanceof ArrayPayload) {
            b.append(" width=").append(((ArrayPayload) i).getElementWidth());
            for (Number n : ((ArrayPayload) i).getArrayElements()) b.append(' ').append(n);
        }
        return b.append(" |maxreg=").append(maxReg).toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            System.err.println("usage: DexDiff <cleanApk> <patchedApk> <reportFile> <removalAllowlist> [<contracts>]");
            System.exit(2);
        }
        File clean = new File(args[0]);
        File patched = new File(args[1]);
        File allowlistFile = new File(args[3]);
        RemovalAllowlist allowlist = readRemovalAllowlist(allowlistFile);
        File contractFile = args.length > 4 ? new File(args[4]) : null;
        List<Contract> contracts = readContracts(contractFile);

        System.out.println("[diff] fingerprinting clean " + clean.getName());
        Map<String, String> before = fingerprintAll(clean);
        Set<String> beforeDexEntries = dexEntries(clean);
        System.out.println("[diff] " + before.size() + " methods");
        System.out.println("[diff] fingerprinting patched " + patched.getName());
        Map<String, String> after = fingerprintAll(patched);
        Set<String> afterDexEntries = dexEntries(patched);
        System.out.println("[diff] " + after.size() + " methods");

        Set<String> changed = new TreeSet<>();
        Set<String> added = new TreeSet<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String was = before.get(e.getKey());
            if (was == null) added.add(e.getKey());
            else if (!was.equals(e.getValue())) changed.add(e.getKey());
        }
        Set<String> removed = new TreeSet<>();
        for (String k : before.keySet()) if (!after.containsKey(k)) removed.add(k);
        Set<String> removedDexEntries = new TreeSet<>(beforeDexEntries);
        removedDexEntries.removeAll(afterDexEntries);

        Set<String> rejectedRemoved = new TreeSet<>(removed);
        rejectedRemoved.removeAll(allowlist.methods);
        Set<String> rejectedDexEntries = new TreeSet<>(removedDexEntries);
        rejectedDexEntries.removeAll(allowlist.dexEntries);
        Set<String> staleAllowedMethods = new TreeSet<>(allowlist.methods);
        staleAllowedMethods.removeAll(removed);
        Set<String> staleAllowedDexEntries = new TreeSet<>(allowlist.dexEntries);
        staleAllowedDexEntries.removeAll(removedDexEntries);

        Set<String> ownAdded = new TreeSet<>();
        for (String s : added) if (s.startsWith(OWN)) ownAdded.add(s);

        System.out.println("[diff] host methods changed: " + changed.size());
        System.out.println("[diff] methods added: " + added.size()
                + " (" + ownAdded.size() + " under " + OWN + ")");
        System.out.println("[diff] methods removed: " + removed.size()
                + " (rejected " + rejectedRemoved.size() + ")");
        System.out.println("[diff] DEX entries removed: " + removedDexEntries.size()
                + " (rejected " + rejectedDexEntries.size() + ")");

        // A pair of files with nothing between them is not a clean bill of health, it is the wrong
        // pair of files. Both of these were reachable by pointing the run at one APK twice.
        int problems = 0;
        if (changed.isEmpty()) {
            System.out.println("[diff] FAIL: no host method differs, so these two APKs are not a "
                    + "clean build and a patched build of it.");
            problems++;
        }
        if (ownAdded.isEmpty()) {
            System.out.println("[diff] FAIL: the patched APK carries no method under " + OWN
                    + ", so no extension code was added to it.");
            problems++;
        }
        if (!rejectedRemoved.isEmpty()) {
            System.out.println("[diff] FAIL: removed host methods are not allowed:");
            for (String method : rejectedRemoved) System.out.println("[diff]   " + method);
            problems += rejectedRemoved.size();
        }
        if (!rejectedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: removed DEX entries are not allowed:");
            for (String entry : rejectedDexEntries) System.out.println("[diff]   " + entry);
            problems += rejectedDexEntries.size();
        }
        if (!staleAllowedMethods.isEmpty() || !staleAllowedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: the removal allowlist contains entries this pair did not remove:");
            for (String method : staleAllowedMethods) System.out.println("[diff]   method " + method);
            for (String entry : staleAllowedDexEntries) System.out.println("[diff]   dex " + entry);
            problems += staleAllowedMethods.size() + staleAllowedDexEntries.size();
        }

        System.out.println("[diff] reading both bodies for the changed and added methods");
        Set<String> wanted = new TreeSet<>(changed);
        wanted.addAll(ownAdded);
        Map<String, List<String>> beforeBodies = bodiesOf(clean, changed);
        Map<String, List<String>> afterBodies = bodiesOf(patched, wanted);

        // Every method the patch wrote or touched, the host's and the bundle's alike.
        System.out.println("[diff] checking branches, invokes, parameters and try ranges of "
                + (changed.size() + added.size()) + " methods");
        Set<String> structuralWanted = new TreeSet<>(changed);
        structuralWanted.addAll(added);
        Map<String, List<String>> structural = structuralPass(patched, structuralWanted, contracts);
        int structuralCount = 0;
        for (Map.Entry<String, List<String>> e : structural.entrySet()) {
            for (String finding : e.getValue()) {
                structuralCount++;
                System.out.println("[diff] FAIL: " + finding
                        + (e.getKey().equals("contract") ? "" : "  in " + e.getKey()));
            }
        }
        System.out.println("[diff] structural findings: " + structuralCount);
        problems += structuralCount;

        PrintWriter report = new PrintWriter(args[2], "UTF-8");
        try {
            report.println("Methods a patched APK does not share with the clean build it came from.");
            report.println("clean:   " + clean.getAbsolutePath());
            report.println("patched: " + patched.getAbsolutePath());
            report.println("removal allowlist: " + allowlistFile.getAbsolutePath());
            report.println("contracts: " + (contractFile == null ? "none" : contractFile.getAbsolutePath()));
            report.println("changed=" + changed.size() + " added=" + added.size()
                    + " (own=" + ownAdded.size() + ") removed=" + removed.size()
                    + " removedDex=" + removedDexEntries.size());
            report.println();

            report.println("Structural findings: " + structuralCount);
            for (Map.Entry<String, List<String>> e : structural.entrySet()) {
                report.println("  " + e.getKey());
                for (String finding : e.getValue()) report.println("    FAIL  " + finding);
            }
            report.println();

            report.println("Removed methods:");
            for (String method : removed) {
                report.println((allowlist.methods.contains(method) ? "  allowed " : "  FAIL    ") + method);
            }
            report.println("Removed DEX entries:");
            for (String entry : removedDexEntries) {
                report.println((allowlist.dexEntries.contains(entry) ? "  allowed " : "  FAIL    ") + entry);
            }
            report.println("Stale removal allowlist entries:");
            for (String method : staleAllowedMethods) report.println("  FAIL    method " + method);
            for (String entry : staleAllowedDexEntries) report.println("  FAIL    dex " + entry);
            report.println();

            int overRegister = 0;
            int unreadable = 0;

            for (String s : changed) {
                List<String> b = beforeBodies.getOrDefault(s, List.of());
                List<String> a = afterBodies.getOrDefault(s, List.of());
                int regsBefore = registersOf(b), regsAfter = registersOf(a);
                report.println("==== " + s);
                report.println("     registers " + regsBefore + " -> " + regsAfter
                        + ", instructions " + Math.max(0, b.size() - 1) + " -> " + Math.max(0, a.size() - 1));
                if (regsAfter < 0 && !a.isEmpty()) {
                    // Nothing to hold the injected lines to. Silently skipping this was a hole:
                    // any method the second pass failed to render passed the check by default.
                    report.println("  !  no register count could be read for this method");
                    unreadable++;
                }
                List<String> onlyAfter = minus(a, b);
                for (String line : minus(b, a)) report.println("  -  " + line);
                for (String line : onlyAfter) {
                    int high = highestRegister(line);
                    boolean bad = high >= 0 && regsAfter >= 0 && high >= regsAfter;
                    if (bad) overRegister++;
                    report.println("  +  " + line + (bad ? "   <<< REGISTER >= registerCount" : ""));
                }
                report.println();
            }

            // The bundle's own methods are where registers are chosen by hand rather than reused
            // from the host, which is exactly where an out-of-range one would come from.
            for (String s : ownAdded) {
                List<String> a = afterBodies.getOrDefault(s, List.of());
                int regsAfter = registersOf(a);
                if (a.isEmpty()) continue;
                List<String> offending = new ArrayList<>();
                for (String line : a) {
                    if (line.startsWith("#")) continue;
                    int high = highestRegister(line);
                    if (high >= 0 && regsAfter >= 0 && high >= regsAfter) offending.add(line);
                }
                if (regsAfter < 0) {
                    report.println("==== added " + s);
                    report.println("  !  no register count could be read for this method");
                    unreadable++;
                }
                if (offending.isEmpty()) continue;
                overRegister += offending.size();
                report.println("==== added " + s);
                report.println("     registers " + regsAfter);
                for (String line : offending) {
                    report.println("  +  " + line + "   <<< REGISTER >= registerCount");
                }
                report.println();
            }

            report.println("Lines naming a register at or above the method's register count: " + overRegister);
            report.println("Methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] injected lines naming an out-of-range register: " + overRegister);
            if (unreadable != 0) problems++;
            if (overRegister != 0) problems++;
        } finally {
            report.close();
        }
        System.out.println("[diff] report written to " + args[2]);
        System.exit(problems == 0 ? 0 : 1);
    }

    /**
     * The register count the body declares, or -1 when the body was not read at all.
     *
     * <p>Zero is a real answer: a static method with no arguments and no locals declares no
     * registers, and R8's synthetic lambda bridges are full of them. Treating zero as "unknown"
     * flagged twenty-one of the bundle's own methods as unreadable when nothing was wrong.
     */
    private static int registersOf(List<String> body) {
        if (body.isEmpty() || !body.get(0).startsWith("# registers=")) return -1;
        return Integer.parseInt(body.get(0).substring("# registers=".length()));
    }

    /** Lines of a that are not in b, counting duplicates. */
    private static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> pool = new HashMap<>();
        for (String s : b) pool.merge(s, 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (String s : a) {
            Integer left = pool.get(s);
            if (left != null && left > 0) pool.put(s, left - 1);
            else if (!s.startsWith("# registers=")) out.add(s);
        }
        return out;
    }

    /** The highest register the instruction touched, read back from the marker render() wrote. */
    private static int highestRegister(String line) {
        int at = line.lastIndexOf("|maxreg=");
        if (at < 0) return -1;
        return Integer.parseInt(line.substring(at + "|maxreg=".length()).trim());
    }
}
