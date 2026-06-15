import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FindDexStringRefs {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: FindDexStringRefs <dex-dir|dex|apk> <contains> [contains...]");
        }

        File input = new File(args[0]);
        List<String> needles = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            needles.add(args[i].toLowerCase(Locale.ROOT));
        }

        for (DexFile dex : loadDexFiles(input)) {
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
                            if (ref instanceof StringReference) {
                                String value = ((StringReference) ref).getString();
                                String lower = value.toLowerCase(Locale.ROOT);
                                for (String needle : needles) {
                                    if (lower.contains(needle)) {
                                        System.out.println(classDef.getType() + "->" + method.getName()
                                                + method.getParameterTypes() + method.getReturnType()
                                                + " [" + index + "] \"" + value + "\"");
                                        break;
                                    }
                                }
                            }
                        }
                        index++;
                    }
                }
            }
        }
    }

    private static List<DexFile> loadDexFiles(File input) throws Exception {
        List<DexFile> dexFiles = new ArrayList<>();
        if (input.isDirectory()) {
            File[] files = input.listFiles((dir, name) -> name.matches("classes\\d*\\.dex"));
            if (files != null) {
                for (File file : files) {
                    dexFiles.add(DexFileFactory.loadDexFile(file, null));
                }
            }
            return dexFiles;
        }
        if (input.getName().endsWith(".apk") || input.getName().endsWith(".zip")) {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"), "find-dex-string-refs-" + System.nanoTime());
            if (!tmpDir.mkdirs()) {
                throw new IllegalStateException("failed to create temp dir: " + tmpDir);
            }
            try (ZipFile zip = new ZipFile(input)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.matches("classes\\d*\\.dex")) {
                        continue;
                    }
                    File dexFile = new File(tmpDir, name);
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(dexFile)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = in.read(buffer)) >= 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    dexFiles.add(DexFileFactory.loadDexFile(dexFile, null));
                }
            }
            return dexFiles;
        }
        dexFiles.add(DexFileFactory.loadDexFile(input, null));
        return dexFiles;
    }
}
