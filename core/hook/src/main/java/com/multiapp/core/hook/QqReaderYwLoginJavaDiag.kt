package com.multiapp.core.hook

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ExecutorService

object QqReaderYwLoginJavaDiag {
    private const val TAG = "QqReaderYwLoginJavaDiag"

    @Volatile
    private var installed = false

    @Volatile
    private var injectedSignCallback: Any? = null

    private const val QQ_READER_C_VERSION = "qqreader_8.5.1.0888_android"

    fun install(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        if (installed) return true
        if (isDisabled("debug.multiapp.ywlogin.java_diag")) {
            Log.d(TAG, "java_diag disabled")
            return false
        }

        return try {
            val ywLoginClass = Class.forName("com.yuewen.ywlogin.YWLogin", false, classLoader)
            val callbackClass = Class.forName("com.yuewen.ywlogin.login.YWCallBack", false, classLoader)

            val results = listOf(
                hookYwLoginInit(hookEngine, classLoader, ywLoginClass),
                hookSetParamsSignCallback(hookEngine, ywLoginClass),
                hookYwHttpPost(hookEngine, classLoader),
                hookOkHttpNewCall(hookEngine, classLoader),
                hookPwdLogin(hookEngine, classLoader, ywLoginClass, callbackClass),
                hookSendPhoneCode(hookEngine, classLoader, ywLoginClass, callbackClass),
                hookPhoneLogin(hookEngine, classLoader, ywLoginClass, callbackClass),
                hookLoginResponseParser(hookEngine, classLoader, callbackClass),
                hookLoginErrorDispatcher(hookEngine, classLoader, callbackClass)
            )
            val ok = results.any { it }
            installed = ok
            Log.i(TAG, "java login diag installed=$ok results=$results")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "java login diag install failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookOkHttpNewCall(
        hookEngine: HookEngine,
        classLoader: ClassLoader
    ): Boolean {
        return try {
            val clientClass = Class.forName("okhttp3.OkHttpClient", false, classLoader)
            val requestClass = Class.forName("okhttp3.Request", false, classLoader)
            val method = clientClass.getDeclaredMethod("newCall", requestClass)
            method.isAccessible = true
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                describePtLoginRequest(args.getOrNull(0))?.let { Log.w(TAG, it) }
                callOriginal(args)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "OkHttpClient.newCall hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookYwLoginInit(
        hookEngine: HookEngine,
        classLoader: ClassLoader,
        ywLoginClass: Class<*>
    ): Boolean {
        return try {
            val hostTypeClass = Class.forName("com.yuewen.ywlogin.HostType", false, classLoader)
            val callbackClass = Class.forName("com.yuewen.ywlogin.callbacks.DefaultYWCallback", false, classLoader)
            val methods = listOf(
                ywLoginClass.getDeclaredMethod(
                    "init",
                    Application::class.java,
                    ContentValues::class.java,
                    hostTypeClass
                ),
                ywLoginClass.getDeclaredMethod(
                    "init",
                    Application::class.java,
                    ContentValues::class.java,
                    hostTypeClass,
                    callbackClass
                )
            )
            methods.forEach { method ->
                method.isAccessible = true
                hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                    Log.w(
                        TAG,
                        "YWLogin.init before app=${args.getOrNull(0)?.javaClass?.name} " +
                            "host=${args.getOrNull(2)} params=${summarizeContentValues(args.getOrNull(1))}"
                    )
                    val result = callOriginal(args)
                    Log.w(TAG, "YWLogin.init after host=${args.getOrNull(2)}")
                    ensureParamsSignCallback(classLoader, ywLoginClass, "YWLogin.init")
                    result
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "YWLogin.init hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookSetParamsSignCallback(
        hookEngine: HookEngine,
        ywLoginClass: Class<*>
    ): Boolean {
        return try {
            val callbackClass = Class.forName(
                "com.yuewen.ywlogin.login.ParamsSignCallback",
                false,
                ywLoginClass.classLoader
            )
            val method = ywLoginClass.getDeclaredMethod("setParamsSignCallback", callbackClass)
            method.isAccessible = true
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                Log.w(TAG, "YWLogin.setParamsSignCallback callback=${args.getOrNull(0)?.javaClass?.name}")
                val result = callOriginal(args)
                if (args.getOrNull(0) != null) {
                    injectedSignCallback = args[0]
                }
                result
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "YWLogin.setParamsSignCallback hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookYwHttpPost(
        hookEngine: HookEngine,
        classLoader: ClassLoader
    ): Boolean {
        return try {
            val httpClass = Class.forName("com.yuewen.ywlogin.network.YWHttp", false, classLoader)
            val method = httpClass.getDeclaredMethod(
                "post",
                String::class.java,
                ContentValues::class.java
            )
            method.isAccessible = true
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                Log.w(
                    TAG,
                    "YWHttp.post before url=${safeUrl(args.getOrNull(0) as? String)} " +
                        "params=${summarizeContentValues(args.getOrNull(1))}"
                )
                val result = callOriginal(args)
                Log.w(TAG, "YWHttp.post after ${describeResponse(result)}")
                result
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "YWHttp.post hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookPwdLogin(
        hookEngine: HookEngine,
        classLoader: ClassLoader,
        ywLoginClass: Class<*>,
        callbackClass: Class<*>
    ): Boolean {
        val method = ywLoginClass.getDeclaredMethod(
            "pwdLogin",
            Activity::class.java,
            String::class.java,
            String::class.java,
            callbackClass
        )
        method.isAccessible = true
        return hookVoidLoginMethod(
            hookEngine,
            method,
                "pwdLogin",
                describe = { args ->
                    "activity=${args.getOrNull(0)?.javaClass?.name} account=${mask(args.getOrNull(1))} " +
                    "passwordLen=${(args.getOrNull(2) as? String)?.length ?: -1} callback=${args.getOrNull(3)?.javaClass?.name}"
            },
            fallback = { args -> runPwdLoginJavaTask(classLoader, callbackClass, args) }
        )
    }

    private fun hookSendPhoneCode(
        hookEngine: HookEngine,
        classLoader: ClassLoader,
        ywLoginClass: Class<*>,
        callbackClass: Class<*>
    ): Boolean {
        val method = ywLoginClass.getDeclaredMethod(
            "sendPhoneCode",
            Context::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            callbackClass
        )
        method.isAccessible = true
        return hookVoidLoginMethod(
            hookEngine,
            method,
            "sendPhoneCode",
            describe = { args ->
                "context=${args.getOrNull(0)?.javaClass?.name} phone=${mask(args.getOrNull(1))} " +
                    "type=${args.getOrNull(2)} scene=${args.getOrNull(3)} callback=${args.getOrNull(4)?.javaClass?.name}"
            },
            fallback = { args -> runSendPhoneCodeJavaTask(classLoader, callbackClass, args) }
        )
    }

    private fun hookPhoneLogin(
        hookEngine: HookEngine,
        classLoader: ClassLoader,
        ywLoginClass: Class<*>,
        callbackClass: Class<*>
    ): Boolean {
        val method = ywLoginClass.getDeclaredMethod(
            "phoneLogin",
            String::class.java,
            String::class.java,
            String::class.java,
            callbackClass
        )
        method.isAccessible = true
        return hookVoidLoginMethod(
            hookEngine,
            method,
            "phoneLogin",
            describe = { args ->
                "phone=${mask(args.getOrNull(0))} codeLen=${(args.getOrNull(1) as? String)?.length ?: -1} " +
                    "area=${args.getOrNull(2)} callback=${args.getOrNull(3)?.javaClass?.name}"
            },
            fallback = { args -> runPhoneLoginJavaTask(classLoader, callbackClass, args) }
        )
    }

    private fun hookVoidLoginMethod(
        hookEngine: HookEngine,
        method: Method,
        name: String,
        describe: (Array<Any?>) -> String,
        fallback: ((Array<Any?>) -> Boolean)? = null
    ): Boolean {
        return try {
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                Log.w(TAG, "$name before ${describe(args)}")
                try {
                    callOriginal(args)
                    Log.i(TAG, "$name original returned")
                } catch (t: Throwable) {
                    if (!isMissingNative(t)) throw t
                    Log.e(TAG, "$name native missing; suppressing process crash and notifying callback: ${t.message}", t)
                    val handled = fallback?.invoke(args) == true
                    if (handled) {
                        Log.w(TAG, "$name java fallback scheduled")
                    } else {
                        notifyError(args.lastOrNull(), -90101, "QQ Reader login native is not registered")
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$name hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookLoginResponseParser(
        hookEngine: HookEngine,
        classLoader: ClassLoader,
        callbackClass: Class<*>
    ): Boolean {
        return try {
            val parserClass = Class.forName("b.a.a.search.qdaa", false, classLoader)
            val responseClass = Class.forName("com.yuewen.ywlogin.network.YWHttpResponse", false, classLoader)
            val method = parserClass.getDeclaredMethod(
                "search",
                responseClass,
                Handler::class.java,
                callbackClass,
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                Log.w(TAG, "response parser before ${describeResponse(args.getOrNull(0))} requiredData=${args.getOrNull(3)}")
                val result = try {
                    callOriginal(args)
                } catch (t: Throwable) {
                    Log.e(TAG, "response parser original failed: ${rootMessage(t)} ${describeResponse(args.getOrNull(0))}", t)
                    throw t
                }
                Log.w(TAG, "response parser after result=$result ${describeResponse(args.getOrNull(0))}")
                result
            }
        } catch (t: Throwable) {
            Log.w(TAG, "response parser hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookLoginErrorDispatcher(
        hookEngine: HookEngine,
        classLoader: ClassLoader,
        callbackClass: Class<*>
    ): Boolean {
        return try {
            val parserClass = Class.forName("b.a.a.search.qdaa", false, classLoader)
            val method = parserClass.getDeclaredMethod(
                "search",
                Int::class.javaPrimitiveType,
                String::class.java,
                Handler::class.java,
                callbackClass
            )
            method.isAccessible = true
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                Log.w(TAG, "login error dispatch code=${args.getOrNull(0)} message=${safeText(args.getOrNull(1))}")
                callOriginal(args)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "login error dispatcher hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun runSendPhoneCodeJavaTask(
        classLoader: ClassLoader,
        callbackClass: Class<*>,
        args: Array<Any?>
    ): Boolean {
        val context = args.getOrNull(0) as? Context
        val phone = args.getOrNull(1) as? String
        val type = args.getOrNull(2) as? Int ?: 0
        val scene = args.getOrNull(3) as? Int ?: 0
        val callback = args.getOrNull(4)
        if (context == null || phone == null || callback == null) {
            Log.w(TAG, "sendPhoneCode java fallback skipped: invalid args")
            return false
        }

        return try {
            val managerClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager", true, classLoader)
            ensureParamsSignCallback(classLoader, managerClass, "sendPhoneCode fallback")
            val manager = getYwLoginManager(managerClass) ?: return false
            val ywGuid = getDefaultParamString(manager, "ywguid").orEmpty()
            val ywKey = getDefaultParamString(manager, "ywkey").orEmpty()
            val normalizedPhone = normalizeMainlandPhone(phone)
            Log.w(
                TAG,
                "sendPhoneCode java fallback params normalized=${normalizedPhone != phone} " +
                    "ywguid=${ywGuid.isNotBlank()} ywkey=${ywKey.isNotBlank()}"
            )
            val taskClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager\$c", true, classLoader)
            val ctor = taskClass.getDeclaredConstructor(
                managerClass,
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Handler::class.java,
                callbackClass,
                Context::class.java
            )
            ctor.isAccessible = true
            val task = ctor.newInstance(
                manager,
                ywGuid,
                ywKey,
                normalizedPhone,
                type,
                "",
                "",
                "",
                scene,
                Handler(Looper.getMainLooper()),
                callback,
                context
            )
            executeYwLoginTask(classLoader, taskClass, task, callback, "sendPhoneCode")
        } catch (t: Throwable) {
            Log.e(TAG, "sendPhoneCode java fallback failed: ${rootMessage(t)}", t)
            false
        }
    }

    private fun runPhoneLoginJavaTask(
        classLoader: ClassLoader,
        callbackClass: Class<*>,
        args: Array<Any?>
    ): Boolean {
        val phone = args.getOrNull(0) as? String
        val code = args.getOrNull(1) as? String
        val callback = args.getOrNull(3)
        if (phone == null || code == null || callback == null) {
            Log.w(TAG, "phoneLogin java fallback skipped: invalid args")
            return false
        }

        val sessionKey = findCallbackOwnerString(callback, "mSessionKey")
        if (sessionKey.isNullOrBlank()) {
            Log.w(TAG, "phoneLogin java fallback skipped: missing sessionKey on callback owner")
            notifyError(callback, -90103, "QQ Reader phone login sessionKey is missing")
            return true
        }

        return try {
            val managerClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager", true, classLoader)
            ensureParamsSignCallback(classLoader, managerClass, "phoneLogin fallback")
            val manager = getYwLoginManager(managerClass) ?: return false
            val ywGuid = getDefaultParamString(manager, "ywguid").orEmpty()
            val ywKey = getDefaultParamString(manager, "ywkey").orEmpty()
            val normalizedPhone = normalizeMainlandPhone(phone)
            Log.w(
                TAG,
                "phoneLogin java fallback params normalized=${normalizedPhone != phone} " +
                    "ywguid=${ywGuid.isNotBlank()} ywkey=${ywKey.isNotBlank()} sessionKey=${sessionKey.isNotBlank()}"
            )
            val taskClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager\$z", true, classLoader)
            val ctor = taskClass.getDeclaredConstructor(
                managerClass,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Handler::class.java,
                callbackClass
            )
            ctor.isAccessible = true
            val task = ctor.newInstance(
                manager,
                ywGuid,
                ywKey,
                normalizedPhone,
                code,
                sessionKey,
                Handler(Looper.getMainLooper()),
                callback
            )
            executeYwLoginTask(classLoader, taskClass, task, callback, "phoneLogin")
        } catch (t: Throwable) {
            Log.e(TAG, "phoneLogin java fallback failed: ${rootMessage(t)}", t)
            false
        }
    }

    private fun runPwdLoginJavaTask(
        classLoader: ClassLoader,
        callbackClass: Class<*>,
        args: Array<Any?>
    ): Boolean {
        val activity = args.getOrNull(0) as? Activity
        val account = args.getOrNull(1) as? String
        val password = args.getOrNull(2) as? String
        val callback = args.getOrNull(3)
        if (activity == null || account == null || password == null || callback == null) {
            Log.w(TAG, "pwdLogin java fallback skipped: invalid args")
            return false
        }

        return try {
            val managerClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager", true, classLoader)
            ensureParamsSignCallback(classLoader, managerClass, "pwdLogin fallback")
            val manager = getYwLoginManager(managerClass) ?: return false
            var ywGuid = getDefaultParamString(manager, "ywguid").orEmpty()
            var ywKey = getDefaultParamString(manager, "ywkey").orEmpty()

            // 如果 getDefaultParameters 中没有 ywguid/ywkey，从登录会话管理类获取
            if (ywGuid.isBlank()) {
                ywGuid = resolveYwGuid(classLoader).orEmpty()
                Log.w(TAG, "pwdLogin fallback: ywguid from qdad=$ywGuid")
            }
            if (ywKey.isBlank()) {
                ywKey = resolveYwKey(classLoader).orEmpty()
                Log.w(TAG, "pwdLogin fallback: ywkey from qdab=$ywKey")
            }
            Log.w(TAG, "pwdLogin java fallback params ywguid=${ywGuid.isNotBlank()} ywkey=${ywKey.isNotBlank()}")

            val taskClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager\$g0", true, classLoader)
            val ctor = taskClass.getDeclaredConstructor(
                managerClass,
                String::class.java,
                String::class.java,
                Activity::class.java,
                Handler::class.java,
                callbackClass
            )
            ctor.isAccessible = true
            val task = ctor.newInstance(manager, account, password, activity, Handler(Looper.getMainLooper()), callback)
            executeYwLoginTask(classLoader, taskClass, task, callback, "pwdLogin")
        } catch (t: Throwable) {
            Log.e(TAG, "pwdLogin java fallback failed: ${rootMessage(t)}", t)
            false
        }
    }

    private fun getYwLoginManager(managerClass: Class<*>): Any? {
        val getInstance = managerClass.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        val manager = getInstance.invoke(null)
        if (manager == null) {
            Log.w(TAG, "java fallback skipped: YWLoginManager.getInstance returned null")
        }
        return manager
    }

    private fun ensureParamsSignCallback(
        classLoader: ClassLoader,
        sourceClass: Class<*>,
        reason: String
    ): Boolean {
        return try {
            val managerClass = Class.forName("com.yuewen.ywlogin.login.YWLoginManager", true, classLoader)
            val manager = getYwLoginManager(managerClass) ?: return false
            val getSignCallback = managerClass.getDeclaredMethod("getSignCallback")
            getSignCallback.isAccessible = true
            val existing = getSignCallback.invoke(manager)
            if (existing != null) {
                injectedSignCallback = existing
                Log.w(TAG, "ParamsSignCallback already present reason=$reason callback=${existing.javaClass.name}")
                return true
            }

            val callbackClass = Class.forName("com.yuewen.ywlogin.login.ParamsSignCallback", true, classLoader)
            val callback = injectedSignCallback ?: createParamsSignCallback(classLoader, callbackClass).also {
                injectedSignCallback = it
            }
            val setOnManager = managerClass.getDeclaredMethod("setSignCallback", callbackClass)
            setOnManager.isAccessible = true
            setOnManager.invoke(manager, callback)

            val ywLoginClass = if (sourceClass.name == "com.yuewen.ywlogin.YWLogin") {
                sourceClass
            } else {
                Class.forName("com.yuewen.ywlogin.YWLogin", true, classLoader)
            }
            runCatching {
                val setOnFacade = ywLoginClass.getDeclaredMethod("setParamsSignCallback", callbackClass)
                setOnFacade.isAccessible = true
                setOnFacade.invoke(null, callback)
            }.onFailure {
                Log.w(TAG, "YWLogin.setParamsSignCallback reflective apply failed: ${it.javaClass.simpleName}: ${it.message}")
            }

            Log.w(TAG, "ParamsSignCallback injected reason=$reason callback=${callback.javaClass.name}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ParamsSignCallback inject failed reason=$reason: ${rootMessage(t)}", t)
            false
        }
    }

    private fun createParamsSignCallback(classLoader: ClassLoader, callbackClass: Class<*>): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "signParams" -> {
                    signYwLoginParams(classLoader, args)
                    null
                }
                "toString" -> "MultiAppParamsSignCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            handler
        )
    }

    private fun signYwLoginParams(classLoader: ClassLoader, args: Array<Any?>?) {
        val builder = args?.getOrNull(1)
        val url = args?.getOrNull(2) as? String
        val values = args?.getOrNull(3) as? ContentValues
        if (builder == null || values == null) {
            Log.w(TAG, "signParams skipped builder=${builder != null} values=${values != null}")
            return
        }

        val ttime = System.currentTimeMillis().toString()
        val qrsn = resolveQrsn(classLoader)
        val versionCode = resolveVersionCode(classLoader)
        val base = buildSignBase(values, ttime, qrsn)
        val sign = signWithQqReaderCompat(classLoader, base)
        addHeader(builder, "qrsn", qrsn)
        addHeader(builder, "c_version", QQ_READER_C_VERSION)
        if (versionCode.isNotBlank()) {
            addHeader(builder, "version_code", versionCode)
        }
        addHeader(builder, "ttime", ttime)
        addHeader(builder, "ssign_version", "1")
        addHeader(builder, "ssign", sign.ifBlank { "-1" })
        addHeader(builder, "signature", sign)
        addHeader(builder, "sign", sign)
        addHeader(builder, "trace_id", UUID.randomUUID().toString().replace("-", "_"))
        Log.w(
            TAG,
            "signParams applied url=${safeUrl(url)} rawLen=${base.length} " +
                "keys=${values.valueSet().map { it.key }.sorted().take(40)} qrsn=${qrsn.isNotBlank()} " +
                "cVersion=true versionCode=${versionCode.isNotBlank()} signLen=${sign.length}"
        )
    }

    private fun buildSignBase(values: ContentValues, ttime: String, qrsn: String): String {
        val map = TreeMap<String, String>()
        values.valueSet().forEach { entry ->
            val key = entry.key ?: return@forEach
            if (key.equals("sign", true) || key.equals("signature", true)) return@forEach
            map[key] = entry.value?.toString().orEmpty()
        }
        if (qrsn.isNotBlank()) {
            map["qrsn"] = qrsn
        }
        map["c_version"] = QQ_READER_C_VERSION
        map["ttime"] = ttime
        return map.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    private fun resolveQrsn(classLoader: ClassLoader): String {
        val app = currentApplication() ?: return ""
        return try {
            val cls = Class.forName("com.qq.reader.common.judian.qdac\$qdac", true, classLoader)
            val method = cls.getDeclaredMethod("judian", Context::class.java)
            method.isAccessible = true
            (method.invoke(null, app) as? String).orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "resolve qrsn failed: ${rootMessage(t)}")
            ""
        }
    }

    /** 从 com.qq.reader.common.login.qdad.b().d() 获取 ywguid */
    private fun resolveYwGuid(classLoader: ClassLoader): String {
        return try {
            val qdadClass = Class.forName("com.qq.reader.common.login.qdad", true, classLoader)
            val bMethod = qdadClass.getDeclaredMethod("b")
            bMethod.isAccessible = true
            val instance = bMethod.invoke(null) ?: return ""
            for (methodName in listOf("d", "e", "c", "b")) {
                try {
                    val m = instance.javaClass.getDeclaredMethod(methodName)
                    m.isAccessible = true
                    val result = m.invoke(instance) as? String
                    if (!result.isNullOrBlank()) return result
                } catch (_: Throwable) {}
            }
            // 如果所有方法都返回空，尝试从 qimei 生成
            val qimei = resolveQimei(classLoader)
            if (qimei.isNotBlank()) {
                Log.w(TAG, "resolveYwGuid: using qimei as fallback")
                return qimei
            }
            ""
        } catch (t: Throwable) {
            Log.w(TAG, "resolveYwGuid failed: ${rootMessage(t)}")
            ""
        }
    }

    private fun resolveQimei(classLoader: ClassLoader): String {
        return try {
            val app = currentApplication() ?: return ""
            val cls = Class.forName("com.qq.reader.common.utils.qdbb", true, classLoader)
            val method = cls.getDeclaredMethod("search", Context::class.java)
            method.isAccessible = true
            (method.invoke(null, app) as? String).orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "resolveQimei failed: ${rootMessage(t)}")
            ""
        }
    }

    /** 从 com.qq.reader.common.login.qdad.b().e() 或类似方法获取 ywkey */
    private fun resolveYwKey(classLoader: ClassLoader): String {
        return try {
            val qdadClass = Class.forName("com.qq.reader.common.login.qdad", true, classLoader)
            val bMethod = qdadClass.getDeclaredMethod("b")
            bMethod.isAccessible = true
            val instance = bMethod.invoke(null) ?: return ""
            // 尝试常见的方法名获取 ywkey
            for (methodName in listOf("e", "f", "c", "b")) {
                try {
                    val m = instance.javaClass.getDeclaredMethod(methodName)
                    m.isAccessible = true
                    val result = m.invoke(instance) as? String
                    if (!result.isNullOrBlank()) return result
                } catch (_: Throwable) {}
            }
            ""
        } catch (t: Throwable) {
            Log.w(TAG, "resolveYwKey failed: ${rootMessage(t)}")
            ""
        }
    }

    private fun describePtLoginRequest(request: Any?): String? {
        if (request == null) return null
        return try {
            val url = callNoArg(request, "url")?.toString().orEmpty()
            if (!url.startsWith("https://ptlogin.yuewen.com/")) return null
            val method = callNoArg(request, "method")
            val headers = callNoArg(request, "headers")
            val names = listOf(
                "qrsn", "c_version", "version_code", "ttime",
                "ssign", "ssign_version", "signature", "sign", "trace_id", "Cookie"
            )
            val headerState = names.joinToString(prefix = "{", postfix = "}") { name ->
                "$name=${presence(invokeHeaderGet(headers, name), true)}"
            }
            val bodyStr = extractRequestBody(request)
            "OkHttp.newCall ptlogin method=$method url=${safeUrl(url)} headers=$headerState body=$bodyStr"
        } catch (t: Throwable) {
            "OkHttp.newCall ptlogin describe failed: ${rootMessage(t)}"
        }
    }

    private fun extractRequestBody(request: Any): String {
        return try {
            val body = callNoArg(request, "body") ?: return "<no body>"
            val className = body.javaClass.name
            if (className.contains("FormBody")) {
                val size = (callNoArg(body, "size") as? Int) ?: 0
                val pairs = (0 until size).mapNotNull { i ->
                    val key = try { body.javaClass.getDeclaredMethod("name", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(body, i) as? String } catch (_: Throwable) { null }
                    val value = try { body.javaClass.getDeclaredMethod("value", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(body, i) as? String } catch (_: Throwable) { null }
                    if (key != null) "$key=${value?.take(30)}" else null
                }
                return "FormBody[${pairs.joinToString(", ")}]"
            }
            val contentLength = try { body.javaClass.getDeclaredMethod("contentLength").apply { isAccessible = true }.invoke(body) as? Long } catch (_: Throwable) { -1L }
            "type=$className len=$contentLength"
        } catch (t: Throwable) {
            "<extract failed: ${rootMessage(t)}>"
        }
    }

    private fun invokeHeaderGet(headers: Any?, name: String): String? {
        if (headers == null) return null
        return try {
            val method = headers.javaClass.getDeclaredMethod("get", String::class.java)
            method.isAccessible = true
            method.invoke(headers, name) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveVersionCode(classLoader: ClassLoader): String {
        val app = currentApplication() ?: return ""
        return try {
            val cls = Class.forName("com.yuewen.baseutil.qdac", true, classLoader)
            val method = cls.getDeclaredMethod("judian", Context::class.java)
            method.isAccessible = true
            method.invoke(null, app)?.toString().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "resolve version_code failed: ${rootMessage(t)}")
            ""
        }
    }

    private fun currentApplication(): Context? {
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            val method = cls.getDeclaredMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (t: Throwable) {
            Log.w(TAG, "currentApplication failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun signWithQqReaderCompat(classLoader: ClassLoader, base: String): String {
        val attempts = listOf(
            "com.multiapp.core.loader.QqReaderSignCompat" to "sign",
            "com.qq.reader.qrencrypt.fock.qdaa" to "search",
            "com.yuewen.fockrt.FockRT" to "sn",
            "com.yuewen.fock.Fock" to "sign"
        )
        for ((className, methodName) in attempts) {
            try {
                val cls = Class.forName(className, true, classLoader)
                val method = cls.getDeclaredMethod(methodName, String::class.java)
                method.isAccessible = true
                val result = method.invoke(null, base) as? String
                if (!result.isNullOrBlank()) {
                    Log.w(TAG, "signParams signed via $className.$methodName rawLen=${base.length} signLen=${result.length}")
                    return result
                }
            } catch (t: Throwable) {
                Log.w(TAG, "signParams signer failed $className.$methodName: ${rootMessage(t)}")
            }
        }
        return ""
    }

    private fun addHeader(builder: Any, name: String, value: String) {
        try {
            val method = builder.javaClass.getDeclaredMethod("addHeader", String::class.java, String::class.java)
            method.isAccessible = true
            method.invoke(builder, name, value)
        } catch (t: Throwable) {
            Log.w(TAG, "addHeader failed $name: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun getDefaultParamString(manager: Any, key: String): String? {
        return try {
            val method = manager.javaClass.getDeclaredMethod("getDefaultParameters")
            method.isAccessible = true
            val values = method.invoke(manager) as? ContentValues
            values?.getAsString(key)
        } catch (t: Throwable) {
            Log.w(TAG, "default param $key unavailable: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun normalizeMainlandPhone(phone: String): String {
        return when {
            phone.startsWith("+86") && phone.length > 3 -> phone.substring(3)
            phone.startsWith("0086") && phone.length > 4 -> phone.substring(4)
            phone.startsWith("86") && phone.length == 13 -> phone.substring(2)
            else -> phone
        }
    }

    private fun executeYwLoginTask(
        classLoader: ClassLoader,
        taskClass: Class<*>,
        task: Any,
        callback: Any?,
        name: String
    ): Boolean {
        val runMethod = taskClass.getDeclaredMethod("run")
        runMethod.isAccessible = true
        val command = Runnable {
            try {
                Log.w(TAG, "$name java task running")
                runMethod.invoke(task)
                Log.w(TAG, "$name java task returned")
            } catch (t: Throwable) {
                Log.e(TAG, "$name java task failed: ${rootMessage(t)}", t)
                notifyError(callback, -90102, "QQ Reader Java login task failed")
            }
        }

        val pool = runCatching {
            val poolClass = Class.forName("com.yuewen.ywlogin.network.YWThreadPool", true, classLoader)
            val getPool = poolClass.getDeclaredMethod("getInstance", Int::class.javaPrimitiveType)
            getPool.isAccessible = true
            getPool.invoke(null, 2) as? ExecutorService
        }.getOrNull()
        if (pool != null) {
            pool.execute(command)
        } else {
            Thread(command, "multiapp-ywlogin-$name").start()
        }
        return true
    }

    private fun notifyError(callback: Any?, code: Int, message: String) {
        if (callback == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                invokeCallbackError(callback, code, message)
            }
            return
        }
        invokeCallbackError(callback, code, message)
    }

    private fun invokeCallbackError(callback: Any?, code: Int, message: String) {
        if (callback == null) return
        try {
            val onError = callback.javaClass.methods.firstOrNull {
                it.name == "onError" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
            }
            if (onError == null) {
                Log.w(TAG, "callback onError(int,String) not found: ${callback.javaClass.name}")
                return
            }
            onError.isAccessible = true
            onError.invoke(callback, code, message)
            Log.w(TAG, "callback onError invoked code=$code")
        } catch (t: Throwable) {
            Log.e(TAG, "callback onError failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun isMissingNative(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is UnsatisfiedLinkError && cur.message?.contains("No implementation found") == true) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun rootMessage(t: Throwable): String {
        var cur: Throwable = t
        while (cur.cause != null) cur = cur.cause!!
        return "${cur.javaClass.simpleName}: ${cur.message}"
    }

    private fun describeResponse(response: Any?): String {
        if (response == null) return "response=null"
        return try {
            val cls = response.javaClass
            val success = callNoArg(response, "isSuccess")
            val code = callNoArg(response, "getCode")
            val businessCode = callNoArg(response, "getBusinessCode")
            val originUrl = safeUrl(callNoArg(response, "getOriginUrl") as? String)
            val lastActionUrl = safeUrl(callNoArg(response, "getLastActionUrl") as? String)
            val json = callNoArg(response, "getJson") as? JSONObject
            val data = json?.optJSONObject("data")
            val businessData = callNoArg(response, "getBusinessData") as? JSONObject
            val jsonSummary = if (json == null) {
                "json=null"
            } else {
                "apiCode=${json.optInt("code", Int.MIN_VALUE)} " +
                    "message=${safeText(json.optString("message", ""))} " +
                    "jsonKeys=${json.keysList()} data=${data.safeSummary()}"
            }
            "responseClass=${cls.name} success=$success code=$code businessCode=$businessCode " +
                "origin=$originUrl lastAction=$lastActionUrl $jsonSummary businessData=${businessData.safeSummary()}"
        } catch (t: Throwable) {
            "responseDescribeFailed=${t.javaClass.simpleName}: ${safeText(t.message)}"
        }
    }

    private fun callNoArg(target: Any, name: String): Any? {
        return try {
            val method = target.javaClass.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findCallbackOwnerString(callback: Any, fieldName: String): String? {
        return try {
            val ownerField = callback.javaClass.getDeclaredField("this$0")
            ownerField.isAccessible = true
            val owner = ownerField.get(callback) ?: return null
            val field = owner.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(owner) as? String
        } catch (t: Throwable) {
            Log.w(TAG, "callback owner field $fieldName not found: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun JSONObject?.safeSummary(): String {
        if (this == null) return "null"
        val nextAction = optInt("nextAction", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        return "keys=${keysList()} nextAction=${nextAction ?: "none"} " +
            "hasYwGuid=${has("ywGuid")} hasYwKey=${has("ywKey")} hasTicket=${has("ticket")} " +
            "hasToken=${has("token")} hasPhoneKey=${has("phone_key")}"
    }

    private fun JSONObject.keysList(): String {
        val names = mutableListOf<String>()
        val iterator = keys()
        while (iterator.hasNext() && names.size < 20) {
            names += iterator.next()
        }
        return names.joinToString(prefix = "[", postfix = "]")
    }

    private fun safeUrl(value: String?): String {
        if (value.isNullOrBlank()) return "null"
        return value.substringBefore('?').take(160)
    }

    private fun summarizeContentValues(value: Any?): String {
        val values = value as? ContentValues ?: return "null"
        return try {
            val entries = values.valueSet().associate { it.key to it.value }
            val keys = entries.keys.sorted()
            val critical = listOf(
                "appid",
                "areaid",
                "source",
                "qimei",
                "qimei36",
                "ibex",
                "version",
                "devicetype",
                "osversion",
                "sdkversion",
                "auto",
                "autotime",
                "ticket",
                "username",
                "password",
                "ywguid",
                "ywkey",
                "signature",
                "sign"
            ).joinToString(prefix = "{", postfix = "}") { key ->
                "$key=${presence(entries[key], entries.containsKey(key))}"
            }
            "size=${values.size()} keys=${keys.take(40)} critical=$critical"
        } catch (t: Throwable) {
            "ContentValuesSummaryFailed=${t.javaClass.simpleName}: ${safeText(t.message)}"
        }
    }

    private fun presence(value: Any?, exists: Boolean): String {
        if (!exists) return "missing"
        val text = value?.toString()
        return if (text.isNullOrBlank()) "blank" else "present"
    }

    private fun safeText(value: Any?): String {
        val text = value?.toString() ?: return "null"
        return text.replace(Regex("\\s+"), " ").take(180)
    }

    private fun mask(value: Any?): String {
        val text = value as? String ?: return "null"
        if (text.length <= 4) return "***"
        return "${text.take(2)}***${text.takeLast(2)}"
    }

    private fun isDisabled(name: String): Boolean {
        val sys = System.getProperty(name)
        val prop = getProp(name)
        return isFalse(sys) || isFalse(prop)
    }

    private fun isFalse(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value == "0" || value.equals("false", true) || value.equals("off", true)
    }

    private fun getProp(name: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getDeclaredMethod("get", String::class.java, String::class.java)
            method.invoke(null, name, "") as? String
        } catch (_: Throwable) {
            null
        }
    }
}
