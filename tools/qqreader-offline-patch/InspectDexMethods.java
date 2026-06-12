import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InspectDexMethods {
    private static final String[] TARGETS = {
            "Lcom/qq/reader/ReaderApplication;->initLoginSDK",
            "Lcom/qq/reader/ReaderApplication;->initPushSDK",
            "Lcom/qq/reader/shortcut/ShortcutManager;->cihai",
            "Lcom/qq/reader/abtest_sdk/qdab;->cihai",
            "Lcom/qq/reader/common/utils/qdbd;->search",
            "Lcom/qq/reader/common/utils/qdcg;->search",
            "Lcom/qq/reader/common/utils/qdeb;->search",
            "Lcom/qq/reader/common/utils/ae;->search",
            "Lcom/qq/reader/plugin/qdbh;->search",
            "Lcom/qq/reader/qrlightdark/LightDarkStatusManager;->search",
            "Lcom/qq/reader/view/qded;->search",
            "Lcom/qq/reader/view/qded;->b",
            "Lcom/qq/reader/common/receiver/WXBroadcastReceiver;->search",
            "Lcom/bytedance/android/dy/sdk/pangle/ZeusPlatformUtils;->initZeus",
            "Lcom/yuewen/fock/Fock;->sign"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: InspectDexMethods <apk> <out-dir>");
        }

        File apk = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();

        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".dex")) {
                    continue;
                }

                File dexFile = new File(outDir, entry.getName().replace('/', '_'));
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(dexFile)) {
                    in.transferTo(out);
                }
                inspect(entry.getName(), dexFile);
            }
        }
    }

    private static void inspect(String dexName, File dexFile) throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(dexFile, null);
        for (ClassDef classDef : dex.getClasses()) {
            for (String target : TARGETS) {
                String classType = target.substring(0, target.indexOf("->"));
                String methodName = target.substring(target.indexOf("->") + 2);
                if (!classDef.getType().equals(classType)) {
                    continue;
                }

                for (Method method : classDef.getMethods()) {
                    if (!method.getName().equals(methodName)) {
                        continue;
                    }

                    System.out.println("FOUND " + dexName + " " + classDef.getType()
                            + "->" + method.getName()
                            + method.getParameterTypes()
                            + method.getReturnType());

                    if (method.getImplementation() == null) {
                        System.out.println("  implementation=null");
                        continue;
                    }

                    System.out.println("  registers=" + method.getImplementation().getRegisterCount());
                    int index = 0;
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        System.out.println("  [" + index + "] " + instruction.getOpcode());
                        index++;
                        if (index >= 8) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
