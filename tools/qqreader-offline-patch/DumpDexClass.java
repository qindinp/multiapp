import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction21c;

import java.io.File;

public class DumpDexClass {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DumpDexClass <dex> <class-type>");
        }

        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        String target = args[1];
        for (ClassDef classDef : dex.getClasses()) {
            if (!classDef.getType().equals(target)) {
                continue;
            }

            System.out.println("CLASS " + classDef.getType());
            System.out.println("SUPER " + classDef.getSuperclass());
            for (Field field : classDef.getFields()) {
                System.out.println("FIELD " + field.getType() + " " + field.getName()
                        + " initial=" + field.getInitialValue());
            }
            for (Method method : classDef.getMethods()) {
                System.out.println("METHOD " + method.getName()
                        + method.getParameterTypes() + method.getReturnType());
                if (method.getImplementation() == null) {
                    System.out.println("  implementation=null");
                    continue;
                }
                System.out.println("  registers=" + method.getImplementation().getRegisterCount());
                int index = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    String line = "  [" + index + "] " + instruction.getOpcode();
                    if (instruction instanceof ReferenceInstruction) {
                        line += " ref=" + ((ReferenceInstruction) instruction).getReference();
                    }
                    if (instruction instanceof Instruction21c) {
                        line += " r=" + ((Instruction21c) instruction).getRegisterA();
                    }
                    System.out.println(line);
                    index++;
                }
            }
            return;
        }

        System.out.println("CLASS_NOT_FOUND " + target);
    }
}
