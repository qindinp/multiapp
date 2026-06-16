package de.robv.android.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import dalvik.system.InMemoryDexClassLoader
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile


class ModuleLoader(
    private val context: Context,
    private val bridgeImpl: XposedBridgeImpl
) {
    companion object {
        private const val TAG = "ModuleLoader"
        private const val XPOSED_INIT = "assets/xposed_init"
        private const val NATIVE_INIT = "assets/native_init"
    }

    private val loadedModules = mutableListOf<XC_LoadPackage>()

    fun loadModule(apkPath: String, classLoader: ClassLoader): Boolean {
        return try {
            Timber.tag(TAG).i("Loading Xposed module: $apkPath")

            val initClasses = readXposedInit(apkPath)
            val nativeLibs = readNativeInit(apkPath)

            if (initClasses.isEmpty() && nativeLibs.isEmpty()) {
                Timber.tag(TAG).w("No xposed_init or native_init found in $apkPath")
                return false
            }

            if (nativeLibs.isNotEmpty()) {
                loadNativeLibraries(apkPath, nativeLibs, classLoader)
            }

            if (initClasses.isEmpty()) return true

            val dexBytes = extractDex(apkPath)
            if (dexBytes.isEmpty()) {
                Timber.tag(TAG).w("No DEX files found in $apkPath")
                return false
            }

            val buffer = ByteBuffer.wrap(dexBytes[0])
            val moduleClassLoader = InMemoryDexClassLoader(buffer, classLoader)

            for (className in initClasses) {
                try {
                    val clazz = Class.forName(className.trim(), true, moduleClassLoader)
                    val instance = clazz.getDeclaredConstructor().newInstance()

                    if (instance is IXposedHookLoadPackage) {
                        val loadPackage = object : XC_LoadPackage() {
                            override fun handleLoadPackage(lpparam: LoadPackageParam) {
                                try {
                                    instance.handleLoadPackage(lpparam)
                                } catch (e: Throwable) {
                                    Timber.tag(TAG).e(e, "Error in handleLoadPackage for $className")
                                }
                            }
                        }
                        loadedModules.add(loadPackage)
                        Timber.tag(TAG).i("Loaded module class: $className (IXposedHookLoadPackage)")
                    } else if (instance is XC_LoadPackage) {
                        loadedModules.add(instance)
                        Timber.tag(TAG).i("Loaded module class: $className (XC_LoadPackage)")
                    } else {
                        Timber.tag(TAG).w("Module class $className is not a recognized Xposed module type")
                    }
                } catch (e: Throwable) {
                    Timber.tag(TAG).e(e, "Failed to load module class: $className")
                }
            }

            true
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to load module: $apkPath")
            false
        }
    }

    fun dispatchLoadPackage(
        packageName: String,
        processName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo,
        isFirstApplication: Boolean = true
    ) {
        val lpparam = XC_LoadPackage.LoadPackageParam().apply {
            this.packageName = packageName
            this.processName = processName
            this.classLoader = classLoader
            this.appInfo = appInfo
            this.isFirstApplication = isFirstApplication
        }

        for (module in loadedModules) {
            try {
                module.handleLoadPackage(lpparam)
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Error dispatching loadPackage")
            }
        }
    }

    fun getLoadedModuleCount(): Int = loadedModules.size

    private fun readXposedInit(apkPath: String): List<String> {
        return try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(XPOSED_INIT) ?: return emptyList()
                zip.getInputStream(entry).bufferedReader().readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to read xposed_init from $apkPath")
            emptyList()
        }
    }

    private fun extractDex(apkPath: String): List<ByteArray> {
        return try {
            val dexFiles = mutableListOf<ByteArray>()
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".dex")) {
                        dexFiles.add(zip.getInputStream(entry).readBytes())
                    }
                }
            }
            dexFiles
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to extract DEX from $apkPath")
            emptyList()
        }
    }

    private fun readNativeInit(apkPath: String): List<String> {
        return try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(NATIVE_INIT) ?: return emptyList()
                zip.getInputStream(entry).bufferedReader().readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.trim() }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to read native_init from $apkPath")
            emptyList()
        }
    }

    private fun loadNativeLibraries(apkPath: String, nativeLibs: List<String>, classLoader: ClassLoader) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        for (libName in nativeLibs) {
            val soName = if (libName.startsWith("lib") && libName.endsWith(".so")) {
                libName
            } else {
                "lib${libName}.so"
            }

            val libFile = File(nativeDir, soName)
            if (libFile.exists()) {
                try {
                    System.load(libFile.absolutePath)
                    Timber.tag(TAG).i("Loaded native library: $soName")
                } catch (e: Throwable) {
                    Timber.tag(TAG).e(e, "Failed to load native library: $soName")
                }
            } else {
                val extracted = File(context.cacheDir, soName)
                try {
                    ZipFile(apkPath).use { zip ->
                        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                        val entryPath = "lib/$abi/$soName"
                        val entry = zip.getEntry(entryPath) ?: run {
                            Timber.tag(TAG).w("Native lib not found in APK: $entryPath")
                            return@use
                        }
                        zip.getInputStream(entry).use { input ->
                            extracted.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (extracted.exists()) {
                        System.load(extracted.absolutePath)
                        Timber.tag(TAG).i("Loaded extracted native library: $soName")
                    }
                } catch (e: Throwable) {
                    Timber.tag(TAG).e(e, "Failed to extract/load native library: $soName")
                }
            }
        }
    }
}

interface IXposedHookLoadPackage {
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam)
}
