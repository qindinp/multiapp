import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;

import java.io.File;

public class FindDexMethodRefs {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: FindDexMethodRefs <dex> <owner-prefix> [method-name]");
        }

        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        String ownerPrefix = args[1];
        String methodName = args.length >= 3 ? args[2] : null;

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
                        if (ref instanceof MethodReference) {
                            MethodReference mr = (MethodReference) ref;
                            boolean ownerMatches = mr.getDefiningClass().startsWith(ownerPrefix);
                            boolean nameMatches = methodName == null || mr.getName().equals(methodName);
                            if (ownerMatches && nameMatches) {
                                System.out.println(classDef.getType() + "->" + method.getName()
                                        + method.getParameterTypes() + method.getReturnType()
                                        + " [" + index + "] " + mr.getDefiningClass()
                                        + "->" + mr.getName() + mr.getParameterTypes() + mr.getReturnType());
                            }
                        }
                    }
                    index++;
                }
            }
        }
    }
}
