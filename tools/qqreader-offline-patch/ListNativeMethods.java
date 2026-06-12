import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;

import java.io.File;

public class ListNativeMethods {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: ListNativeMethods <dex> [class-type]");
        }

        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        String targetClass = args.length == 2 ? args[1] : null;
        for (ClassDef classDef : dex.getClasses()) {
            if (targetClass != null && !classDef.getType().equals(targetClass)) {
                continue;
            }
            for (Method method : classDef.getMethods()) {
                if ((method.getAccessFlags() & AccessFlags.NATIVE.getValue()) == 0) {
                    continue;
                }
                System.out.println(classDef.getType() + "->" + method.getName()
                        + method.getParameterTypes() + method.getReturnType()
                        + " flags=0x" + Integer.toHexString(method.getAccessFlags()));
            }
        }
    }
}
