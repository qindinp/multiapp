package com.multiapp.core.hook.dexpatch

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x
import timber.log.Timber

/**
 * DEX 方法修补器
 *
 * 将检测方法替换为空方法（直接返回默认值），使检测代码失效
 */
object MethodPatcher {
    private const val TAG = "MethodPatcher"

    /**
     * 将一个方法替换为空实现，返回新的 Method 对象
     *
     * 策略：
     * - 有返回值的方法 → 替换为 return 0/false/null
     * - void 方法 → 替换为 return-void
     * - 构造函数 → 不修改（避免崩溃）
     */
    fun neutralize(method: org.jf.dexlib2.iface.Method): org.jf.dexlib2.iface.Method {
        // 跳过构造函数和静态初始化块
        if (method.name == "<init>" || method.name == "<clinit>") {
            Timber.tag(TAG).d("Skipping constructor/clinit: ${method.name}")
            return method
        }

        val returnType = method.returnType
        val regCount = method.implementation?.registerCount ?: 2

        try {
            val impl = MutableMethodImplementation(regCount)

            // 根据返回类型插入适当的返回指令
            when (returnType) {
                "V" -> {
                    impl.replaceInstruction(0, BuilderInstruction10x(Opcode.RETURN_VOID))
                }
                "Z", "I", "S", "B", "C", "F" -> {
                    impl.replaceInstruction(0, BuilderInstruction11n(Opcode.CONST_4, 0, 0))
                    impl.replaceInstruction(1, BuilderInstruction11x(Opcode.RETURN, 0))
                }
                "J" -> {
                    impl.replaceInstruction(0, BuilderInstruction11n(Opcode.CONST_4, 0, 0))
                    impl.replaceInstruction(1, BuilderInstruction11x(Opcode.RETURN_WIDE, 0))
                }
                "D" -> {
                    impl.replaceInstruction(0, BuilderInstruction11n(Opcode.CONST_4, 0, 0))
                    impl.replaceInstruction(1, BuilderInstruction11x(Opcode.RETURN_WIDE, 0))
                }
                else -> {
                    impl.replaceInstruction(0, BuilderInstruction11n(Opcode.CONST_4, 0, 0))
                    impl.replaceInstruction(1, BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
                }
            }

            Timber.tag(TAG).d("Neutralized: ${method.definingClass}->${method.name} (return=$returnType)")

            // 返回新的 Method 对象，保留原方法的签名，替换实现
            return object : org.jf.dexlib2.iface.Method by method {
                override fun getImplementation(): org.jf.dexlib2.iface.MethodImplementation? = impl
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to neutralize ${method.name}: ${e.message}")
            return method
        }
    }
}
