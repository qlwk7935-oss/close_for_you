package kr.closeforyou.data.rules

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * D: 기기 내부 Room DB. 버전 1.
 * TODO(D): 스키마를 바꿀 때는 Migration을 추가하고 exportSchema를 켜서 스키마 JSON을 커밋한다.
 */
@Database(entities = [PopupRuleEntity::class], version = 1, exportSchema = false)
abstract class CloseForYouDatabase : RoomDatabase() {
    abstract fun popupRuleDao(): PopupRuleDao

    companion object {
        const val NAME = "closeforyou_rules.db"

        @Volatile
        private var instance: CloseForYouDatabase? = null

        fun get(context: Context): CloseForYouDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CloseForYouDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
