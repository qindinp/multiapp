package com.multiapp.core.hook

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookEngineTest {

    private lateinit var engine: HookEngine

    /** Skip tests that require LSPlant if it's not available */
    private fun assumeLsplantAvailable() {
        Assumptions.assumeTrue(engine.initLsplant(ClassLoader.getSystemClassLoader()), "LSPlant not available in JVM")
    }

    @BeforeEach
    fun setUp() {
        // Reset the static lsplantInitializedGlobal to false before each test
        // to ensure test isolation (the field is @Volatile and shared across instances)
        resetLsplantGlobalState()
        engine = HookEngine()
    }

    @AfterEach
    fun tearDown() {
        // Clean up all hooks to avoid state leaking between tests
        try {
            engine.unhookAll()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
        resetLsplantGlobalState()
    }

    // ===== initLsplant tests =====

    @Test
    fun `initLsplant returns false in JVM unit test environment`() {
        // LSPlant native library is not available in JVM unit tests
        // Class.forName("io.github.lsplant.LSPlant") will throw ClassNotFoundException
        val result = engine.initLsplant(this::class.java.classLoader!!)
        assertFalse(result)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `initLsplant returns true when called second time after successful init`() {
        // Simulate a prior successful initialization by setting the static field
        setLsplantGlobalState(true)

        val result = engine.initLsplant(this::class.java.classLoader!!)
        assertTrue(result)
    }

    @Test
    fun `initLsplant with system classloader does not crash`() {
        val result = engine.initLsplant(ClassLoader.getSystemClassLoader())
        assertFalse(result)
    }

    // ===== hookMethod tests =====

    @Test
    fun `hookMethod returns false when LSPlant not initialized`() {
        val method = String::class.java.getMethod("length")
        val result = engine.hookMethod(method)
        assertFalse(result)
    }

    @Test
    fun `hookMethod with callbacks returns false when LSPlant not initialized`() {
        val method = String::class.java.getMethod("length")
        val result = engine.hookMethod(
            method,
            beforeCallback = { _, args -> args },
            afterCallback = { _, _, result -> result }
        )
        assertFalse(result)
    }

    @Test
    fun `hookMethod with null callbacks returns false when LSPlant not initialized`() {
        val method = String::class.java.getMethod("length")
        val result = engine.hookMethod(method, null, null)
        assertFalse(result)
    }

    @Test
    fun `hookMethod with only beforeCallback returns false when not initialized`() {
        val method = String::class.java.getMethod("length")
        val result = engine.hookMethod(
            method,
            beforeCallback = { _, args -> args }
        )
        assertFalse(result)
    }

    @Test
    fun `hookMethod with only afterCallback returns false when not initialized`() {
        val method = String::class.java.getMethod("length")
        val result = engine.hookMethod(
            method,
            afterCallback = { _, _, result -> result }
        )
        assertFalse(result)
    }

    @Test
    fun `hookMethod does not increment hook count when LSPlant not initialized`() {
        val method = String::class.java.getMethod("length")
        engine.hookMethod(method)
        assertEquals(0, engine.getHookCount())
    }

    @Test
    fun `hookMethod with constructor executable returns false when not initialized`() {
        val constructor = String::class.java.getConstructor()
        val result = engine.hookMethod(constructor)
        assertFalse(result)
    }

    // ===== Duplicate hook / dedup tests =====

    @Test
    fun `hookMethod returns false for duplicate when LSPlant initialized but class missing`() {
        // Set LSPlant as "initialized" to bypass the init guard
        setLsplantGlobalState(true)

        val method = String::class.java.getMethod("length")
        // LSPlant class is not on classpath, so hookMethod will fail at Class.forName
        val result = engine.hookMethod(method)
        assertFalse(result)
    }

    @Test
    fun `hookMethod called twice with LSPlant initialized does not double-count`() {
        setLsplantGlobalState(true)

        val method = String::class.java.getMethod("length")
        engine.hookMethod(method) // fails (class missing)
        engine.hookMethod(method) // fails again
        assertEquals(0, engine.getHookCount())
    }

    // ===== unhookAll tests =====

    @Test
    fun `unhookAll succeeds when no hooks installed`() {
        engine.unhookAll()
        assertEquals(0, engine.getHookCount())
    }

    @Test
    fun `unhookAll clears hook count`() {
        val testObj = TestClassForHooking()
        engine.hookInstanceField(testObj, "value", "modified")
        assertTrue(engine.getHookCount() > 0)

        engine.unhookAll()
        assertEquals(0, engine.getHookCount())
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `unhookAll restores static field original value`() {
        val originalValue = StaticTestClass.STATIC_FIELD
        engine.hookStaticField(
            StaticTestClass::class.java.name,
            "STATIC_FIELD",
            "modified_value"
        )
        assertEquals("modified_value", StaticTestClass.STATIC_FIELD)

        engine.unhookAll()
        assertEquals(originalValue, StaticTestClass.STATIC_FIELD)
    }

    @Test
    fun `unhookAll restores instance field original value`() {
        val testObj = TestClassForHooking()
        testObj.value = "original"
        engine.hookInstanceField(testObj, "value", "modified")
        assertEquals("modified", testObj.value)

        engine.unhookAll()
        assertEquals("original", testObj.value)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `unhookAll cleans up both static and instance field hooks`() {
        val originalStatic = StaticTestClass.STATIC_FIELD
        val testObj = TestClassForHooking()
        testObj.value = "original"

        engine.hookStaticField(
            StaticTestClass::class.java.name,
            "STATIC_FIELD",
            "modified_static"
        )
        engine.hookInstanceField(testObj, "value", "modified_instance")

        assertEquals(2, engine.getHookCount())

        engine.unhookAll()

        assertEquals(0, engine.getHookCount())
        assertEquals(originalStatic, StaticTestClass.STATIC_FIELD)
        assertEquals("original", testObj.value)
    }

    @Test
    fun `unhookAll restores fields in reverse order`() {
        val testObj = TestClassForHooking()
        testObj.value = "v1"

        // Chain two hooks on the same field
        engine.hookInstanceField(testObj, "value", "v2")
        engine.hookInstanceField(testObj, "value", "v3")
        assertEquals("v3", testObj.value)

        engine.unhookAll()
        // Reversed: second hook restores "v2", then first hook restores "v1"
        assertEquals("v1", testObj.value)
    }

    @Test
    fun `unhookAll is safe to call twice`() {
        val testObj = TestClassForHooking()
        engine.hookInstanceField(testObj, "value", "modified")

        engine.unhookAll()
        engine.unhookAll() // Second call should not throw
        assertEquals(0, engine.getHookCount())
    }

    // ===== hookStaticField tests =====

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `hookStaticField modifies static field value`() {
        val originalValue = StaticTestClass.STATIC_FIELD
        val result = engine.hookStaticField(
            StaticTestClass::class.java.name,
            "STATIC_FIELD",
            "new_value"
        )
        assertTrue(result)
        assertEquals("new_value", StaticTestClass.STATIC_FIELD)

        setStaticFieldDirectly(StaticTestClass::class.java, "STATIC_FIELD", originalValue)
    }

    @Test
    fun `hookStaticField returns false for nonexistent class`() {
        val result = engine.hookStaticField(
            "com.nonexistent.FakeClass",
            "fakeField",
            "value"
        )
        assertFalse(result)
    }

    @Test
    fun `hookStaticField returns false for nonexistent field`() {
        val result = engine.hookStaticField(
            StaticTestClass::class.java.name,
            "nonexistentField",
            "value"
        )
        assertFalse(result)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `hookStaticField increments hook count`() {
        val originalValue = StaticTestClass.STATIC_FIELD
        assertEquals(0, engine.getHookCount())

        engine.hookStaticField(
            StaticTestClass::class.java.name,
            "STATIC_FIELD",
            "temp"
        )
        assertEquals(1, engine.getHookCount())

        setStaticFieldDirectly(StaticTestClass::class.java, "STATIC_FIELD", originalValue)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `hookStaticField with null value sets field to null`() {
        val originalValue = StaticTestClass.NULLABLE_FIELD
        val result = engine.hookStaticField(
            StaticTestClass::class.java.name,
            "NULLABLE_FIELD",
            null
        )
        assertTrue(result)
        assertEquals(null, StaticTestClass.NULLABLE_FIELD)

        setStaticFieldDirectly(StaticTestClass::class.java, "NULLABLE_FIELD", originalValue)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `hookStaticField with integer value`() {
        val originalValue = StaticTestClass.INT_FIELD
        val result = engine.hookStaticField(
            StaticTestClass::class.java.name,
            "INT_FIELD",
            999
        )
        assertTrue(result)
        assertEquals(999, StaticTestClass.INT_FIELD)

        setStaticFieldDirectly(StaticTestClass::class.java, "INT_FIELD", originalValue)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `hookStaticField with empty string value`() {
        val originalValue = StaticTestClass.NULLABLE_FIELD
        val result = engine.hookStaticField(
            StaticTestClass::class.java.name,
            "NULLABLE_FIELD",
            ""
        )
        assertTrue(result)
        assertEquals("", StaticTestClass.NULLABLE_FIELD)

        setStaticFieldDirectly(StaticTestClass::class.java, "NULLABLE_FIELD", originalValue)
    }

    @Test
    fun `hookStaticField for final field attempts to strip final modifier`() {
        // On standard JVM, modifying final fields may or may not work depending on
        // the JVM version. The hook should attempt and handle either outcome gracefully.
        val result = engine.hookStaticField(
            StaticTestClass::class.java.name,
            "FINAL_FIELD",
            "modified_final"
        )
        // The result depends on JVM capabilities - we just verify no crash
        if (result) {
            engine.unhookAll()
        }
    }

    // ===== hookInstanceField tests =====

    @Test
    fun `hookInstanceField modifies instance field value`() {
        val testObj = TestClassForHooking()
        testObj.value = "original"

        val result = engine.hookInstanceField(testObj, "value", "modified")
        assertTrue(result)
        assertEquals("modified", testObj.value)
    }

    @Test
    fun `hookInstanceField returns false for nonexistent field`() {
        val testObj = TestClassForHooking()
        val result = engine.hookInstanceField(testObj, "nonexistentField", "value")
        assertFalse(result)
    }

    @Test
    fun `hookInstanceField traverses class hierarchy`() {
        val childObj = ChildTestClass()
        childObj.childValue = "child_original"
        val result = engine.hookInstanceField(childObj, "childValue", "child_modified")
        assertTrue(result)
        assertEquals("child_modified", childObj.childValue)
    }

    @Test
    fun `hookInstanceField accesses parent field from child class`() {
        val childObj = ChildTestClass()
        childObj.value = "parent_original"
        val result = engine.hookInstanceField(childObj, "value", "parent_modified")
        assertTrue(result)
        assertEquals("parent_modified", childObj.value)
    }

    @Test
    fun `hookInstanceField increments hook count`() {
        val testObj = TestClassForHooking()
        assertEquals(0, engine.getHookCount())

        engine.hookInstanceField(testObj, "value", "modified")
        assertEquals(1, engine.getHookCount())
    }

    @Test
    fun `hookInstanceField with null value sets field to null`() {
        val testObj = TestClassForHooking()
        testObj.nullableValue = "exists"

        val result = engine.hookInstanceField(testObj, "nullableValue", null)
        assertTrue(result)
        assertEquals(null, testObj.nullableValue)
    }

    @Test
    fun `hookInstanceField on private field succeeds`() {
        val testObj = TestClassForHooking()
        val result = engine.hookInstanceField(testObj, "privateValue", "accessed")
        assertTrue(result)
    }

    @Test
    fun `hookInstanceField on superclass field through deep hierarchy`() {
        val grandChild = GrandChildTestClass()
        grandChild.value = "deep_original"

        val result = engine.hookInstanceField(grandChild, "value", "deep_modified")
        assertTrue(result)
        assertEquals("deep_modified", grandChild.value)
    }

    @Test
    fun `hookInstanceField with special characters in value`() {
        val testObj = TestClassForHooking()
        val specialValue = "unicode: éèê emoji: <3 special: <>&\"'"

        val result = engine.hookInstanceField(testObj, "value", specialValue)
        assertTrue(result)
        assertEquals(specialValue, testObj.value)
    }

    // ===== getHookCount tests =====

    @Test
    fun `getHookCount returns zero for fresh engine`() {
        assertEquals(0, engine.getHookCount())
    }

    @Test
    fun `getHookCount reflects multiple field hooks`() {
        val testObj1 = TestClassForHooking()
        val testObj2 = TestClassForHooking()

        engine.hookInstanceField(testObj1, "value", "a")
        engine.hookInstanceField(testObj2, "value", "b")
        assertEquals(2, engine.getHookCount())
    }

    @Test
    fun `getHookCount resets after unhookAll`() {
        val testObj = TestClassForHooking()
        engine.hookInstanceField(testObj, "value", "modified")
        assertTrue(engine.getHookCount() > 0)

        engine.unhookAll()
        assertEquals(0, engine.getHookCount())
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `getHookCount tracks mixed hook types`() {
        val originalStatic = StaticTestClass.STATIC_FIELD
        val testObj = TestClassForHooking()

        engine.hookStaticField(
            StaticTestClass::class.java.name,
            "STATIC_FIELD",
            "s"
        )
        engine.hookInstanceField(testObj, "value", "i")
        assertEquals(2, engine.getHookCount())

        setStaticFieldDirectly(StaticTestClass::class.java, "STATIC_FIELD", originalStatic)
    }

    // ===== Shared state / multiple instances tests =====

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `multiple HookEngine instances share lsplant initialization state`() {
        setLsplantGlobalState(true)

        val engine2 = HookEngine()
        assertTrue(engine.initLsplant(this::class.java.classLoader!!))
        assertTrue(engine2.initLsplant(this::class.java.classLoader!!))
    }

    @Test
    fun `lsplant state persists across HookEngine instances after reset`() {
        setLsplantGlobalState(false)

        val engine2 = HookEngine()
        assertFalse(engine.initLsplant(this::class.java.classLoader!!))
        assertFalse(engine2.initLsplant(this::class.java.classLoader!!))
    }

    // ===== Edge case tests =====

    @Test
    fun `unhookAll with no hooks and no LSPlant hooks does not throw`() {
        engine.unhookAll()
        engine.unhookAll()
        engine.unhookAll()
        assertEquals(0, engine.getHookCount())
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires LSPlant native library (ART runtime)")
    fun `hookStaticField and hookInstanceField coexist in hook count`() {
        val originalStatic = StaticTestClass.INT_FIELD
        val testObj = TestClassForHooking()

        engine.hookStaticField(
            StaticTestClass::class.java.name,
            "INT_FIELD",
            100
        )
        engine.hookInstanceField(testObj, "value", "coexist")
        engine.hookInstanceField(testObj, "nullableValue", null)

        assertEquals(3, engine.getHookCount())

        engine.unhookAll()
        assertEquals(0, engine.getHookCount())
        assertEquals(originalStatic, StaticTestClass.INT_FIELD)
    }

    // ===== Helper methods =====

    /**
     * Reset the static lsplantInitializedGlobal field to false via reflection.
     * This is necessary because HookEngine shares this state globally across instances.
     */
    private fun resetLsplantGlobalState() {
        try {
            val field = HookEngine::class.java.getDeclaredField("lsplantInitializedGlobal")
            field.isAccessible = true

            val modifiersField = try {
                // Android / Dalvik uses 'accessFlags'
                java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
            } catch (_: NoSuchFieldException) {
                // Standard JVM uses 'modifiers'
                java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            }
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())

            field.setBoolean(null, false)
        } catch (e: Exception) {
            System.err.println("Warning: Could not reset lsplantInitializedGlobal: ${e.message}")
        }
    }

    /**
     * Set the static lsplantInitializedGlobal field to a specific value via reflection.
     */
    private fun setLsplantGlobalState(value: Boolean) {
        try {
            val field = HookEngine::class.java.getDeclaredField("lsplantInitializedGlobal")
            field.isAccessible = true

            val modifiersField = try {
                java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
            } catch (_: NoSuchFieldException) {
                java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            }
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())

            field.setBoolean(null, value)
        } catch (e: Exception) {
            System.err.println("Warning: Could not set lsplantInitializedGlobal: ${e.message}")
        }
    }

    /**
     * Directly set a static field value for test restoration purposes.
     */
    private fun setStaticFieldDirectly(clazz: Class<*>, fieldName: String, value: Any?) {
        try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, value)
        } catch (_: Exception) {
            // Restoration is best-effort
        }
    }

    // ===== Test fixture classes =====

    open class TestClassForHooking {
        var value: String = "default"
        var nullableValue: String? = "exists"
        private var privateValue: String = "private_default"
    }

    open class ChildTestClass : TestClassForHooking() {
        var childValue: String = "child_default"
    }

    class GrandChildTestClass : ChildTestClass() {
        var grandChildValue: String = "grandchild_default"
    }

    object StaticTestClass {
        @JvmStatic
        var STATIC_FIELD: String = "static_original"

        @JvmStatic
        var NULLABLE_FIELD: String? = "nullable_original"

        @JvmStatic
        var INT_FIELD: Int = 42

        @JvmStatic
        val FINAL_FIELD: String = "final_original"
    }
}
