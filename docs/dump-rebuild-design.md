# Dump + Rebuild 技术方案

最后更新：2026-06-06（v3 - 整合综合评估反馈）

## 一、思路总览

当前方案的核心矛盾：**在运行期逐个 hook 对抗加固壳的反检测，是一场必输的军备竞赛。**

新思路分两步走：

```
Phase 1: 让壳跑完 → dump 解密产物（DEX + native libs）
Phase 2: 用 dump 产物重建干净 APK → 直接加载，无需壳
```

关键转变：

| | 旧方案 | 新方案 |
|---|---|---|
| 目标 | 让壳在 stub 里正常工作 | 让壳完成解密后"退休" |
| 壳的角色 | 持续运行的组件 | 一次性解密工具 |
| 复杂度来源 | 对抗壳的反检测 | 准确捕获解密产物 |
| 最终产物 | 带壳运行的 stub | 不带壳的干净 APK |

## 二、整体架构

```
┌─────────────────────────────────────────────────┐
│                  Phase 1: Dump                   │
│                                                  │
│  ┌──────────┐    ┌──────────┐    ┌────────────┐ │
│  │Stub APK  │───→│ 壳初始化  │───→│ DEX Dump   │ │
│  │(现有构建) │    │(现有hook) │    │ Native Dump│ │
│  └──────────┘    └──────────┘    └─────┬──────┘ │
│                                        │         │
│                              dump_output/        │
│                              ├── classes.dex      │
│                              ├── classes2.dex     │
│                              └── lib/arm64/*.so   │
└────────────────────────────────────────┼─────────┘
                                         │
┌────────────────────────────────────────▼─────────┐
│                Phase 2: Rebuild                   │
│                                                  │
│  ┌──────────┐    ┌──────────┐    ┌────────────┐ │
│  │原APK结构 │    │替换 DEX  │    │ 重建 APK   │ │
│  │(manifest,│───→│替换 .so  │───→│ zipalign   │ │
│  │ resources)│    │清除壳痕迹│    │ sign       │ │
│  └──────────┘    └──────────┘    └─────┬──────┘ │
│                                        │         │
│                               clean_rebuilt.apk  │
└────────────────────────────────────────┼─────────┘
                                         │
┌────────────────────────────────────────▼─────────┐
│              Phase 3: 正常加载                    │
│                                                  │
│  LoaderFactory 加载干净 APK                       │
│  → 无壳 → 无反检测 → 无 JNI_OnLoad 失败          │
│  → Application.onCreate 正常执行                  │
│  → 业务 JNI 方法由 .so 自身 RegisterNatives 绑定  │
└──────────────────────────────────────────────────┘
```

## 三、Phase 1：Dump 模块设计

### 3.1 DEX Dump 策略

360 加固的 DEX 解密流程：

```
libjiagu_vip.so JNI_OnLoad
  → 解密 DEX 字节码
  → 写入 /data/data/<pkg>/files/ 或直接内存加载
  → DexFile.openDexFile / InMemoryDexClassLoader
  → ClassLoader 加载解密后的类
```

需要在解密完成后、壳开始执行业务代码前捕获 DEX。

> **⚠️ 核心难点：** 360 加固部分版本使用 method-level trampoline（方法抽取），调用到某个方法时才解密该 code_item。
> ClassLoader 遍历只能拿到已加载的 DEX，如果壳用 InMemoryDexClassLoader 分批加载，可能只 dump 到一部分。
> P0 验证时**必须**检查：dump 出的 DEX 里类数量是否和 dexdump 分析原始壳时预期的类数量一致。

#### 方案：hook DexFile native 层 + ClassLoader 遍历 + 内存扫描三层保险

**Hook 点 1：DexFile native 创建（精确拦截）**

```cpp
// native-hook.cpp 新增

// Android 10+ libdexfile.so 中的 DexFileLoader::Open
// 通过 GOT hook libart.so 中引用的 dexfile 符号
typedef void* (*DexFileLoaderOpen_t)(...);

void* hooked_dexFileLoaderOpen(...) {
    void* result = original_dexFileLoaderOpen(...);
    
    // 从返回的 DexFile 对象中提取 begin_ 和 size_
    // DexFile 内存布局（Android 10+ arm64）:
    //   offset 0x00: vtable
    //   offset 0x08: begin_ (const uint8_t*)
    //   offset 0x10: size_ (size_t)
    //   offset 0x18: location_ (std::string)
    // 具体偏移需要 per-version 适配
    
    uint8_t* begin = *(uint8_t**)((uintptr_t)result + kBeginOffset);
    size_t size = *(size_t*)((uintptr_t)result + kSizeOffset);
    
    // 校验 DEX magic
    if (size > 0x70 && memcmp(begin, "dex\n", 4) == 0) {
        dump_dex_to_file(begin, size, dump_dir, dex_count++);
    }
    
    return result;
}
```

**Hook 点 2：ClassLoader 路径反射（Java 层兜底）**

```kotlin
// DexDumper.kt 新增

object DexDumper {
    /**
     * 在壳完成 StubApp.load() 后调用
     * 遍历 guest ClassLoader 的 DexPathList，提取所有已加载的 DexFile
     */
    fun dumpFromClassLoader(classLoader: ClassLoader, outputDir: File) {
        // 1. classLoader -> pathList (DexPathList)
        val pathList = findField(classLoader.javaClass, "pathList").get(classLoader)
        
        // 2. pathList -> dexElements (DexPathList.Element[])
        val dexElements = findField(pathList.javaClass, "dexElements")
            .get(pathList) as Array<*>
        
        var dumpIndex = 0
        for (element in dexElements) {
            // 3. element -> dexFile (DexFile)
            val dexFile = findField(element!!.javaClass, "dexFile").get(element)
                ?: continue
            
            // 4. dexFile -> mCookie (native pointer to DexFile C++ object)
            val cookie = findField(dexFile.javaClass, "mCookie").get(dexFile)
                ?: continue
            
            // 5. 通过 JNI 读取 DexFile::begin_ 和 DexFile::size_
            val dexBytes = NativeDumper.extractDexFromCookie(cookie)
            if (dexBytes != null && dexBytes.size > 0x70) {
                val outFile = File(outputDir, "classes${if (dumpIndex == 0) "" else "${dumpIndex + 1}"}.dex")
                outFile.writeBytes(dexBytes)
                Log.i(TAG, "Dumped DEX #${dumpIndex}: ${outFile.name} (${dexBytes.size} bytes)")
                dumpIndex++
            }
        }
    }
}
```

**Hook 点 3：有限内存扫描（最后防线，缩小范围）**

> **⚠️ 不要做全进程内存扫描。** 逐 4 字节扫描 100MB 内存需要 2500 万次 memcmp，
> ARM64 上可能需要数秒，且大量内存读取本身是异常行为，壳可能监控 `/proc/self/stat` 的 utime。
> 
> 策略：先通过 ClassLoader 遍历拿到所有已知 DEX 的地址范围，
> 只对 `mCookie` 中未覆盖的匿名映射区域做有限扫描。

```cpp
// native-hook.cpp 新增

/**
 * 有限内存扫描：只扫描已知 DEX 未覆盖的匿名映射
 * 
 * 步骤：
 *   1. 从 ClassLoader 遍历拿到所有 DexFile* 的 begin_ 和 size_
 *   2. 解析 /proc/self/maps，找到匿名可读映射（[anon:dalvik-*]）
 *   3. 排除已知 DEX 覆盖的区域
 *   4. 对剩余区域做 DEX magic 扫描（只扫前 1MB，DEX header 在开头）
 */
void scanUnknownMemoryForDex(const char* dumpDir, 
                              uintptr_t* knownDexAddrs, int knownCount) {
    FILE* maps = fopen("/proc/self/maps", "r");
    char line[512];
    int dexCount = 0;
    
    while (fgets(line, sizeof(line), maps)) {
        uintptr_t start, end;
        char perms[5], name[256] = "";
        if (sscanf(line, "%lx-%lx %4s %*s %*s %*s %255[^\n]", 
                   &start, &end, perms, name) < 3) continue;
        
        // 只扫描 dalvik 匿名映射（DEX 通常在这里）
        if (!strstr(name, "[anon:dalvik-") && !strstr(line, "")) continue;
        if (perms[0] != 'r') continue;
        
        size_t regionSize = end - start;
        if (regionSize < 0x70 || regionSize > 50 * 1024 * 1024) continue;
        
        // 排除已知 DEX 区域
        bool overlapsKnown = false;
        for (int i = 0; i < knownCount; i += 2) { // pairs: [addr, addr+size]
            if (start < knownDexAddrs[i + 1] && end > knownDexAddrs[i]) {
                overlapsKnown = true;
                break;
            }
        }
        if (overlapsKnown) continue;
        
        // 只扫描前 1MB
        size_t scanSize = regionSize < 1024*1024 ? regionSize : 1024*1024;
        for (uintptr_t addr = start; addr < start + scanSize - 8; addr += 4) {
            if (memcmp((void*)addr, "dex\n", 4) == 0) {
                uint32_t fileSize = *(uint32_t*)(addr + 32);
                if (fileSize > 0x70 && fileSize <= regionSize) {
                    char path[256];
                    snprintf(path, sizeof(path), "%s/mem_dex_%d.dex", dumpDir, dexCount++);
                    FILE* out = fopen(path, "wb");
                    if (out) {
                        fwrite((void*)addr, 1, fileSize, out);
                        fclose(out);
                        LOGI("Scanned DEX at 0x%lx, size=%u -> %s", addr, fileSize, path);
                    }
                }
            }
        }
    }
    fclose(maps);
}
```

**Hook 点 4：FART 式全量类遍历（对抗方法抽取）**

```cpp
// native-hook.cpp 新增

/**
 * FART Phase 2 思路：遍历所有已加载的 Class，从 ArtMethod 中
 * 回溯到 DexFile 和 code_item，重建完整 DEX。
 * 
 * 这是对抗方法抽取（method-level trampoline）的关键。
 * 即使壳只解密了部分方法体，遍历 Class → ArtMethod → code_item
 * 也能拿到所有已解密的方法。
 * 
 * 流程：
 *   Runtime::GetRuntime()
 *     → GetHeap() → VisitClasses (遍历所有 mirror::Class)
 *       → 每个 Class: GetDexFile() + GetDexClassDef()
 *         → 收集唯一的 DexFile* 指针
 *           → 读取 begin_ 和 size_
 */
void dumpDexByClassTraversal(const char* dumpDir) {
    // 通过 JNI 获取 Runtime 指针
    // ArtMethod 结构因 Android 版本不同需要适配
    // 核心偏移：
    //   ArtMethod::declaring_class_ (GcRoot<Class>)
    //   ArtMethod::dex_code_item_offset_ (uint32_t)
    //   ArtMethod::dex_method_index_ (uint32_t)
    //   Class::dex_cache_ -> DexCache::dex_file_ -> DexFile*
    
    // 遍历 ClassTable 收集所有 DexFile*
    // 去重后 dump 每个 DexFile
    // 需要 per-Android-version 适配 ART 内部结构偏移
}
```

**多轮 dump 策略（应对方法抽取）：**

```kotlin
// DumpTrigger.kt 新增

/**
 * 方法抽取场景下，单次 dump 可能不完整。
 * 采用多轮 dump：每触发一次类加载，dump 一次 DEX，
 * 最后合并去重。
 * 
 * 触发时机：
 *   Round 1: StubApp.load() 完成后（壳解密主 DEX）
 *   Round 2: Application.attachBaseContext() 之后（可能触发延迟解密）
 *   Round 3: Application.onCreate() 之后（业务 SDK 初始化可能触发更多解密）
 *   Round 4: 首个 Activity 启动后（最后一批按需解密）
 * 
 * 每轮 dump 后对比：如果新增了类，说明还有延迟解密在进行。
 */
class MultiRoundDumper(private val guestClassLoader: ClassLoader) {
    private val seenDexHashes = mutableSetOf<String>()
    private val mergedClasses = mutableMapOf<String, MutableSet<String>>() // dexHash -> classNames
    
    fun dumpRound(outputDir: File, roundName: String): Int {
        val roundDir = File(outputDir, roundName).apply { mkdirs() }
        val newDexCount = DexDumper.dumpFromClassLoader(guestClassLoader, roundDir)
        
        // 对比 DEX 内容，去重
        var newClassCount = 0
        roundDir.listFiles { f -> f.name.endsWith(".dex") }?.forEach { dex ->
            val hash = computeHash(dex)
            if (hash !in seenDexHashes) {
                seenDexHashes.add(hash)
                val classes = ClassExtractor.extractClassNames(dex)
                mergedClasses[hash] = classes.toMutableSet()
                newClassCount += classes.size
            }
        }
        
        Log.i(TAG, "Round $roundName: $newDexCount DEX, $newClassCount new classes")
        return newClassCount
    }
    
    fun isComplete(): Boolean {
        // 如果最近一轮没有新增类，认为 dump 完成
        return mergedClasses.isNotEmpty()
    }
    
    fun mergeToFinal(outputDir: File) {
        // 将所有轮次的 DEX 合并到最终输出目录
        // 去重逻辑：相同 hash 的 DEX 只保留一份
        // 方法体级别的合并更复杂，需要 dexlib2 支持
    }
}
```

### 3.2 Native Library Dump 策略

360 加固的 native 库解密流程：

```
壳代码解密 .so 文件字节码到内存
  → dlopen (解密后的内存/文件)
  → linker 加载、重定位
  → .init_array / JNI_OnLoad
```

#### 方案：dlopen 后 dump + ELF 重建

> **⚠️ ELF 重建注意事项：**
> 1. **Section headers 丢失** — `dl_iterate_phdr` 只有 program headers，没有 section headers。
>    重建的 ELF 缺 `.symtab`、`.dynsym` 等，但对运行时加载不影响（linker 只看 program headers）。
> 2. **GOT/PLT 已重定位** — dump 出的 .so 的 GOT 表项已指向真实地址。
>    重新加载时 dynamic linker 会重新重定位（R_*_JUMP_SLOT 类型会覆盖），但
>    某些 R_*_GLOB_DAT 类型的重定位可能冲突，需要在重建时清零 GOT/PLT 段。
> 3. **壳 .so 不需要 dump** — `libjiagu_vip.so` 解密后可能修改了自身代码段，
>    但 clean APK 不带壳，这部分逻辑丢掉无所谓。

```cpp
// native-hook.cpp 新增

#include <link.h>
#include <elf.h>

struct SoDumpInfo {
    const char* targetName;
    const char* dumpDir;
    bool found;
};

static int dumpPhdrCallback(struct dl_phdr_info* info, size_t size, void* data) {
    auto* di = (SoDumpInfo*)data;
    
    if (!info->dlpi_name || !strstr(info->dlpi_name, di->targetName)) {
        return 0; // 继续遍历
    }
    
    di->found = true;
    
    // 计算总加载大小
    size_t maxEnd = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_LOAD) {
            size_t segEnd = info->dlpi_phdr[i].p_vaddr + info->dlpi_phdr[i].p_memsz;
            if (segEnd > maxEnd) maxEnd = segEnd;
        }
    }
    
    // 读取 ELF header
    Elf64_Ehdr* ehdr = (Elf64_Ehdr*)info->dlpi_addr;
    
    // 构建输出文件
    char outPath[512];
    const char* basename = strrchr(info->dlpi_name, '/');
    basename = basename ? basename + 1 : info->dlpi_name;
    snprintf(outPath, sizeof(outPath), "%s/%s", di->dumpDir, basename);
    
    FILE* out = fopen(outPath, "wb");
    if (!out) return 1;
    
    // 写入 ELF header
    fwrite(ehdr, 1, sizeof(Elf64_Ehdr), out);
    
    // 写入 program headers
    Elf64_Phdr* phdrs = (Elf64_Phdr*)(info->dlpi_addr + ehdr->e_phoff);
    fseek(out, ehdr->e_phoff, SEEK_SET);
    fwrite(phdrs, 1, ehdr->e_phnum * sizeof(Elf64_Phdr), out);
    
    // 写入每个 PT_LOAD 段的内容
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (phdrs[i].p_type == PT_LOAD) {
            void* segData = (void*)(info->dlpi_addr + phdrs[i].p_vaddr);
            fseek(out, phdrs[i].p_offset, SEEK_SET);
            fwrite(segData, 1, phdrs[i].p_filesz, out);
        }
    }
    
    // 清零 GOT/PLT 段，让 dynamic linker 重新解析
    // 遍历 dynamic section 找到 DT_JMPREL (PLT) 和 DT_PLTGOT (GOT)
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            Elf64_Dyn* dyn = (Elf64_Dyn*)(info->dlpi_addr + phdrs[i].p_vaddr);
            Elf64_Addr jmprel = 0; size_t pltrelsz = 0;
            for (; dyn->d_tag != DT_NULL; dyn++) {
                if (dyn->d_tag == DT_JMPREL) jmprel = dyn->d_un.d_ptr;
                if (dyn->d_tag == DT_PLTGOT) {
                    // 清零 GOT[1] (linker cookie) 和 GOT[2] (resolver)
                    // 让 linker 重新初始化
                    Elf64_Addr* got = (Elf64_Addr*)(dyn->d_un.d_ptr);
                    // got[1] 和 got[2] 在重新加载时会被 linker 覆盖
                }
            }
            // 清零 PLT entries 中已解析的地址
            if (jmprel && pltrelsz) {
                // 找到对应 PT_LOAD 段的文件偏移并清零
                // ... 需要计算 jmprel 对应的文件偏移
            }
            break;
        }
    }
    
    fclose(out);
    LOGI("Dumped SO: %s (base=0x%lx, maxEnd=0x%lx)", outPath, info->dlpi_addr, maxEnd);
    
    return 1; // 停止遍历
}

/**
 * dump 指定已加载的 native library
 */
bool dumpLoadedLibrary(const char* libName, const char* dumpDir) {
    SoDumpInfo di = { libName, dumpDir, false };
    dl_iterate_phdr(dumpPhdrCallback, &di);
    return di.found;
}

/**
 * dump 所有 origin native libs（壳解密后）
 * 在 StubApp.load() 完成后调用
 */
void dumpAllOriginLibs(const char* dumpDir) {
    // 扫描 /proc/self/maps 找到所有属于 app data 目录的 .so
    FILE* maps = fopen("/proc/self/maps", "r");
    char line[512];
    
    while (fgets(line, sizeof(line), maps)) {
        if (strstr(line, "/lib/arm64/") || strstr(line, "/lib/arm/")) {
            if (strstr(line, ".so") && strstr(line, "r-xp")) {
                // 提取库名
                char* path = strchr(line, '/');
                if (path) {
                    char* newline = strchr(path, '\n');
                    if (newline) *newline = 0;
                    
                    const char* basename = strrchr(path, '/');
                    if (basename) {
                        basename++;
                        SoDumpInfo di = { basename, dumpDir, false };
                        dl_iterate_phdr(dumpPhdrCallback, &di);
                    }
                }
            }
        }
    }
    fclose(maps);
}
```

### 3.3 Dump 触发时机

在 [LoaderFactory.kt](core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt) 的 `preloadPackerLibViaGuestClassLoader()` 中，**壳完成解密后**触发 dump：

> **⚠️ 壳使用 InMemoryDexClassLoader 的场景：**
> 如果壳不走文件路径而是直接内存加载，ClassLoader 遍历可能拿不到 `mCookie`（因为
> `InMemoryDexClassLoader` 的 DexFile 对象结构不同）。此时必须依赖 native 层的
> DexFile hook 或内存扫描。P0 验证时需要确认 360 加固具体用哪种方式加载解密后的 DEX。

```kotlin
// LoaderFactory.kt 修改

private fun ensureClassLoaderSwapped(cl: ClassLoader) {
    // ... 现有步骤 1-8 ...
    
    // 步骤 9: 触发壳解密
    preloadPackerLibViaGuestClassLoader(appInfo, guestClassLoader)
    
    // 步骤 10: ★ 新增 - dump 解密产物
    if (shouldDump()) {
        val dumpDir = File(appInfo.dataDir, "dump_output").apply { mkdirs() }
        val multiRound = MultiRoundDumper(guestClassLoader)
        
        // Round 1: StubApp.load() 完成后
        multiRound.dumpRound(dumpDir, "round1_after_stub_load")
        
        // Native lib dump：通过 dl_iterate_phdr
        NativeDumper.dumpAllOriginLibs(
            File(dumpDir, "lib/${Build.SUPPORTED_ABIS[0]}").apply { mkdirs() }
        )
        
        // 内存扫描兜底
        NativeDumper.scanMemoryForDex(dumpDir)
        
        // 标记 dump 阶段完成（后续轮次在 Application.onCreate 后触发）
        File(dumpDir, "dump_round1.marker").createNewFile()
        
        Log.w(TAG, "=== DUMP ROUND 1 COMPLETE === ${dumpDir.absolutePath}")
    }
    
    // 步骤 11: 继续正常启动...
    // 注意：后续 dump 轮次在 instantiateApplication() 和
    // instantiateActivity() 中触发，不在这里阻塞
}

private fun shouldDump(): Boolean {
    // 检查配置或文件标记
    val marker = File(appInfo.dataDir, "dump_requested.marker")
    return marker.exists()
}
```

### 3.4 Dump 后验证

```kotlin
// DumpVerifier.kt 新增

object DumpVerifier {
    data class DumpReport(
        val dexFiles: List<DexInfo>,
        val soFiles: List<SoInfo>,
        val issues: List<String>
    )
    
    data class DexInfo(val file: File, val classCount: Int, val version: String)
    data class SoInfo(val file: File, val isValidElf: Boolean, val exportedSymbols: Int)
    
    fun verify(dumpDir: File): DumpReport {
        val issues = mutableListOf<String>()
        
        // 检查 DEX 文件
        val dexFiles = dumpDir.listFiles { f -> f.name.endsWith(".dex") }?.map { f ->
            val bytes = f.readBytes()
            val magic = String(bytes.sliceArray(0..3))
            if (magic != "dex\n") {
                issues.add("${f.name}: invalid magic '$magic'")
            }
            val version = String(bytes.sliceArray(4..7)).trim(' ')
            val classCount = readClassCount(bytes)
            DexInfo(f, classCount, version)
        } ?: emptyList()
        
        if (dexFiles.isEmpty()) {
            issues.add("CRITICAL: No DEX files dumped!")
        }
        
        // 检查 native libs
        val libDir = File(dumpDir, "lib")
        val soFiles = libDir.walkTopDown()
            .filter { it.name.endsWith(".so") }
            .map { f ->
                val bytes = f.readBytes()
                val isValidElf = bytes.size >= 4 
                    && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte()
                    && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
                if (!isValidElf) issues.add("${f.name}: not a valid ELF")
                SoInfo(f, isValidElf, 0) // TODO: count exported symbols
            }.toList()
        
        return DumpReport(dexFiles, soFiles, issues)
    }
    
    private fun readClassCount(dexBytes: ByteArray): Int {
        if (dexBytes.size < 96) return 0
        return dexBytes.sliceArray(96..99).let {
            (it[0].toInt() and 0xFF) or
            ((it[1].toInt() and 0xFF) shl 8) or
            ((it[2].toInt() and 0xFF) shl 16) or
            ((it[3].toInt() and 0xFF) shl 24)
        }
    }
}
```

## 四、Phase 2：Rebuild 模块设计

### 4.1 Rebuild 流程

```kotlin
// ApkRebuilder.kt 新增

/**
 * 从 dump 产物重建干净 APK
 * 
 * 输入：
 *   - originalApk: 未修改的原始 APK（用 origin_original.apk）
 *   - dumpDir: dump 产物目录
 * 
 * 输出：
 *   - cleanApk: 不含壳的干净 APK
 */
class ApkRebuilder(
    private val originalApk: File,
    private val dumpDir: File,
    private val outputDir: File,
    private val signingConfig: SigningConfig
) {
    data class RebuildReport(
        val success: Boolean,
        val outputPath: File?,
        val dexReplaced: Int,
        val soReplaced: Int,
        val manifestCleaned: Boolean,
        val issues: List<String>
    )
    
    fun rebuild(): RebuildReport {
        val issues = mutableListOf<String>()
        val workDir = File(outputDir, "rebuild_work").apply { mkdirs() }
        
        // Step 1: 解压原始 APK
        val extracted = extractApk(originalApk, workDir)
        
        // Step 2: 替换 DEX 文件
        val dexReplaced = replaceDexFiles(workDir, dumpDir)
        if (dexReplaced == 0) {
            issues.add("WARNING: No DEX files replaced")
        }
        
        // Step 3: 替换 native libraries
        val soReplaced = replaceNativeLibs(workDir, dumpDir)
        
        // Step 4: 清理 manifest 中的壳组件
        val manifestCleaned = cleanManifest(workDir, dumpDir)
        
        // Step 5: 删除壳相关的 assets
        cleanAssets(workDir)
        
        // Step 6: 删除 META-INF（旧签名）
        File(workDir, "META-INF").deleteRecursively()
        
        // Step 7: 重新打包 APK
        val unsignedApk = File(outputDir, "rebuilt_unsigned.apk")
        packageApk(workDir, unsignedApk)
        
        // Step 8: Zipalign
        val alignedApk = File(outputDir, "rebuilt_aligned.apk")
        zipalignApk(unsignedApk, alignedApk)
        
        // Step 9: 签名
        val signedApk = File(outputDir, "rebuilt_clean.apk")
        signApk(alignedApk, signedApk, signingConfig)
        
        // Step 10: 清理临时文件
        workDir.deleteRecursively()
        unsignedApk.delete()
        alignedApk.delete()
        
        return RebuildReport(
            success = signedApk.exists() && signedApk.length() > 0,
            outputPath = signedApk,
            dexReplaced = dexReplaced,
            soReplaced = soReplaced,
            manifestCleaned = manifestCleaned,
            issues = issues
        )
    }
}
```

### 4.2 DEX 替换逻辑

```kotlin
// ApkRebuilder.kt 内部

/**
 * 用 dump 出的 DEX 替换 APK 中的原始 DEX
 * 
 * 360 加固的原始 APK 中，classes.dex 是壳的 loader
 * 真实业务 DEX 在壳运行时才解密
 * 
 * 替换策略：
 *   - 删除原始 classes.dex（壳的代码）
 *   - 复制 dump 出的所有 DEX 作为 classes.dex, classes2.dex, ...
 *   - 如果 dump 出的 DEX 只有一个，它就是 classes.dex
 */
private fun replaceDexFiles(workDir: File, dumpDir: File): Int {
    // 删除原始 DEX
    workDir.listFiles { f -> f.name.endsWith(".dex") }?.forEach { it.delete() }
    
    // 复制 dump DEX
    val dumpDexFiles = dumpDir.listFiles { f -> f.name.endsWith(".dex") }
        ?.sortedBy { it.name }
        ?: return 0
    
    dumpDexFiles.forEachIndexed { index, dexFile ->
        val targetName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
        dexFile.copyTo(File(workDir, targetName), overwrite = true)
    }
    
    return dumpDexFiles.size
}
```

### 4.3 Native Library 替换逻辑

```kotlin
// ApkRebuilder.kt 内部

/**
 * 用 dump 出的 .so 替换 APK 中的 native libs
 * 
 * 壳可能对原始 .so 做了加密或代码抽取
 * dump 出的是解密/恢复后的版本
 * 
 * 策略：
 *   - 遍历 dumpDir/lib/ 下的 .so
 *   - 按文件名匹配替换 APK lib/ 中对应的 .so
 *   - 不在 APK 中出现的 dump .so 也加入（壳可能新增了库）
 */
private fun replaceNativeLibs(workDir: File, dumpDir: File): Int {
    var count = 0
    
    val dumpLibDir = File(dumpDir, "lib")
    if (!dumpLibDir.exists()) return 0
    
    // 遍历 ABI 目录
    dumpLibDir.listFiles()?.forEach { abiDir ->
        if (!abiDir.isDirectory) return@forEach
        
        val targetLibDir = File(workDir, "lib/${abiDir.name}")
        targetLibDir.mkdirs()
        
        abiDir.listFiles { f -> f.name.endsWith(".so") }?.forEach { soFile ->
            val target = File(targetLibDir, soFile.name)
            soFile.copyTo(target, overwrite = true)
            count++
        }
    }
    
    return count
}
```

### 4.4 Manifest 清理

> **⚠️ Manifest 清理复杂度高于预期：**
> 1. 壳可能**替换**了原始 Application 类（不是新增，而是把 `com.example.App` 改指向壳的 `StubApplication`）
> 2. 壳可能修改了 `android:process`、`android:sharedUserId` 等属性
> 3. 仅移除壳前缀的组件不够，还需要**恢复被壳篡改的组件指向**
> 4. 壳可能修改了 `ApplicationInfo.meta-data` 用于热修复框架（Tinker/RFix）的初始化
>
> **应对：不修改 manifest，改为在运行时处理。**
> 壳组件在 manifest 中声明但类不存在时，Android 不会立即崩溃，
> 只有在实际启动该组件时才会 crash。
> 我们可以在 LoaderFactory 中拦截 `instantiateActivity/instantiateService`，
> 对壳组件返回一个 stub 实现或直接 skip。
> 这样避免了 manifest 编码/解码的复杂性。

```kotlin
// ManifestCleaner.kt - 两种策略，优先用运行时过滤

object ManifestCleaner {
    
    /**
     * 策略 A（推荐）：不改 manifest，运行时过滤
     * 
     * 在 LoaderFactory 中拦截组件实例化：
     * - instantiateActivity: 如果是壳组件，返回 StubActivity
     * - instantiateService: 如果是壳组件，返回 StubService
     * - instantiateReceiver: 如果是壳组件，直接跳过
     * - instantiateProvider: 如果是壳组件，返回空 Provider
     * 
     * 优点：不需要 manifest 编码/解码，不会引入新的编码 bug
     * 缺点：壳组件仍然注册在系统中，占用资源但不致命
     */
    fun isShellComponent(className: String): Boolean {
        val shellPatterns = listOf(
            "com.stub.", "com.qihoo.", "com.secneo.",
            "com.nqshield.", "com.secshell.",
            "com.baidu.protect.", "com.tencent.StubShell"
        )
        return shellPatterns.any { className.startsWith(it) }
    }
    
    /**
     * 策略 B（备选）：直接修改 manifest 二进制
     * 
     * 风险：BinaryXmlEncoder 可能无法完美处理所有 manifest 属性
     * 只在策略 A 不够用时才考虑
     */
    fun cleanManifestBinary(
        manifestBytes: ByteArray,
        shellComponentNames: Set<String>
    ): ByteArray {
        // 1. 解析 manifest
        val parsed = ManifestParser.parse(manifestBytes)
        
        // 2. 找到壳 Application 类并恢复原始指向
        val originalAppClass = findOriginalApplicationClass(parsed, shellComponentNames)
        
        // 3. 移除壳组件
        // 4. 恢复被篡改的属性（process, sharedUserId 等）
        // 5. 用 BinaryXmlEncoder 重新编码
        
        // ⚠️ 这一步目前不可靠，建议先用策略 A
        return manifestBytes // placeholder
    }
}
```

### 4.5 复用现有构建管线

Rebuild 大量复用 `StubBuilder` 中已有的能力：

| 需求 | 复用组件 |
|---|---|
| APK 打包 | `StubBuilder.assembleApk()` 的 zip 逻辑 |
| Zipalign | `StubBuilder.zipalign()` |
| 签名 | `StubBuilder.signApk()` + `ApkSigningHelper` |
| Manifest 编码 | `BinaryXmlEncoder` |
| Manifest 解析 | `ManifestParser` |
| Native lib 处理 | `StubBuilder.packageNativeLibs()` 的 ELF 解析 |

## 五、Phase 3：干净 APK 加载

### 5.1 LoaderFactory 适配

重建后的干净 APK 没有壳，加载流程大幅简化：

```kotlin
// LoaderFactory.kt 中增加 clean APK 加载路径

private fun ensureClassLoaderSwapped(cl: ClassLoader) {
    val config = readConfig()
    
    if (config.isCleanApk) {
        // ★ 干净 APK 路径：跳过所有壳相关逻辑
        loadCleanApk(cl, config)
    } else {
        // 原始路径：带壳加载 + dump
        loadProtectedApk(cl, config)
    }
}

private fun loadCleanApk(cl: ClassLoader, config: AppConfig) {
    // 1. 解压干净 APK
    val cleanApk = extractAsset("clean_origin.apk", appInfo.dataDir)
    
    // 2. 替换 ClassLoader（复用现有 swapClassLoader 逻辑）
    // 但跳过：壳 native 库预加载、FindClass hook、GOT hook、
    //         完整性重定向、JNI_OnLoad patch、Stage 2 业务 JNI 预加载
    swapClassLoader(cl, cleanApk)
    
    // 3. 应用 Context 伪装（仍需要，因为系统层包名是 stub 的）
    // 复用 GuestContextWrapper
    
    // 4. 完成 - 直接进入 Application.onCreate
    // 不需要任何 native hook（没有壳的反检测要绕过）
}
```

### 5.2 可精简的模块

加载干净 APK 时，以下模块**不再需要**：

| 模块 | 原因 |
|---|---|
| `NativeHookBridge.initNativeHooks()` | 无壳，不需要 maps 过滤、ptrace 伪装等 |
| `preloadPackerLibViaGuestClassLoader()` | 无壳 native 库要加载 |
| `preloadGuestRuntimeNativeLibraries()` | 业务 .so 已在干净 APK 的 lib/ 中，正常加载即可 |
| `patchJiaguLoad()` | 无 libjiagu_vip.so |
| `setIntegrityRedirect()` | 无完整性校验 |
| `FindClass hook` | 无 JNI_OnLoad 需要 guest Class |
| `GOT hook` | 无壳读 maps |
| ShadowHook 大部分 hook | 无反检测要绕 |

**仍需要保留的能力：**

| 模块 | 原因 |
|---|---|
| `ClassLoader 替换` | 系统层身份仍是 stub，需要替换到 origin |
| `GuestContextWrapper` | 应用层包名伪装 |
| `资源替换` | LoadedApk.mResources 需指向 origin |
| `nativeLibraryDir 设置` | 确保 System.loadLibrary 找到正确的 .so |
| **`签名校验 hook`** | **⚠️ 必须保留！见下方说明** |
| **`包名校验 hook`** | **应用可能校验包名与签名的对应关系** |
| Identity hooks（可选） | 多实例设备伪装 |

> **⚠️ 签名校验是 clean 加载路径最大的风险：**
> 
> 很多应用（特别是 QQ 系列）不只在 DEX 里校验签名：
> 1. **DEX 层**：`PackageManager.getPackageInfo(GET_SIGNATURES)` — 用 DexPatcher 中和
> 2. **Native 层**：通过 JNI 调用 `PackageManager` 或自行读取 APK 的签名块 — 需要保留 `SignatureBypass` hook
> 3. **服务端**：登录时把本地签名发到服务端比对 — 这个**无法绕过**，除非用原始签名重新签 APK
> 4. **签名校验自保护**：校验逻辑可能在 native 层，且做了完整性校验 — 中和 DEX 层校验方法后 native 层仍可能检测
>
> **应对策略：**
> - clean APK 用**原始签名**重新签名（如果能拿到 keystore）
> - 如果拿不到原始签名，必须在 clean 加载路径中保留 `SignatureBypass` + `DeviceIdentityHook`
> - 用 DexPatcher 扫描 clean DEX 中的签名校验方法并中和
> - P4 验证时重点测试：登录、支付、分享等需要服务端校验的功能

## 六、新模块结构

```
core/
├── dump/                          ← 新增
│   ├── build.gradle.kts
│   └── src/main/java/.../
│       ├── DexDumper.kt           // DEX dump（Java 层 ClassLoader 遍历）
│       ├── NativeDumper.kt        // Native lib dump（dl_iterate_phdr）
│       ├── MemoryScanner.kt       // 内存扫描兜底
│       ├── DumpTrigger.kt         // dump 触发和生命周期管理
│       └── DumpVerifier.kt        // dump 产物验证
│
├── rebuild/                       ← 新增
│   ├── build.gradle.kts
│   └── src/main/java/.../
│       ├── ApkRebuilder.kt        // 主流程：解压→替换→清理→打包→签名
│       ├── DexReplacer.kt         // DEX 替换逻辑
│       ├── NativeLibReplacer.kt   // .so 替换逻辑
│       ├── ManifestCleaner.kt     // manifest 壳组件清理
│       └── ClassExtractor.kt      // 从 DEX 提取类名列表
│
├── loader/                        ← 修改
│   └── LoaderFactory.kt           // 增加 dump 触发点 + clean APK 加载路径
│
├── hook/                          ← 修改
│   └── native-hook.cpp            // 增加 DexFile hook + SO dump 原语
│
└── stub/                          ← 修改
    └── StubBuilder.kt             // 增加 "包含 clean APK" 的打包模式
```

## 七、完整用户流程

### 7.1 首次使用（Dump 阶段）

```
用户选择要克隆的 APK（如 QQ 阅读）
  ↓
MultiApp 检测到是加固 APK（通过 PackerDetector）
  ↓
StubBuilder 构建带 dump 能力的 stub APK
  ↓
安装并启动 stub
  ↓
LoaderFactory 正常执行壳加载链路
  ↓
壳完成 DEX 解密
  ↓
★ DexDumper + NativeDumper 捕获解密产物
  ↓
dump_output/ 写入设备存储
  ↓
DumpVerifier 验证产物完整性
  ↓
弹通知："dump 完成，正在重建..."
```

### 7.2 重建阶段

```
ApkRebuilder 启动（设备上或电脑上）
  ↓
读取 dump_output/ + origin_original.apk
  ↓
替换 DEX、native libs、清理 manifest
  ↓
打包 → zipalign → 签名
  ↓
生成 clean_rebuilt.apk
  ↓
验证：dexdump 确认类列表完整
```

### 7.3 后续使用（Clean 加载）

```
StubBuilder 用 clean APK 构建新 stub
  ↓
LoaderFactory 检测到 isCleanApk
  ↓
走精简加载路径：ClassLoader 替换 + 资源替换 + Context 伪装
  ↓
Application.onCreate 正常执行
  ↓
无壳、无反检测、无 JNI 失败
```

## 八、风险与应对

### 8.1 Dump 阶段风险

| 风险 | 概率 | 应对 |
|---|---|---|
| 壳检测到 dump 行为 | 中 | dump 时机在壳完成解密之后；hook 痕迹通过现有 GOT hook 路径隐藏 |
| **壳使用 method-level trampoline（按需解密方法体）** | **低-中** | **从 jiagu-bypass-analysis.md 分析，360 加固对 QQ 阅读主要是整体 DEX 加密（壳解密后通过 DexFile.openDexFile 加载），不是 method-level。但 P0 必须用 dexdump 验证方法体完整性。FART 式遍历作为后备。** |
| DEX 通过 InMemoryDexClassLoader 加载，无文件路径 | 中 | DexFile native hook + 内存扫描双保险；P0 需确认 360 加固的加载方式 |
| dump 时 .so 的 GOT/PLT 已被重定位 | 高 | 重建时清零 GOT/PLT，让 dynamic linker 重新解析 |
| 壳有运行期完整性校验（不只启动时） | 低 | 360 加固主要在启动时校验，运行期校验成本太高 |
| 壳检测到 `dl_iterate_phdr` 被调用 | 低 | 可改用直接读 `/proc/self/maps` + 手动解析 ELF header |

### 8.2 Rebuild 阶段风险

| 风险 | 概率 | 应对 |
|---|---|---|
| 壳 manifest 中的组件在 dump DEX 中不存在 | 确定 | 运行时过滤（ManifestCleaner.isShellComponent），不改 manifest |
| **壳替换了原始 Application 类指向** | **高** | **需要从原始 APK（origin_original.apk）恢复 Application 类名** |
| **壳修改了 android:process 等属性** | **中** | **ManifestCleaner 需要恢复这些属性** |
| 资源 ID 不匹配 | 中 | 使用原始 APK 的 resources.arsc |
| 壳注入了 native 做运行期校验，去掉壳后某些功能缺失 | 中 | dump 阶段同时 dump 所有 .so，不只壳的 |
| **签名校验（应用自校验签名）** | **高** | **DexPatcher 中和 + SignatureBypass hook 保留 + 原始签名重签（如果可得）** |
| **服务端签名校验** | **中** | **无法绕过，需要原始 keystore 或接受功能降级** |

### 8.3 Clean 加载阶段风险

| 风险 | 概率 | 应对 |
|---|---|---|
| 应用自身有签名校验（DEX 层） | 高 | DexPatcher 扫描并中和签名校验方法 |
| 应用自身有签名校验（native 层） | 高 | 保留 SignatureBypass + native 层 PackageManager hook |
| **应用登录/支付时服务端校验签名** | **中** | **必须用原始 keystore 重签；否则只能降级使用** |
| 应用检查包名对应的签名 | 中 | GuestContextWrapper + SignatureBypass |
| 应用有 root/模拟器检测 | 低 | 保留 identity hooks |

## 九、实施路线图

### P0：验证可行性（1-2 天）★ 关键里程碑

1. 在 `native-hook.cpp` 中临时添加 `dl_iterate_phdr` dump 逻辑
2. 在 `LoaderFactory.preloadPackerLibViaGuestClassLoader()` 末尾触发 dump
3. 手动验证：
   - dump 出的 DEX 数量是否合理（对比 dexdump 分析原始壳的预期）
   - dump 出的 DEX 中类数量是否完整（搜索 `YWLoginManager` 等关键类）
   - dump 出的 .so 是否是有效的 ELF（`readelf -h` 验证）
   - 360 加固是用文件路径还是 InMemoryDexClassLoader 加载 DEX
   - **★ 方法体完整性检查：用 dexdump 检查 `YWLoginManager.getInstance` 方法是否有实际指令，而不是空方法体或 trampoline 跳板。这是判断 360 加固是否使用 method-level unpacking 的关键依据。**
4. **如果 DEX dump 成功且方法体完整，后续所有步骤才有意义**

### P1：自动化 Dump（2-3 天）

1. 创建 `core/dump` 模块
2. 实现 `DexDumper`（ClassLoader 遍历 + DexFile native 提取）
3. 实现 `NativeDumper`（dl_iterate_phdr dump + GOT/PLT 清零）
4. 实现 `DumpVerifier`（类数量校验 + 方法体完整性校验 + ELF 有效性校验）
5. 实现 `MultiRoundDumper`（多轮 dump，应对方法抽取）
6. 在 LoaderFactory 中集成 dump 触发（Round 1 + Round 2/3 延迟触发）
7. 内存扫描暂缓——先验证 ClassLoader 遍历是否足够，不够再加

### P2：自动化 Rebuild（3-4 天）

1. 创建 `core/rebuild` 模块
2. 实现 `ApkRebuilder` 主流程（DEX/SO 替换 + 打包 + 签名）
3. **Manifest 清理暂不做二进制修改**，改用运行时过滤（P3 实现）
4. 实现签名校验中和（复用 DexPatcher）
5. 集成 StubBuilder 的打包/签名管线
6. **实现原始 APK 签名提取（如果可能）**

### P3：Clean 加载路径（1-2 天）

1. LoaderFactory 增加 `isCleanApk` 分支
2. 精简 clean 路径不需要的 hook 初始化
3. **保留签名校验 hook + 包名校验 hook**
4. **实现壳组件运行时过滤（instantiateActivity/Service/Provider/Receiver）**
5. StubBuilder 增加 clean APK 打包模式

### P4：端到端验证（2-3 天）

1. QQ 阅读 dump → rebuild → clean 加载
2. 验证 `Application.onCreate` 通过
3. 验证 `YWLoginManager.getInstance()` 不再崩溃
4. **验证签名校验场景：登录、分享、支付**
5. **验证壳组件过滤：不因壳 Activity 声明而崩溃**
6. 验证业务功能（登录、阅读等）可用

## 十、关键设计决策

### Q1：dump 在设备上做还是电脑上做？

**设备上做。** 原因：
- 壳的解密发生在设备上，解密后的 DEX/.so 在设备进程内存中
- 无法把进程内存搬到电脑上（除非用 gdbserver，但检测风险更高）
- dump 后的 rebuild 可以在设备上或电脑上做（无区别）

### Q2：dump 触发是自动的还是用户手动的？

**自动触发，用户确认。** 流程：
1. StubBuilder 构建 stub 时标记 `dump_mode=true`
2. LoaderFactory 在壳解密完成后自动 dump
3. dump 完成后弹通知，用户确认后开始 rebuild
4. rebuild 完成后自动替换为 clean APK

### Q3：对未加固的 APK 是否走 dump 流程？

**不走。** PackerDetector 已经能识别加固类型。未加固的 APK 直接用现有加载路径，不需要 dump + rebuild。

### Q4：一次 dump 能否支持多设备？

**大部分情况可以。** DEX 在所有设备上相同（壳解密算法不依赖设备）。但 native lib 如果有设备相关的重定位，可能需要 per-device dump。实际上 dynamic linker 会处理重定位，所以通用 dump 产物通常可用。

### Q5：壳更新后 dump 是否失效？

**是。** 壳更新可能改变解密算法、anti-dump 检测、DEX 结构。需要重新 dump。但这比"每次壳更新都修复一堆 hook"要简单得多——dump 流程本身是通用的，不需要针对特定壳版本定制。

## 十一、方案总体评估

| 维度 | 评分 | 说明 |
|---|---|---|
| 技术可行性 | ✅ 高 | dump + rebuild 是成熟范式（FART、FDex2、Frida dump 脚本验证过） |
| 架构设计 | ✅ 好 | 分层清晰，复用现有组件（StubBuilder 管线、DexPatcher、ManifestParser） |
| 风险识别 | ✅ 完整 | 方法抽取、签名校验、manifest 清理的风险已识别并有应对 |
| 工作量 | ⚠️ 偏乐观 | P0 合理，但 P2 manifest 处理 + P4 签名校验可能比预期复杂 |
| 投入产出比 | ✅ 高 | 一次 dump 后 clean 加载路径大幅简化，后续维护成本低 |

### 关键决策点

```
P0 验证结果
  ├── DEX dump 成功 + 类列表完整 → 继续 P1-P4
  ├── DEX dump 成功但类不完整 → 需要 FART 式遍历 + 多轮 dump（增加 2-3 天）
  └── DEX dump 失败 → 方案不可行，需要另寻出路
```

### 与旧方案的关系

dump + rebuild **不是替代**现有 hook 链路，而是**建立在其之上**：

1. 现有 hook 链路让壳完成解密（这是 dump 的前提）
2. dump 捕获解密产物
3. rebuild 生成干净 APK
4. clean 加载路径用精简版 hook 加载干净 APK

现有代码（ClassLoader 替换、资源伪装、Context wrapper、签名校验 hook）在 clean 加载路径中仍然需要。只是不再需要对抗壳的反检测（GOT hook、maps 过滤、FindClass hook 等）。
