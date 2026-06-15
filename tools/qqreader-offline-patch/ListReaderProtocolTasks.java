import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ListReaderProtocolTasks {
    private static final String TARGET = "Lcom/yuewen/component/businesstask/ordinal/ReaderProtocolTask;";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ListReaderProtocolTasks <apk-or-dex>");
        }

        File input = new File(args[0]);
        List<DexFile> dexFiles = new ArrayList<>();
        if (input.isDirectory()) {
            File[] files = input.listFiles((dir, name) -> name.endsWith(".dex"));
            if (files != null) {
                for (File file : files) {
                    dexFiles.add(DexFileFactory.loadDexFile(file, null));
                }
            }
        } else if (input.getName().endsWith(".apk") || input.getName().endsWith(".zip")) {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"), "list-reader-protocol-" + System.nanoTime());
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
        } else {
            dexFiles.add(DexFileFactory.loadDexFile(input, null));
        }

        Map<String, ClassDef> classes = new HashMap<>();
        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                classes.put(classDef.getType(), classDef);
            }
        }

        for (ClassDef classDef : classes.values()) {
            if (!inheritsFrom(classDef, classes, TARGET, new HashSet<>())) {
                continue;
            }
            if (classDef.getType().equals(TARGET)) {
                continue;
            }

            List<String> ctors = new ArrayList<>();
            List<String> overrides = new ArrayList<>();
            List<String> interestingRefs = new ArrayList<>();
            for (Method method : classDef.getMethods()) {
                if ("<init>".equals(method.getName())) {
                    ctors.add(method.getParameterTypes().toString());
                }
                if (isInterestingOverride(method)) {
                    overrides.add(method.getName() + method.getParameterTypes() + method.getReturnType());
                }
                if (method.getImplementation() != null) {
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        if (instruction instanceof ReferenceInstruction) {
                            String ref = String.valueOf(((ReferenceInstruction) instruction).getReference());
                            if (ref.contains("ChapBatAuthWithPD")
                                    || ref.contains("newminerva")
                                    || ref.contains("ctebchaptercosurl")
                                    || ref.contains("epubPureUrl")
                                    || ref.contains("epubResourceUrl")
                                    || ref.contains("OnlineChapterDownloadTask")
                                    || ref.contains("ReadOnline")) {
                                interestingRefs.add(method.getName() + " -> " + ref);
                            }
                        }
                    }
                }
            }

            if (!overrides.isEmpty() || !interestingRefs.isEmpty()) {
                System.out.println("CLASS " + classDef.getType());
                System.out.println("  SUPER " + classDef.getSuperclass());
                System.out.println("  CTORS " + ctors);
                System.out.println("  OVERRIDES " + overrides);
                for (String ref : interestingRefs) {
                    System.out.println("  REF " + ref);
                }
            }
        }
    }

    private static boolean isInterestingOverride(Method method) {
        String name = method.getName();
        return "getUrl".equals(name)
                || "getRequestContent".equals(name)
                || "refreshHeader".equals(name)
                || "getApplicationInterceptor".equals(name)
                || "getNetworkInterceptor".equals(name)
                || "getContentType".equals(name)
                || "getRequestMethod".equals(name);
    }

    private static boolean inheritsFrom(
            ClassDef classDef,
            Map<String, ClassDef> classes,
            String target,
            Set<String> seen) {
        String type = classDef.getType();
        if (!seen.add(type)) return false;
        String parent = classDef.getSuperclass();
        if (parent == null) return false;
        if (target.equals(parent)) return true;
        ClassDef parentDef = classes.get(parent);
        return parentDef != null && inheritsFrom(parentDef, classes, target, seen);
    }
}
