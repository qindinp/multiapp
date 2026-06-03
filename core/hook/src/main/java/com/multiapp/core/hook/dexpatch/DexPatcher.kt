package com.multiapp.core.hook.dexpatch

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import timber.log.Timber
import java.io.File

/**
 * DEX Patch 引擎
 *
 * 使用 dexlib2 扫描 DEX 文件中的检测方法，并将其替换为空实现。
 * 仅在运行时 Hook 不够时使用，作为"兜底"方案。
 */
class DexPatcher {
    companion object {
        private const val TAG = "DexPatcher"
    }

    /**
     * 对 DEX 文件列表执行 patch
     *
     * @param dexPaths DEX 文件路径列表
     * @param packerType 壳类型（用于选择特征库）
     * @return PatchReport 包含修补结果
     */
    fun patch(dexPaths: List<File>, packerType: String): PatchReport {
        Timber.tag(TAG).i("Starting DEX patch for $packerType on ${dexPaths.size} files")

        val signatures = DetectionSignatureDatabase.getForPacker(packerType)
        if (signatures.isEmpty()) {
            Timber.tag(TAG).w("No signatures found for packer: $packerType")
            return PatchReport(0, dexPaths.size)
        }

        var totalPatched = 0
        val errors = mutableListOf<String>()

        for (dexFile in dexPaths) {
            try {
                val patched = patchSingleDex(dexFile, signatures)
                totalPatched += patched
                Timber.tag(TAG).d("Patched $patched methods in ${dexFile.name}")
            } catch (e: Exception) {
                val error = "Failed to patch ${dexFile.name}: ${e.message}"
                Timber.tag(TAG).e(e, error)
                errors.add(error)
            }
        }

        val report = PatchReport(totalPatched, dexPaths.size, errors)
        Timber.tag(TAG).i("DEX patch complete: ${report.patchedMethodCount} methods patched, ${errors.size} errors")
        return report
    }

    /**
     * 对单个 DEX 文件执行 patch
     */
    private fun patchSingleDex(dexFile: File, signatures: List<DetectionSignature>): Int {
        val dex = DexFileFactory.loadDexFile(dexFile, null as org.jf.dexlib2.Opcodes?)
        val classes: List<org.jf.dexlib2.iface.ClassDef> = dex.getClasses().toList()
        val opcodes = dex.getOpcodes()
        var patchedCount = 0
        // Map: class type -> set of method names that should be neutralized
        val patchedMethodsByClass = mutableMapOf<String, MutableSet<String>>()

        // 第一遍：扫描需要修补的方法
        for (classDef in classes) {
            val className = classDef.type
                .removePrefix("L")
                .removeSuffix(";")
                .replace("/", ".")

            for (signature in signatures) {
                if (matchesClassName(className, signature.classNamePattern)) {
                    val methods: List<org.jf.dexlib2.iface.Method> = classDef.methods.toList()
                    for (method in methods) {
                        if (matchesMethodStrings(method, signature.signatureStrings)) {
                            patchedMethodsByClass.getOrPut(classDef.type) { mutableSetOf() }.add(method.name)
                            patchedCount++
                            Timber.tag(TAG).d("Patched: ${signature.id} -> $className.${method.name}")
                        }
                    }
                }
            }
        }

        // 写回修改后的 DEX — 只替换匹配的方法，保留类中的其他方法
        if (patchedCount > 0) {
            val newClasses = mutableListOf<org.jf.dexlib2.iface.ClassDef>()

            for (classDef in classes) {
                val methodsToNeutralize = patchedMethodsByClass[classDef.type]
                if (methodsToNeutralize != null) {
                    val methods: List<org.jf.dexlib2.iface.Method> = classDef.methods.toList()
                    val patchedMethods = mutableListOf<org.jf.dexlib2.iface.Method>()
                    for (method in methods) {
                        if (method.name in methodsToNeutralize) {
                            patchedMethods.add(MethodPatcher.neutralize(method))
                        } else {
                            patchedMethods.add(method)
                        }
                    }
                    newClasses.add(object : org.jf.dexlib2.iface.ClassDef by classDef {
                        override fun getMethods(): MutableIterable<org.jf.dexlib2.iface.Method> = patchedMethods
                    })
                } else {
                    newClasses.add(classDef)
                }
            }

            val tmpFile = File(dexFile.parentFile, dexFile.name + ".tmp")
            val dexPool = DexPool(opcodes)
            for (c in newClasses) {
                dexPool.internClass(c)
            }
            dexPool.writeTo(FileDataStore(tmpFile))
            dexFile.delete()
            tmpFile.renameTo(dexFile)
        }

        return patchedCount
    }

    /**
     * 匹配类名（支持通配符 *）
     */
    private fun matchesClassName(className: String, pattern: String): Boolean {
        if (pattern == "*") return true
        if (!pattern.contains("*")) return className == pattern

        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .toRegex()
        return regex.matches(className)
    }

    /**
     * 检查方法是否引用了所有特征字符串
     * 通过检查方法的字符串引用列表
     */
    private fun matchesMethodStrings(
        method: org.jf.dexlib2.iface.Method,
        signatureStrings: List<String>
    ): Boolean {
        if (signatureStrings.isEmpty()) return false

        val implementation = method.implementation ?: return false
        val methodStrings = mutableSetOf<String>()

        for (instruction in implementation.instructions) {
            // 检查 const-string 指令
            if (instruction.opcode == Opcode.CONST_STRING ||
                instruction.opcode == Opcode.CONST_STRING_JUMBO
            ) {
                try {
                    val ref = (instruction as Instruction21c).reference
                    methodStrings.add(ref.toString())
                } catch (_: Exception) {}
            }
        }

        // 所有特征字符串都必须在方法中出现
        return signatureStrings.all { sig ->
            methodStrings.any { it.contains(sig, ignoreCase = true) }
        }
    }

    /**
     * 在指定类的方法开头注入 System.loadLibrary 调用
     */
    fun injectLoadLibrary(
        dexPaths: List<File>,
        targetClass: String,
        methodName: String,
        libName: String
    ): Boolean {
        Timber.tag(TAG).i("Injecting System.loadLibrary(\"$libName\") into $targetClass->$methodName")
        val internalType = "L${targetClass.replace(".", "/")};"

        for (dexFile in dexPaths) {
            try {
                val dex = DexFileFactory.loadDexFile(dexFile, null as org.jf.dexlib2.Opcodes?)
                val classes = dex.getClasses().toList()
                val opcodes = dex.getOpcodes()

                for (classDef in classes) {
                    if (classDef.type != internalType) continue

                    val methods = classDef.methods.toList()
                    var injected = false
                    val newMethods = mutableListOf<org.jf.dexlib2.iface.Method>()

                    for (method in methods) {
                        if (method.name == methodName && method.parameterTypes.isEmpty()) {
                            newMethods.add(MethodPatcher.injectLoadLibrary(method, libName))
                            injected = true
                        } else {
                            newMethods.add(method)
                        }
                    }

                    if (injected) {
                        val newClasses = classes.map { c ->
                            if (c.type == internalType) {
                                object : org.jf.dexlib2.iface.ClassDef by c {
                                    override fun getMethods(): MutableIterable<org.jf.dexlib2.iface.Method> = newMethods
                                }
                            } else c
                        }
                        val tmpFile = File(dexFile.parentFile, dexFile.name + ".inject.tmp")
                        val dexPool = DexPool(opcodes)
                        for (c in newClasses) { dexPool.internClass(c) }
                        dexPool.writeTo(FileDataStore(tmpFile))
                        dexFile.delete()
                        tmpFile.renameTo(dexFile)
                        Timber.tag(TAG).i("Injection complete: ${dexFile.name}")
                        return true
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to process ${dexFile.name}: ${e.message}")
            }
        }
        Timber.tag(TAG).w("Target $targetClass->$methodName not found in any DEX")
        return false
    }
}
