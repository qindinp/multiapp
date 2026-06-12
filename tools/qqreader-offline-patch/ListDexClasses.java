import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;

import java.io.File;

public class ListDexClasses {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: ListDexClasses <dex> [contains]");
        }
        String contains = args.length == 2 ? args[1].toLowerCase() : "";
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        for (ClassDef classDef : dex.getClasses()) {
            String type = classDef.getType();
            if (!contains.isEmpty() && !type.toLowerCase().contains(contains)) {
                boolean methodMatch = false;
                for (Method method : classDef.getMethods()) {
                    if (method.getName().toLowerCase().contains(contains)) {
                        methodMatch = true;
                        break;
                    }
                }
                if (!methodMatch) {
                    continue;
                }
            }
            System.out.println(type);
            for (Method method : classDef.getMethods()) {
                if (contains.isEmpty()
                        || type.toLowerCase().contains(contains)
                        || method.getName().toLowerCase().contains(contains)) {
                    System.out.println("  " + method.getName()
                            + method.getParameterTypes()
                            + method.getReturnType());
                }
            }
        }
    }
}
