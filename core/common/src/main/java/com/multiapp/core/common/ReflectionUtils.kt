package com.multiapp.core.common

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Safe execution wrapper that logs errors via Timber.
 */
inline fun <T> runSafe(tag: String = "MULTIAPP", block: () -> T): T? {
    return try {
        block()
    } catch (e: Exception) {
        Timber.tag(tag).e(e, "Safe execution failed")
        null
    }
}

/**
 * Safe execution with a default fallback value.
 */
inline fun <T> runSafeOr(tag: String = "MULTIAPP", default: T, block: () -> T): T {
    return try {
        block()
    } catch (e: Exception) {
        Timber.tag(tag).e(e, "Safe execution failed, using default")
        default
    }
}

/**
 * Reflection helper: Get a field value from any object.
 */
fun Any.getField(name: String): Any? = try {
    val field = findField(this::class.java, name)
    field?.isAccessible = true
    field?.get(this)
} catch (e: Exception) {
    Timber.tag("Reflect").e(e, "Failed to get field: $name")
    null
}

/**
 * Reflection helper: Set a field value on any object.
 */
fun Any.setField(name: String, value: Any?): Boolean = try {
    val field = findField(this::class.java, name)
    field?.isAccessible = true
    field?.set(this, value)
    true
} catch (e: Exception) {
    Timber.tag("Reflect").e(e, "Failed to set field: $name")
    false
}

/**
 * Reflection helper: Get a static field value from a class.
 */
fun Class<*>.getStaticField(name: String): Any? = try {
    val field = findField(this, name)
    field?.isAccessible = true
    field?.get(null)
} catch (e: Exception) {
    Timber.tag("Reflect").e(e, "Failed to get static field: $name from ${this.name}")
    null
}

/**
 * Reflection helper: Set a static field value on a class.
 */
fun Class<*>.setStaticField(name: String, value: Any?): Boolean = try {
    val field = findField(this, name)
    field?.isAccessible = true
    // Remove final modifier if needed
    removeFinalModifier(field!!)
    field.set(null, value)
    true
} catch (e: Exception) {
    Timber.tag("Reflect").e(e, "Failed to set static field: $name on ${this.name}")
    false
}

/**
 * Reflection helper: Invoke a method on any object.
 */
fun Any.invokeMethod(
    name: String,
    parameterTypes: Array<Class<*>> = emptyArray(),
    vararg args: Any?
): Any? = try {
    val method = findMethod(this::class.java, name, parameterTypes)
    method?.isAccessible = true
    method?.invoke(this, *args)
} catch (e: Exception) {
    Timber.tag("Reflect").e(e, "Failed to invoke method: $name")
    null
}

/**
 * Reflection helper: Invoke a static method on a class.
 */
fun Class<*>.invokeStaticMethod(
    name: String,
    parameterTypes: Array<Class<*>> = emptyArray(),
    vararg args: Any?
): Any? = try {
    val method = findMethod(this, name, parameterTypes)
    method?.isAccessible = true
    method?.invoke(null, *args)
} catch (e: Exception) {
    Timber.tag("Reflect").e(e, "Failed to invoke static method: $name on ${this.name}")
    null
}

/**
 * Find a field in a class or its superclasses.
 * Falls back to HiddenApiBypass on Android 9+ when standard reflection is blocked.
 */
fun findField(clazz: Class<*>, name: String): Field? {
    // Standard reflection first
    var current: Class<*>? = clazz
    while (current != null) {
        try {
            return current.getDeclaredField(name)
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }

    // Fallback: HiddenApiBypass (Android 9+)
    if (Build.VERSION.SDK_INT >= 28) {
        try {
            var searchClass: Class<*>? = clazz
            while (searchClass != null) {
                // getInstanceFields returns non-static fields
                val instanceFields: List<*>? = HiddenApiBypass.getInstanceFields(searchClass)
                if (instanceFields != null) {
                    for (f in instanceFields) {
                        if (f is Field && f.name == name) {
                            Timber.tag("ReflectionUtils").d("Found instance field '$name' via HiddenApiBypass in ${searchClass.name}")
                            return f
                        }
                    }
                }
                // getStaticFields returns static fields
                val staticFields: List<*>? = HiddenApiBypass.getStaticFields(searchClass)
                if (staticFields != null) {
                    for (f in staticFields) {
                        if (f is Field && f.name == name) {
                            Timber.tag("ReflectionUtils").d("Found static field '$name' via HiddenApiBypass in ${searchClass.name}")
                            return f
                        }
                    }
                }
                searchClass = searchClass.superclass
            }
        } catch (e: Exception) {
            Timber.tag("ReflectionUtils").w(e, "HiddenApiBypass findField failed for '$name'")
        }
    }
    return null
}

/**
 * Find a method in a class or its superclasses.
 * Falls back to HiddenApiBypass on Android 9+ when standard reflection is blocked.
 */
fun findMethod(clazz: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method? {
    // Standard reflection first
    var current: Class<*>? = clazz
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes)
        } catch (_: NoSuchMethodException) {
            current = current.superclass
        }
    }

    // Fallback: HiddenApiBypass (Android 9+)
    if (Build.VERSION.SDK_INT >= 28) {
        try {
            // Try getDeclaredMethod from HiddenApiBypass first (most efficient)
            if (parameterTypes.isNotEmpty()) {
                val method = HiddenApiBypass.getDeclaredMethod(clazz, name, *parameterTypes)
                if (method is Method) {
                    Timber.tag("ReflectionUtils").d("Found method '$name' via HiddenApiBypass.getDeclaredMethod")
                    return method
                }
            }
            // Fall back to scanning all methods
            var searchClass: Class<*>? = clazz
            while (searchClass != null) {
                val methods: List<*>? = HiddenApiBypass.getDeclaredMethods(searchClass)
                if (methods != null) {
                    for (m in methods) {
                        if (m is Method && m.name == name && m.parameterTypes.contentEquals(parameterTypes)) {
                            Timber.tag("ReflectionUtils").d("Found method '$name' via HiddenApiBypass in ${searchClass.name}")
                            return m
                        }
                    }
                }
                searchClass = searchClass.superclass
            }
        } catch (e: Exception) {
            Timber.tag("ReflectionUtils").w(e, "HiddenApiBypass findMethod failed for '$name'")
        }
    }
    return null
}

/**
 * Remove the final modifier from a field (needed for Build.* spoofing).
 */
fun removeFinalModifier(field: Field) {
    try {
        // On Android, the modifier field is called "accessFlags"
        val accessFlagsField = Field::class.java.getDeclaredField("accessFlags")
        accessFlagsField.isAccessible = true
        accessFlagsField.setInt(field, field.modifiers and Modifier.FINAL.inv())
    } catch (e: NoSuchFieldException) {
        // Fallback: try "modifiers" (standard Java)
        try {
            val modifiersField = Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        } catch (_: Exception) {
            Timber.tag("Reflect").w("Cannot remove final modifier from: ${field.name}")
        }
    }
}
