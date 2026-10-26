package cn.xingban.app.data

import android.content.Context
import androidx.room.*
import cn.xingban.core.*

data class SavedMessage(val id: String, val role: String, val text: String, val time: Long, val status: String = "completed", val turnId: String? = null)
enum class BackgroundMusicTrack(val title: String, val sourceUrl: String) {
    MEDITATION_IMPROMPTU_01("Meditation Impromptu 01", "https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100163"),
    AVEC_SOIN("Avec Soin", "https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100860"),
    MOONSTONE("Moonstone", "https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100145");

    fun next(): BackgroundMusicTrack = entries[(ordinal + 1) % entries.size]
}
data class AppSettings(
    val provider: String = "DeepSeek", val model: String = "deepseek-flash",
    val speechMode: String = "off", val voiceId: String = "", val systemVoice: String = "",
    val userAddress: String = "", val warmth: Double = 0.8, val playfulness: Double = 0.7, val tsundere: Double = 0.55,
    val musicEnabled: Boolean = true, val musicVolume: Double = 0.15, val musicTrack: BackgroundMusicTrack = BackgroundMusicTrack.MEDITATION_IMPROMPTU_01,
    val musicSource: String = "local",
)
data class AppData(
    val messages: List<SavedMessage> = emptyList(), val character: CharacterState = CharacterState(),
    val memory: MemoryState = MemoryState(), val settings: AppSettings = AppSettings(),
    val reunionDay: String = "", val reunionShownAt: Long = 0,
    val activity: CompanionActivity = CompanionActivity(), val departedAtMillis: Long? = null,
    val summary: ConversationSummary? = null, val summaryAttemptTurns: Int = 0, val usage: UsageLedger = UsageLedger()
)

@Entity(tableName = "companion_snapshot")
data class SnapshotRow(@PrimaryKey val id: Int = 1, val payload: String)
@Dao
interface SnapshotDao {
    @Query("SELECT * FROM companion_snapshot WHERE id = 1") suspend fun read(): SnapshotRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(row: SnapshotRow)
}
@Database(entities = [SnapshotRow::class], version = 1, exportSchema = false)
abstract class CompanionDatabase : RoomDatabase() { abstract fun snapshots(): SnapshotDao }

/** One versioned snapshot is the transaction boundary for messages, mood and memory revision. */
open class AppStore(context: Context) {
    private val database = Room.databaseBuilder(context, CompanionDatabase::class.java, "xingban.db").build()
    open suspend fun load(): AppData = database.snapshots().read()?.let { StateCodec.decode(it.payload) } ?: AppData()
    open suspend fun save(data: AppData) { database.withTransaction { database.snapshots().write(SnapshotRow(payload = StateCodec.encode(data))) } }
}
