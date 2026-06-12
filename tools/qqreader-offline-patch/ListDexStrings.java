import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.util.TreeSet;

public class ListDexStrings {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: ListDexStrings <dex> [contains]");
        }
        String contains = args.length == 2 ? args[1].toLowerCase() : "";
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        TreeSet<String> strings = new TreeSet<>();
        for (ClassDef classDef : dex.getClasses()) {
            strings.add(classDef.getType());
            for (Method method : classDef.getMethods()) {
                MethodImplementation implementation = method.getImplementation();
                if (implementation == null) {
                    continue;
                }
                for (Instruction instruction : implementation.getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) {
                        continue;
                    }
                    Object ref = ((ReferenceInstruction) instruction).getReference();
                    if (ref instanceof StringReference) {
                        strings.add(((StringReference) ref).getString());
                    } else {
                        strings.add(ref.toString());
                    }
                }
            }
        }
        for (String value : strings) {
            if (contains.isEmpty() || value.toLowerCase().contains(contains)) {
                System.out.println(value);
            }
        }
    }
}
