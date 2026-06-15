import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DumpDexClassVerbose {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DumpDexClassVerbose <dex> <class-type>");
        }

        List<DexFile> dexFiles = loadDexFiles(new File(args[0]));
        String target = args[1];
        for (DexFile dex : dexFiles) {
            for (ClassDef classDef : dex.getClasses()) {
                if (!classDef.getType().equals(target)) {
                    continue;
                }

                System.out.println("CLASS " + classDef.getType());
                System.out.println("SUPER " + classDef.getSuperclass());
                for (Field field : classDef.getFields()) {
                    System.out.println("FIELD " + field.getType() + " " + field.getName()
                            + " initial=" + field.getInitialValue());
                }
                for (Method method : classDef.getMethods()) {
                    System.out.println("METHOD " + method.getName()
                            + method.getParameterTypes() + method.getReturnType());
                    if (method.getImplementation() == null) {
                        System.out.println("  implementation=null");
                        continue;
                    }
                    System.out.println("  registers=" + method.getImplementation().getRegisterCount());
                    int index = 0;
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        StringBuilder line = new StringBuilder("  [")
                                .append(index)
                                .append("] ")
                                .append(instruction.getOpcode());
                        if (instruction instanceof ReferenceInstruction) {
                            line.append(" ref=").append(((ReferenceInstruction) instruction).getReference());
                        }
                        if (instruction instanceof OneRegisterInstruction) {
                            line.append(" rA=").append(((OneRegisterInstruction) instruction).getRegisterA());
                        }
                        if (instruction instanceof TwoRegisterInstruction) {
                            line.append(" rB=").append(((TwoRegisterInstruction) instruction).getRegisterB());
                        }
                        if (instruction instanceof ThreeRegisterInstruction) {
                            line.append(" rC=").append(((ThreeRegisterInstruction) instruction).getRegisterC());
                        }
                        if (instruction instanceof RegisterRangeInstruction) {
                            line.append(" start=").append(((RegisterRangeInstruction) instruction).getStartRegister())
                                    .append(" count=").append(((RegisterRangeInstruction) instruction).getRegisterCount());
                        }
                        if (instruction instanceof FiveRegisterInstruction) {
                            FiveRegisterInstruction five = (FiveRegisterInstruction) instruction;
                            line.append(" count=").append(five.getRegisterCount())
                                    .append(" c=").append(five.getRegisterC())
                                    .append(" d=").append(five.getRegisterD())
                                    .append(" e=").append(five.getRegisterE())
                                    .append(" f=").append(five.getRegisterF())
                                    .append(" g=").append(five.getRegisterG());
                        }
                        if (instruction instanceof NarrowLiteralInstruction) {
                            line.append(" narrow=").append(((NarrowLiteralInstruction) instruction).getNarrowLiteral());
                        }
                        if (instruction instanceof WideLiteralInstruction) {
                            line.append(" wide=").append(((WideLiteralInstruction) instruction).getWideLiteral());
                        }
                        if (instruction instanceof OffsetInstruction) {
                            line.append(" offset=").append(((OffsetInstruction) instruction).getCodeOffset());
                        }
                        System.out.println(line);
                        index++;
                    }
                }
                return;
            }
        }

        System.out.println("CLASS_NOT_FOUND " + target);
    }

    private static List<DexFile> loadDexFiles(File input) throws Exception {
        List<DexFile> dexFiles = new ArrayList<>();
        if (input.isDirectory()) {
            File[] files = input.listFiles((dir, name) -> name.endsWith(".dex"));
            if (files != null) {
                for (File file : files) {
                    dexFiles.add(DexFileFactory.loadDexFile(file, null));
                }
            }
            return dexFiles;
        }
        if (input.getName().endsWith(".apk") || input.getName().endsWith(".zip")) {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"), "dump-dex-class-" + System.nanoTime());
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
