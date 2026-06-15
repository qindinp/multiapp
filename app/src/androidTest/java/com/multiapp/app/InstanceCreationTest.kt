package com.multiapp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.core.instance.InstanceDatabase
import com.multiapp.core.instance.InstanceEntity
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstanceCreationTest {

    private lateinit var database: InstanceDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, InstanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertInstance_persistsCorrectly() = runTest {
        val entity = InstanceEntity(
            instanceId = "test_instance_001",
            originalPackageName = "com.example.app",
            stubPackageName = "com.multiapp.stub.test001",
            identityJson = """{"instanceId":"test_instance_001"}""",
            createdAt = System.currentTimeMillis(),
            status = "READY"
        )

        database.instanceDao().insert(entity)

        val retrieved = database.instanceDao().getById("test_instance_001")
        assertNotNull(retrieved)
        assertEquals("com.example.app", retrieved!!.originalPackageName)
        assertEquals("com.multiapp.stub.test001", retrieved.stubPackageName)
        assertEquals("READY", retrieved.status)
    }

    @Test
    fun insertDuplicate_replacesExisting() = runTest {
        val entity1 = InstanceEntity(
            instanceId = "test_instance_002",
            originalPackageName = "com.example.app",
            stubPackageName = "com.multiapp.stub.v1",
            identityJson = "{}",
            createdAt = 1000L,
            status = "CREATING"
        )
        val entity2 = InstanceEntity(
            instanceId = "test_instance_002",
            originalPackageName = "com.example.app",
            stubPackageName = "com.multiapp.stub.v2",
            identityJson = "{}",
            createdAt = 2000L,
            status = "READY"
        )

        database.instanceDao().insert(entity1)
        database.instanceDao().insert(entity2)

        val retrieved = database.instanceDao().getById("test_instance_002")
        assertNotNull(retrieved)
        assertEquals("com.multiapp.stub.v2", retrieved!!.stubPackageName)
        assertEquals("READY", retrieved.status)
    }

    @Test
    fun deleteInstance_removesFromDatabase() = runTest {
        val entity = InstanceEntity(
            instanceId = "test_instance_003",
            originalPackageName = "com.example.app",
            stubPackageName = "com.multiapp.stub.test003",
            identityJson = "{}",
            createdAt = System.currentTimeMillis(),
            status = "READY"
        )

        database.instanceDao().insert(entity)
        database.instanceDao().deleteById("test_instance_003")

        val retrieved = database.instanceDao().getById("test_instance_003")
        assertEquals(null, retrieved)
    }

    @Test
    fun observeAll_returnsAllInstances() = runTest {
        val entities = (1..3).map { i ->
            InstanceEntity(
                instanceId = "test_instance_00$i",
                originalPackageName = "com.example.app$i",
                stubPackageName = "com.multiapp.stub.test00$i",
                identityJson = "{}",
                createdAt = System.currentTimeMillis() + i,
                status = "READY"
            )
        }

        entities.forEach { database.instanceDao().insert(it) }

        val all = database.instanceDao().observeAll().first()
        assertEquals(3, all.size)
    }

    @Test
    fun getByPackageName_returnsMatchingInstances() = runTest {
        val entity1 = InstanceEntity(
            instanceId = "test_instance_004",
            originalPackageName = "com.target.app",
            stubPackageName = "com.multiapp.stub.a",
            identityJson = "{}",
            createdAt = 1000L,
            status = "READY"
        )
        val entity2 = InstanceEntity(
            instanceId = "test_instance_005",
            originalPackageName = "com.target.app",
            stubPackageName = "com.multiapp.stub.b",
            identityJson = "{}",
            createdAt = 2000L,
            status = "READY"
        )
        val entity3 = InstanceEntity(
            instanceId = "test_instance_006",
            originalPackageName = "com.other.app",
            stubPackageName = "com.multiapp.stub.c",
            identityJson = "{}",
            createdAt = 3000L,
            status = "READY"
        )

        database.instanceDao().insert(entity1)
        database.instanceDao().insert(entity2)
        database.instanceDao().insert(entity3)

        val results = database.instanceDao().getByPackageName("com.target.app")
        assertEquals(2, results.size)
        assertTrue(results.all { it.originalPackageName == "com.target.app" })
    }
}
