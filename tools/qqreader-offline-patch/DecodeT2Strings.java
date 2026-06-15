import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DecodeT2Strings {
    private static String[] table;

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: DecodeT2Strings <dex> [case-insensitive-filter|key:<long>]");
        }
        String filter = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : null;
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        table = readStringTable(dex);
        System.out.println("TABLE_SIZE " + table.length);
        for (int i = 0; i < table.length; i++) {
            System.out.println("TABLE_ENTRY " + i + " length=" + table[i].length());
        }
        if (filter != null && filter.startsWith("key:")) {
            long key = Long.parseLong(filter.substring("key:".length()));
            System.out.println("KEY " + key + " text=" + printable(decode(key)));
            return;
        }

        for (ClassDef classDef : dex.getClasses()) {
            for (Method method : classDef.getMethods()) {
                MethodImplementation impl = method.getImplementation();
                if (impl == null) {
                    continue;
                }
                Long[] wideRegs = new Long[Math.max(impl.getRegisterCount(), 1)];
                int index = 0;
                for (Instruction instruction : impl.getInstructions()) {
                    if (isConstWide(instruction) && instruction instanceof OneRegisterInstruction
                            && instruction instanceof WideLiteralInstruction) {
                        int reg = ((OneRegisterInstruction) instruction).getRegisterA();
                        if (reg >= 0 && reg < wideRegs.length) {
                            wideRegs[reg] = ((WideLiteralInstruction) instruction).getWideLiteral();
                        }
                    }
                    if (isTargetAssertCall(instruction)) {
                        int reg = firstInvokeRegister(instruction);
                        Long key = reg >= 0 && reg < wideRegs.length ? wideRegs[reg] : null;
                        if (key != null) {
                            try {
                                String decoded = decode(key);
                                if (filter == null || decoded.toLowerCase(Locale.ROOT).contains(filter)) {
                                    System.out.println(classDef.getType() + " " + method.getName()
                                            + method.getParameterTypes() + method.getReturnType()
                                            + " @" + index + " key=" + key + " text=" + printable(decoded));
                                }
                            } catch (RuntimeException ex) {
                                System.out.println(classDef.getType() + " " + method.getName()
                                        + " @" + index + " key=" + key + " ERROR=" + ex.getMessage());
                            }
                        }
                    }
                    index++;
                }
            }
        }
    }

    private static String[] readStringTable(DexFile dex) {
        for (ClassDef classDef : dex.getClasses()) {
            if (!"Lt2/assert;".equals(classDef.getType())) {
                continue;
            }
            Method clinit = null;
            for (Method method : classDef.getMethods()) {
                if ("<clinit>".equals(method.getName())) {
                    clinit = method;
                    break;
                }
            }
            if (clinit == null || clinit.getImplementation() == null) {
                throw new IllegalStateException("Lt2/assert.<clinit> not found");
            }

            int size = -1;
            Map<Integer, Integer> intRegs = new HashMap<>();
            Map<Integer, String> stringRegs = new HashMap<>();
            Map<Integer, String> values = new HashMap<>();
            for (Instruction instruction : clinit.getImplementation().getInstructions()) {
                Opcode op = instruction.getOpcode();
                if (op == Opcode.CONST_4 && instruction instanceof OneRegisterInstruction
                        && instruction instanceof NarrowLiteralInstruction) {
                    intRegs.put(((OneRegisterInstruction) instruction).getRegisterA(),
                            ((NarrowLiteralInstruction) instruction).getNarrowLiteral());
                } else if (op == Opcode.NEW_ARRAY && instruction instanceof TwoRegisterInstruction) {
                    int countReg = ((TwoRegisterInstruction) instruction).getRegisterB();
                    Integer count = intRegs.get(countReg);
                    if (count != null) {
                        size = count;
                    }
                } else if (op == Opcode.CONST_STRING && instruction instanceof OneRegisterInstruction
                        && instruction instanceof ReferenceInstruction) {
                    Reference ref = ((ReferenceInstruction) instruction).getReference();
                    if (ref instanceof StringReference) {
                        stringRegs.put(((OneRegisterInstruction) instruction).getRegisterA(),
                                ((StringReference) ref).getString());
                    }
                } else if (op == Opcode.APUT_OBJECT && instruction instanceof ThreeRegisterInstruction) {
                    ThreeRegisterInstruction three = (ThreeRegisterInstruction) instruction;
                    String text = stringRegs.get(three.getRegisterA());
                    Integer arrayIndex = intRegs.get(three.getRegisterC());
                    if (text != null && arrayIndex != null) {
                        values.put(arrayIndex, text);
                    }
                }
            }
            if (size < 0) {
                throw new IllegalStateException("string table size not found");
            }
            String[] result = new String[size];
            for (int i = 0; i < size; i++) {
                result[i] = values.get(i);
                if (result[i] == null) {
                    throw new IllegalStateException("missing string table entry " + i);
                }
            }
            return result;
        }
        throw new IllegalStateException("Lt2/assert not found");
    }

    private static boolean isConstWide(Instruction instruction) {
        Opcode op = instruction.getOpcode();
        return op == Opcode.CONST_WIDE
                || op == Opcode.CONST_WIDE_16
                || op == Opcode.CONST_WIDE_32
                || op == Opcode.CONST_WIDE_HIGH16;
    }

    private static boolean isTargetAssertCall(Instruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)) {
            return false;
        }
        return "Lt2/assert;->assert(J)Ljava/lang/String;"
                .equals(((ReferenceInstruction) instruction).getReference().toString());
    }

    private static int firstInvokeRegister(Instruction instruction) {
        if (instruction instanceof FiveRegisterInstruction) {
            return ((FiveRegisterInstruction) instruction).getRegisterC();
        }
        if (instruction instanceof RegisterRangeInstruction) {
            return ((RegisterRangeInstruction) instruction).getStartRegister();
        }
        return -1;
    }

    private static String decode(long key) {
        long v0 = 0xffffffffL;
        v0 &= key;
        long v2 = v0 >>> 33;
        v0 ^= v2;
        v0 *= 7109453100751455733L;
        v2 = v0 >>> 28;
        v0 ^= v2;
        v0 *= -3808689974395783757L;
        v0 >>>= 32;
        v0 = mix(v0);
        long v3 = (v0 >>> 32) & 65535L;
        v0 = mix(v0);
        long v7 = (v0 >>> 16) & -65536L;
        long seedLong = (key >>> 32) ^ v3 ^ v7;
        int seed = (int) seedLong;

        v0 = mix(v0);
        int tableIndex = seed / 8191;
        int charIndex = seed % 8191;
        char first = table[tableIndex].charAt(charIndex);
        v0 ^= ((long) first) << 32;
        int length = (int) ((v0 >>> 32) & 65535L);

        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            int nextSeed = seed + i + 1;
            v0 = mix(v0);
            tableIndex = nextSeed / 8191;
            charIndex = nextSeed % 8191;
            char encoded = table[tableIndex].charAt(charIndex);
            v0 ^= ((long) encoded) << 32;
            out[i] = (char) ((v0 >>> 32) & 65535L);
        }
        return new String(out);
    }

    private static long mix(long value) {
        long mask = 65535L;
        int v2 = (short) (int) (value & mask);
        long shifted = value >>> 16;
        int v4 = (short) (int) (shifted & mask);
        int v5 = (short) (v2 + v4);
        v5 = (short) ((v5 << 9) | (v5 >>> 23));
        v5 = (short) (v5 + v2);
        v4 = (short) (v4 ^ v2);
        int v0 = (short) ((v2 << 13) | (v2 >>> 19));
        v0 = (short) (v0 ^ v4);
        v0 = (short) (v0 ^ (v4 << 5));
        v4 = (short) ((v4 << 10) | (v4 >>> 22));
        long result = (long) v5;
        result <<= 16;
        result |= (long) v4;
        result <<= 16;
        result |= (long) v0;
        return result;
    }

    private static String printable(String text) {
        return text.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
