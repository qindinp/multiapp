import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.Reference;

import java.io.File;

public class FindDexFieldRefs {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("usage: FindDexFieldRefs <dex> <owner-prefix> [field-name]");
        }

        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        String ownerPrefix = args[1];
        String fieldName = args.length == 3 ? args[2] : null;

        for (ClassDef classDef : dex.getClasses()) {
            for (Method method : classDef.getMethods()) {
                MethodImplementation impl = method.getImplementation();
                if (impl == null) {
                    continue;
                }
                int index = 0;
                for (Instruction instruction : impl.getInstructions()) {
                    if (instruction instanceof ReferenceInstruction) {
                        Reference ref = ((ReferenceInstruction) instruction).getReference();
                        if (ref instanceof FieldReference) {
                            FieldReference fr = (FieldReference) ref;
                            boolean ownerMatches = fr.getDefiningClass().startsWith(ownerPrefix);
                            boolean nameMatches = fieldName == null || fr.getName().equals(fieldName);
                            if (ownerMatches && nameMatches) {
                                System.out.println(classDef.getType() + "->" + method.getName()
                                        + method.getParameterTypes() + method.getReturnType()
                                        + " [" + index + "] " + fr.getDefiningClass()
                                        + "->" + fr.getName() + ":" + fr.getType());
                            }
                        }
                    }
                    index++;
                }
            }
        }
    }
}
