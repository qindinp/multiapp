import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.HiddenApiRestriction;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21s;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableField;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NeutralizeDex {
    private static final String NATIVE_LIB_LOADER_TYPE = "Lcom/multiapp/NativeLibLoader;";
    private static final String SIGN_COMPAT_TYPE = "Lcom/multiapp/core/loader/QqReaderSignCompat;";
    private static final String QQ_READER_R_ARRAY_TYPE = "Lcom/qq/reader/R$array;";
    private static ClassDef signCompatClass;
    private static boolean patchedSignCompatInCurrentDex;

    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "Lcom/qq/reader/ReaderApplication;->initPushSDK",
            "Lcom/qq/reader/ReaderApplication;->initOaidSo",
            "Lcom/qq/reader/shortcut/ShortcutManager;->cihai",
            "Lcom/qq/reader/abtest_sdk/qdab;->cihai",
            "Lcom/qq/reader/view/qded;->search",
            "Lcom/qq/reader/view/qded;->judian",
            "Lcom/qq/reader/view/qded;->cihai",
            "Lcom/qq/reader/view/qded;->b",
            "Lcom/qq/reader/qrlogger/qded;->cihai",
            "Lcom/qq/reader/view/dialog/judian/qdab;->A",
            "Lcom/bytedance/android/dy/sdk/pangle/ZeusPlatformUtils;->initZeus"
    ));

    private static final Set<String> ALLOW_SELF_RETURN_NULL = new HashSet<>(Arrays.asList());

    public static void main(String[] args) throws Exception {
        int total = 0;
        for (String path : args) {
            File dexPath = new File(path);
            DexFile dex = DexFileFactory.loadDexFile(dexPath, (Opcodes) null);
            Opcodes opcodes = dex.getOpcodes();
            List<ClassDef> classes = new ArrayList<>();
            boolean modified = false;
            boolean nativeLibLoaderInjected = false;
            boolean signCompatPresent = false;
            boolean stubRArrayPresent = false;
            ClassDef qqReaderRArray = null;
            patchedSignCompatInCurrentDex = false;

            for (ClassDef classDef : dex.getClasses()) {
                if (NATIVE_LIB_LOADER_TYPE.equals(classDef.getType())) {
                    nativeLibLoaderInjected = true;
                }
                if (SIGN_COMPAT_TYPE.equals(classDef.getType())) {
                    signCompatPresent = true;
                }
                String stubRArrayType = stubRArrayType();
                if (stubRArrayType != null && stubRArrayType.equals(classDef.getType())) {
                    stubRArrayPresent = true;
                }
                if (QQ_READER_R_ARRAY_TYPE.equals(classDef.getType())) {
                    qqReaderRArray = classDef;
                }

                List<Method> directMethods = new ArrayList<>();
                List<Method> virtualMethods = new ArrayList<>();
                boolean classModified = false;

                for (Method method : classDef.getDirectMethods()) {
                    Method patched = maybePatchMethod(dexPath, classDef, method);
                    directMethods.add(patched);
                    if (patched != method) {
                        classModified = true;
                        modified = true;
                        total++;
                    }
                }

                for (Method method : classDef.getVirtualMethods()) {
                    Method patched = maybePatchMethod(dexPath, classDef, method);
                    virtualMethods.add(patched);
                    if (patched != method) {
                        classModified = true;
                        modified = true;
                        total++;
                    }
                }

                classes.add(classModified
                        ? new ClassDefWrapper(classDef, directMethods, virtualMethods)
                        : classDef);
            }

            if (patchedSignCompatInCurrentDex && !signCompatPresent) {
                classes.add(loadSignCompatClass());
                modified = true;
                total++;
                System.out.println("injected QqReaderSignCompat " + dexPath.getName());
            }

            if (!nativeLibLoaderInjected) {
                classes.add(createNativeLibLoaderClass());
                nativeLibLoaderInjected = true;
                modified = true;
                total++;
                System.out.println("injected NativeLibLoader " + dexPath.getName());
            }

            String stubRArrayType = stubRArrayType();
            if (stubRArrayType != null && !stubRArrayPresent && qqReaderRArray != null) {
                classes.add(createRArrayAliasClass(qqReaderRArray, stubRArrayType));
                modified = true;
                total++;
                System.out.println("injected stub R$array alias " + dexPath.getName() + " " + stubRArrayType);
            }

            if (modified) {
                File tmp = new File(dexPath.getParentFile(), dexPath.getName() + ".tmp");
                DexPool pool = new DexPool(opcodes);
                for (ClassDef classDef : classes) {
                    pool.internClass(classDef);
                }
                pool.writeTo(new FileDataStore(tmp));
                if (!dexPath.delete()) {
                    throw new IllegalStateException("delete failed: " + dexPath);
                }
                if (!tmp.renameTo(dexPath)) {
                    throw new IllegalStateException("rename failed: " + dexPath);
                }
                System.out.println("wrote " + dexPath.getName());
            }
        }
        System.out.println("total=" + total);
    }

    private static ClassDef createNativeLibLoaderClass() {
        MutableMethodImplementation impl = new MutableMethodImplementation(1);
        ImmutableMethodReference loadLibrary = new ImmutableMethodReference(
                "Ljava/lang/System;",
                "loadLibrary",
                Collections.singletonList("Ljava/lang/String;"),
                "V");
        impl.addInstruction(0, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC,
                1, 0, 0, 0, 0, 0,
                loadLibrary));
        impl.addInstruction(1, new BuilderInstruction10x(Opcode.RETURN_VOID));

        ImmutableMethod method = new ImmutableMethod(
                NATIVE_LIB_LOADER_TYPE,
                "loadLibrary",
                Collections.singletonList(new ImmutableMethodParameter("Ljava/lang/String;", null, null)),
                "V",
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(),
                null,
                null,
                impl);

        return new ImmutableClassDef(
                NATIVE_LIB_LOADER_TYPE,
                AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(),
                "Ljava/lang/Object;",
                null,
                null,
                null,
                null,
                Collections.singletonList(method));
    }

    private static String stubRArrayType() {
        String stubPackage = System.getProperty("multiapp.stubPackage", "").trim();
        if (stubPackage.isEmpty()) {
            return null;
        }
        return "L" + stubPackage.replace('.', '/') + "/R$array;";
    }

    private static ClassDef createRArrayAliasClass(ClassDef source, String targetType) {
        List<ImmutableField> staticFields = new ArrayList<>();
        for (Field field : source.getStaticFields()) {
            staticFields.add(new ImmutableField(
                    targetType,
                    field.getName(),
                    field.getType(),
                    field.getAccessFlags(),
                    field.getInitialValue(),
                    field.getAnnotations(),
                    field.getHiddenApiRestrictions()));
        }
        return new ImmutableClassDef(
                targetType,
                AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(),
                "Ljava/lang/Object;",
                null,
                null,
                null,
                staticFields,
                Collections.emptyList());
    }

    private static Method maybePatchMethod(File dexPath, ClassDef classDef, Method method) {
        Method patched = maybeInjectJiaguLoad(dexPath, classDef, method);
        if (patched != method) {
            return patched;
        }
        patched = maybePatchFockSign(dexPath, classDef, method);
        if (patched != method) {
            return patched;
        }
        patched = maybePatchNotificationCancel(dexPath, classDef, method);
        if (patched != method) {
            return patched;
        }
        return maybeNeutralize(dexPath, classDef, method);
    }

    private static Method maybeInjectJiaguLoad(File dexPath, ClassDef classDef, Method method) {
        if (!Boolean.getBoolean("multiapp.injectJiaguLoad")) {
            return method;
        }
        String type = classDef.getType();
        if (!("Lcom/stub/StubApp;".equals(type)
                || "Lcom/qihoo/util/StubApp;".equals(type)
                || "Lcom/stub/StubApplication;".equals(type))
                || !"load".equals(method.getName())
                || !method.getParameterTypes().isEmpty()
                || !"V".equals(method.getReturnType())) {
            return method;
        }

        MethodImplementation original = method.getImplementation();
        if (original == null || original.getRegisterCount() < 1) {
            return method;
        }

        MutableMethodImplementation impl = new MutableMethodImplementation(original);
        ImmutableMethodReference loadLibrary = new ImmutableMethodReference(
                NATIVE_LIB_LOADER_TYPE,
                "loadLibrary",
                Collections.singletonList("Ljava/lang/String;"),
                "V");
        impl.addInstruction(0, new BuilderInstruction21c(
                Opcode.CONST_STRING,
                0,
                new ImmutableStringReference("jiagu_vip")));
        impl.addInstruction(1, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC,
                1, 0, 0, 0, 0, 0,
                loadLibrary));
        System.out.println("injected jiagu load " + dexPath.getName() + " " + type + "->load");
        return new MethodWrapper(method, impl);
    }

    private static Method maybePatchFockSign(File dexPath, ClassDef classDef, Method method) {
        if (!Boolean.getBoolean("multiapp.patchFockSign")) {
            return method;
        }
        if (!"Lcom/yuewen/fock/Fock;".equals(classDef.getType())
                || !"sign".equals(method.getName())
                || method.getParameterTypes().size() != 1
                || !"Ljava/lang/String;".equals(method.getParameterTypes().get(0).toString())
                || !"Ljava/lang/String;".equals(method.getReturnType())) {
            return method;
        }

        int registers = Math.max(method.getImplementation() == null ? 2 : method.getImplementation().getRegisterCount(), 2);
        MutableMethodImplementation impl = new MutableMethodImplementation(registers);
        ImmutableMethodReference signCompat = new ImmutableMethodReference(
                "Lcom/multiapp/core/loader/QqReaderSignCompat;",
                "sign",
                Collections.singletonList("Ljava/lang/String;"),
                "Ljava/lang/String;");
        impl.addInstruction(0, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC,
                1, 1, 0, 0, 0, 0,
                signCompat));
        impl.addInstruction(1, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        impl.addInstruction(2, new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        System.out.println("patched Fock.sign(String) " + dexPath.getName() + " -> QqReaderSignCompat.sign");
        patchedSignCompatInCurrentDex = true;
        return new MethodWrapper(method, impl);
    }

    private static ClassDef loadSignCompatClass() throws Exception {
        if (signCompatClass != null) {
            return signCompatClass;
        }
        String helperDex = System.getProperty("multiapp.signCompatDex", "");
        if (helperDex.isEmpty()) {
            throw new IllegalStateException("Missing -Dmultiapp.signCompatDex for " + SIGN_COMPAT_TYPE);
        }
        DexFile dex = DexFileFactory.loadDexFile(new File(helperDex), (Opcodes) null);
        for (ClassDef classDef : dex.getClasses()) {
            if (SIGN_COMPAT_TYPE.equals(classDef.getType())) {
                signCompatClass = classDef;
                return signCompatClass;
            }
        }
        throw new IllegalStateException("Cannot find " + SIGN_COMPAT_TYPE + " in " + helperDex);
    }

    private static Method maybePatchNotificationCancel(File dexPath, ClassDef classDef, Method method) {
        if (!"Lcom/qq/reader/activity/launch/SplashActivity$AppInitTask$1;".equals(classDef.getType())
                || !"run".equals(method.getName())) {
            return method;
        }

        MethodImplementation original = method.getImplementation();
        if (original == null) {
            return method;
        }

        MutableMethodImplementation impl = new MutableMethodImplementation(original);
        List<BuilderInstruction> instructions = impl.getInstructions();
        int patched = 0;
        for (int i = 0; i < instructions.size(); i++) {
            BuilderInstruction instruction = instructions.get(i);
            if (!instruction.getOpcode().name().startsWith("INVOKE")) {
                continue;
            }
            if (!(instruction instanceof ReferenceInstruction)) {
                continue;
            }
            Reference reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) {
                continue;
            }
            MethodReference methodRef = (MethodReference) reference;
            if ("Landroid/app/NotificationManager;".equals(methodRef.getDefiningClass())
                    && (methodRef.getName().equals("cancel")
                    || methodRef.getName().equals("cancelAsUser")
                    || methodRef.getName().equals("cancelAll"))) {
                impl.replaceInstruction(i, new BuilderInstruction10x(Opcode.NOP));
                patched++;
                System.out.println("patched notification call " + dexPath.getName() + " "
                        + classDef.getType() + "->" + method.getName() + " "
                        + methodRef.getDefiningClass() + "->" + methodRef.getName());
            }
        }

        if (patched == 0) {
            return method;
        }
        return new MethodWrapper(method, impl);
    }

    private static Method maybeNeutralize(File dexPath, ClassDef classDef, Method method) {
        String key = classDef.getType() + "->" + method.getName();
        if (TARGETS.contains(key)
                && !"<init>".equals(method.getName())
                && !"<clinit>".equals(method.getName())) {
            if (method.getReturnType().equals(classDef.getType())
                    && !ALLOW_SELF_RETURN_NULL.contains(key)) {
                System.out.println("skipped self-returning " + dexPath.getName() + " " + key + " " + method.getReturnType());
                return method;
            }
            System.out.println("neutralized " + dexPath.getName() + " " + key + " " + method.getReturnType());
            return neutralize(key, method);
        }
        return method;
    }

    private static Method neutralize(String key, Method method) {
        String returnType = method.getReturnType();
        int originalRegisters = method.getImplementation() == null
                ? 2
                : method.getImplementation().getRegisterCount();
        int required = ("J".equals(returnType) || "D".equals(returnType))
                ? 2
                : ("V".equals(returnType) ? 0 : 1);
        MutableMethodImplementation impl = new MutableMethodImplementation(Math.max(originalRegisters, required));

        if ("V".equals(returnType)) {
            impl.addInstruction(0, new BuilderInstruction10x(Opcode.RETURN_VOID));
        } else if ("Z".equals(returnType)
                || "I".equals(returnType)
                || "S".equals(returnType)
                || "B".equals(returnType)
                || "C".equals(returnType)
                || "F".equals(returnType)) {
            impl.addInstruction(0, new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
            impl.addInstruction(1, new BuilderInstruction11x(Opcode.RETURN, 0));
        } else if ("J".equals(returnType) || "D".equals(returnType)) {
            impl.addInstruction(0, new BuilderInstruction21s(Opcode.CONST_WIDE_16, 0, 0));
            impl.addInstruction(1, new BuilderInstruction11x(Opcode.RETURN_WIDE, 0));
        } else {
            impl.addInstruction(0, new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
            impl.addInstruction(1, new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        }

        return new MethodWrapper(method, impl);
    }

    private static class ClassDefWrapper implements ClassDef {
        private final ClassDef base;
        private final Iterable<? extends Method> directMethods;
        private final Iterable<? extends Method> virtualMethods;

        ClassDefWrapper(
                ClassDef base,
                Iterable<? extends Method> directMethods,
                Iterable<? extends Method> virtualMethods
        ) {
            this.base = base;
            this.directMethods = directMethods;
            this.virtualMethods = virtualMethods;
        }

        public String getType() { return base.getType(); }
        public int getAccessFlags() { return base.getAccessFlags(); }
        public String getSuperclass() { return base.getSuperclass(); }
        public List<String> getInterfaces() { return base.getInterfaces(); }
        public String getSourceFile() { return base.getSourceFile(); }
        public Set<? extends org.jf.dexlib2.iface.Annotation> getAnnotations() { return base.getAnnotations(); }
        public Iterable<? extends org.jf.dexlib2.iface.Field> getStaticFields() { return base.getStaticFields(); }
        public Iterable<? extends org.jf.dexlib2.iface.Field> getInstanceFields() { return base.getInstanceFields(); }
        public Iterable<? extends org.jf.dexlib2.iface.Field> getFields() { return base.getFields(); }
        public Iterable<? extends Method> getDirectMethods() { return directMethods; }
        public Iterable<? extends Method> getVirtualMethods() { return virtualMethods; }

        public Iterable<? extends Method> getMethods() {
            List<Method> methods = new ArrayList<>();
            for (Method method : directMethods) {
                methods.add(method);
            }
            for (Method method : virtualMethods) {
                methods.add(method);
            }
            return methods;
        }

        public void validateReference()
                throws org.jf.dexlib2.iface.reference.Reference.InvalidReferenceException {
            base.validateReference();
        }

        public int compareTo(CharSequence other) { return getType().compareTo(other.toString()); }
        public int length() { return getType().length(); }
        public char charAt(int index) { return getType().charAt(index); }
        public CharSequence subSequence(int start, int end) { return getType().subSequence(start, end); }
    }

    private static class MethodWrapper implements Method {
        private final Method base;
        private final MethodImplementation implementation;
        private final int accessFlags;

        MethodWrapper(Method base, MethodImplementation implementation) {
            this.base = base;
            this.implementation = implementation;
            this.accessFlags = base.getAccessFlags()
                    & ~AccessFlags.NATIVE.getValue()
                    & ~AccessFlags.ABSTRACT.getValue();
        }

        public String getDefiningClass() { return base.getDefiningClass(); }
        public String getName() { return base.getName(); }
        public List<? extends CharSequence> getParameterTypes() { return base.getParameterTypes(); }
        public List<? extends org.jf.dexlib2.iface.MethodParameter> getParameters() { return base.getParameters(); }
        public String getReturnType() { return base.getReturnType(); }
        public int getAccessFlags() { return accessFlags; }
        public Set<? extends org.jf.dexlib2.iface.Annotation> getAnnotations() { return base.getAnnotations(); }
        public Set<HiddenApiRestriction> getHiddenApiRestrictions() { return base.getHiddenApiRestrictions(); }
        public MethodImplementation getImplementation() { return implementation; }

        public void validateReference()
                throws org.jf.dexlib2.iface.reference.Reference.InvalidReferenceException {
            base.validateReference();
        }

        public int compareTo(org.jf.dexlib2.iface.reference.MethodReference other) {
            return base.compareTo(other);
        }
    }
}
