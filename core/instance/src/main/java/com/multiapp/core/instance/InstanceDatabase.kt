package com.multiapp.core.instance

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room 数据库 — 持久化实例信息
 */
@Database(entities = [InstanceEntity::class], version = 2, exportSchema = true)
abstract class InstanceDatabase : RoomDatabase() {
    abstract fun instanceDao(): InstanceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 预留: 下次 schema 变更时在此添加 ALTER TABLE 语句
                // db.execSQL("ALTER TABLE instances ADD COLUMN new_column TEXT")
            }
        }
    }
}

@Entity(tableName = "instances")
data class InstanceEntity(
    @PrimaryKey val instanceId: String,
    val originalPackageName: String,
    val stubPackageName: String,
    val identityJson: String,
    val createdAt: Long,
    val status: String
)

@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InstanceEntity>>

    @Query("SELECT * FROM instances WHERE instanceId = :id")
    suspend fun getById(id: String): InstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: InstanceEntity)

    @Query("DELETE FROM instances WHERE instanceId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM instances WHERE originalPackageName = :packageName")
    suspend fun getByPackageName(packageName: String): List<InstanceEntity>
}
