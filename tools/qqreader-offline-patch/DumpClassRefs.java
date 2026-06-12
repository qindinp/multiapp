import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;

import java.io.File;

public class DumpClassRefs {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DumpClassRefs <dex> <class-type>");
        }
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        String classType = args[1];
        for (ClassDef classDef : dex.getClasses()) {
            if (!classDef.getType().equals(classType)) {
                continue;
            }
            for (Method method : classDef.getMethods()) {
                System.out.println("METHOD " + method.getName()
                        + method.getParameterTypes()
                        + method.getReturnType());
                if (method.getImplementation() == null) {
                    continue;
                }
                int index = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    String line = "  [" + index + "] " + instruction.getOpcode();
                    if (instruction instanceof ReferenceInstruction) {
                        line += " " + ((ReferenceInstruction) instruction).getReference();
                    }
                    System.out.println(line);
                    index++;
                }
            }
        }
    }
}
