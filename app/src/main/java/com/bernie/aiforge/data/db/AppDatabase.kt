package com.bernie.aiforge.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// ─── Entities ─────────────────────────────────────────────────────────────────

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val skillId: String = "default",
    val providerId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity        = ChatEntity::class,
        parentColumns = ["id"],
        childColumns  = ["chatId"],
        onDelete      = ForeignKey.CASCADE,
    )],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val role: String,              // "user" | "assistant" | "tool"
    val content: String,
    val providerName: String? = null,
    val isToolCall: Boolean = false,
    val toolName: String? = null,
    val toolResultJson: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val content: String,
    val sourceChatId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
)

// ─── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChat(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title, updatedAt = :time WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(chatId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteAllMessages(chatId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE isActive = 1 ORDER BY createdAt DESC")
    suspend fun getActiveMemoriesOnce(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("UPDATE memories SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: String)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()
}

// ─── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities  = [ChatEntity::class, MessageEntity::class, MemoryEntity::class],
    version   = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
}
