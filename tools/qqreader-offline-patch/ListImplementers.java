import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ListImplementers {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: ListImplementers <apk-or-dex-dir> <interface-type>");
        }
        String target = args[1];
        for (DexFile dexFile : loadDexFiles(new File(args[0]))) {
            for (ClassDef classDef : dexFile.getClasses()) {
                for (String iface : classDef.getInterfaces()) {
                    if (target.equals(iface)) {
                        System.out.println(classDef.getType() + " SUPER " + classDef.getSuperclass());
                    }
                }
            }
        }
    }

    private static List<DexFile> loadDexFiles(File input) throws Exception {
        List<DexFile> dexFiles = new ArrayList<>();
        if (input.isDirectory()) {
            File[] files = input.listFiles((dir, name) -> name.endsWith(".dex"));
            if (files != null) {
                for (File file : files) dexFiles.add(DexFileFactory.loadDexFile(file, null));
            }
            return dexFiles;
        }
        if (input.getName().endsWith(".apk") || input.getName().endsWith(".zip")) {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"), "list-implementers-" + System.nanoTime());
            if (!tmpDir.mkdirs()) throw new IllegalStateException("failed to create temp dir: " + tmpDir);
            try (ZipFile zip = new ZipFile(input)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.matches("classes\\d*\\.dex")) continue;
                    File dexFile = new File(tmpDir, name);
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(dexFile)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
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
