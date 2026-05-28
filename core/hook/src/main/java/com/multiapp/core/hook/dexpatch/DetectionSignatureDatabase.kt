package com.multiapp.core.hook.dexpatch

/**
 * 加固壳检测方法特征码数据库
 *
 * 每个条目定义了一个检测方法的匹配条件：
 * - classNamePattern: 类名模式（支持通配符 *）
 * - signatureStrings: 方法引用的特征字符串列表（全部匹配才命中）
 *
 * 匹配算法：类名模式 AND 特征字符串引用，避免误判
 */
data class DetectionSignature(
    val id: String,
    val classNamePattern: String,
    val signatureStrings: List<String>,
    val description: String
)

object DetectionSignatureDatabase {

    private val signatures = listOf(
        // 360 加固
        DetectionSignature(
            id = "JIAGU-001",
            classNamePattern = "com.qihoo.util.*",
            signatureStrings = listOf("/proc/self/maps", "xposed"),
            description = "360 maps 扫描检测"
        ),
        DetectionSignature(
            id = "JIAGU-002",
            classNamePattern = "com.qihoo.util.*",
            signatureStrings = listOf("de.robv.android.xposed"),
            description = "360 Xposed 类检测"
        ),
        DetectionSignature(
            id = "JIAGU-003",
            classNamePattern = "com.qihoo.util.*",
            signatureStrings = listOf("liblspd", "LSPosed"),
            description = "360 LSPosed 检测"
        ),
        DetectionSignature(
            id = "JIAGU-004",
            classNamePattern = "com.qihoo.util.*",
            signatureStrings = listOf("lsplant", "shadowhook"),
            description = "360 Hook 框架检测"
        ),

        // 腾讯乐固
        DetectionSignature(
            id = "TENCENT-001",
            classNamePattern = "com.tencent.StubShell.*",
            signatureStrings = listOf("/proc/self/maps"),
            description = "腾讯 maps 扫描"
        ),
        DetectionSignature(
            id = "TENCENT-002",
            classNamePattern = "com.tencent.StubShell.*",
            signatureStrings = listOf("xposed"),
            description = "腾讯 Xposed 检测"
        ),

        // 通用检测
        DetectionSignature(
            id = "UNIVERSAL-001",
            classNamePattern = "*",
            signatureStrings = listOf("XposedBridge"),
            description = "通用 Xposed 检测"
        ),
        DetectionSignature(
            id = "UNIVERSAL-002",
            classNamePattern = "*",
            signatureStrings = listOf("/system/bin/su"),
            description = "通用 Root 检测"
        ),
        DetectionSignature(
            id = "UNIVERSAL-003",
            classNamePattern = "*",
            signatureStrings = listOf("/proc/self/maps", "magisk"),
            description = "通用 Magisk 检测"
        ),
        DetectionSignature(
            id = "UNIVERSAL-004",
            classNamePattern = "*",
            signatureStrings = listOf("frida-agent", "frida-server"),
            description = "通用 Frida 检测"
        )
    )

    fun getForPacker(packerType: String): List<DetectionSignature> {
        val prefix = when (packerType) {
            "360 Jiagu", "360" -> "JIAGU"
            "Tencent Jiagu", "Tencent" -> "TENCENT"
            else -> null
        }
        return if (prefix != null) {
            signatures.filter { it.id.startsWith(prefix) || it.id.startsWith("UNIVERSAL") }
        } else {
            signatures
        }
    }

    fun getAll(): List<DetectionSignature> = signatures
}
