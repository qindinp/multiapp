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
    }

    private val loadedModules = mutableListOf<XC_LoadPackage>()

    fun loadModule(apkPath: String, classLoader: ClassLoader): Boolean {
        return try {
            Timber.tag(TAG).i("Loading Xposed module: $apkPath")

            val initClasses = readXposedInit(apkPath)
            if (initClasses.isEmpty()) {
                Timber.tag(TAG).w("No xposed_init found in $apkPath")
                return false
            }

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
}

interface IXposedHookLoadPackage {
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam)
}
