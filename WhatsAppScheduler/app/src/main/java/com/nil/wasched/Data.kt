package com.nil.wasched

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "wa_groups", indices = [Index("name", unique = true)])
data class WaGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val business: Boolean = false,
    val prefix: String = "",
    val suffix: String = ""
)

/** type = TEXT | POLL. status = PENDING | SENT | FAILED | MISSED | SKIPPED. pollOptions is newline-separated. */
@Entity(tableName = "messages")
data class Msg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupName: String,
    val type: String = "TEXT",
    val message: String = "",
    val url: String = "",
    val pollQuestion: String = "",
    val pollOptions: String = "",
    val allowMultiple: Boolean = false,
    val dateTime: Long,
    val status: String = "PENDING"
)

@Dao
interface GroupDao {
    @Query("SELECT * FROM wa_groups ORDER BY name") fun all(): Flow<List<WaGroup>>
    @Query("SELECT * FROM wa_groups WHERE name = :n LIMIT 1") suspend fun byName(n: String): WaGroup?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(g: WaGroup): Long
    @Delete suspend fun delete(g: WaGroup)
}

@Dao
interface MsgDao {
    @Query("SELECT * FROM messages ORDER BY dateTime") fun all(): Flow<List<Msg>>
    @Query("SELECT * FROM messages ORDER BY dateTime") suspend fun list(): List<Msg>
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun get(id: Long): Msg?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: Msg): Long
    @Delete suspend fun delete(m: Msg)
    @Query("UPDATE messages SET status = :s WHERE id = :id") suspend fun setStatus(id: Long, s: String)
}

@Database(entities = [WaGroup::class, Msg::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun groups(): GroupDao
    abstract fun msgs(): MsgDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "wa.db").build().also { inst = it }
        }
    }
}
