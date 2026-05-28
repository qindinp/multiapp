package com.multiapp.core.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Simple test class with known fields for reflection tests. */
open class TestSubject {
    @JvmField
    var name: String = "default"
    @JvmField
    var count: Int = 0
    private var secret: String = "hidden"

    fun greet(): String = "hello"
    fun add(a: Int, b: Int): Int = a + b
    private fun secretMethod(): String = "secret"
}

/** Subclass to test superclass field resolution. */
class TestChild : TestSubject() {
    @JvmField
    var childField: Boolean = true
}

class ReflectionUtilsTest {

    // --- findField ---

    @Test
    fun `findField finds public field on class`() {
        val field = findField(TestSubject::class.java, "name")
        assertNotNull(field)
        assertEquals("name", field.name)
    }

    @Test
    fun `findField finds int field`() {
        val field = findField(TestSubject::class.java, "count")
        assertNotNull(field)
        assertEquals("count", field.name)
    }

    @Test
    fun `findField finds private field`() {
        val field = findField(TestSubject::class.java, "secret")
        assertNotNull(field)
        assertEquals("secret", field.name)
    }

    @Test
    fun `findField returns null for nonexistent field`() {
        val field = findField(TestSubject::class.java, "nonExistent")
        assertNull(field)
    }

    @Test
    fun `findField resolves field from superclass`() {
        val field = findField(TestChild::class.java, "name")
        assertNotNull(field)
        assertEquals("name", field.name)
    }

    @Test
    fun `findField finds child-specific field`() {
        val field = findField(TestChild::class.java, "childField")
        assertNotNull(field)
        assertEquals("childField", field.name)
    }

    // --- findMethod ---

    @Test
    fun `findMethod finds no-arg method`() {
        val method = findMethod(TestSubject::class.java, "greet", emptyArray())
        assertNotNull(method)
        assertEquals("greet", method.name)
    }

    @Test
    fun `findMethod finds method with parameters`() {
        val method = findMethod(
            TestSubject::class.java,
            "add",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        )
        assertNotNull(method)
        assertEquals("add", method.name)
    }

    @Test
    fun `findMethod finds private method`() {
        val method = findMethod(TestSubject::class.java, "secretMethod", emptyArray())
        assertNotNull(method)
        assertEquals("secretMethod", method.name)
    }

    @Test
    fun `findMethod returns null for nonexistent method`() {
        val method = findMethod(TestSubject::class.java, "noSuchMethod", emptyArray())
        assertNull(method)
    }

    @Test
    fun `findMethod resolves method from superclass`() {
        val method = findMethod(TestChild::class.java, "greet", emptyArray())
        assertNotNull(method)
        assertEquals("greet", method.name)
    }

    // --- Any.getField ---

    @Test
    fun `getField retrieves public field value`() {
        val subject = TestSubject()
        subject.name = "test-value"
        val value = subject.getField("name")
        assertEquals("test-value", value)
    }

    @Test
    fun `getField retrieves int field value`() {
        val subject = TestSubject()
        subject.count = 42
        val value = subject.getField("count")
        assertEquals(42, value)
    }

    @Test
    fun `getField retrieves private field value`() {
        val subject = TestSubject()
        val value = subject.getField("secret")
        assertEquals("hidden", value)
    }

    @Test
    fun `getField returns null for nonexistent field`() {
        val subject = TestSubject()
        val value = subject.getField("doesNotExist")
        assertNull(value)
    }

    // --- Any.setField ---

    @Test
    fun `setField modifies public field`() {
        val subject = TestSubject()
        val success = subject.setField("name", "new-value")
        assertTrue(success)
        assertEquals("new-value", subject.name)
    }

    @Test
    fun `setField modifies int field`() {
        val subject = TestSubject()
        val success = subject.setField("count", 99)
        assertTrue(success)
        assertEquals(99, subject.count)
    }

    @Test
    fun `setField modifies private field`() {
        val subject = TestSubject()
        val success = subject.setField("secret", "revealed")
        assertTrue(success)
        assertEquals("revealed", subject.getField("secret"))
    }

    @Test
    fun `setField returns true even for nonexistent field`() {
        val subject = TestSubject()
        // setField uses safe calls: field?.set(...) -- if field is null, it returns true
        val success = subject.setField("nonexistent", "value")
        assertTrue(success)
    }

    // --- invokeMethod ---

    @Test
    fun `invokeMethod calls no-arg method`() {
        val subject = TestSubject()
        val result = subject.invokeMethod("greet")
        assertEquals("hello", result)
    }

    @Test
    fun `invokeMethod calls method with args`() {
        val subject = TestSubject()
        val result = subject.invokeMethod(
            "add",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            3, 4
        )
        assertEquals(7, result)
    }

    @Test
    fun `invokeMethod returns null for nonexistent method`() {
        val subject = TestSubject()
        val result = subject.invokeMethod("noSuchMethod")
        assertNull(result)
    }
}
