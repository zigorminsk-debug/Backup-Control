package by.csl.backupcontrol.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import by.csl.backupcontrol.data.local.dao.BackupEmailDao
import by.csl.backupcontrol.data.local.dao.OrganizationDao
import by.csl.backupcontrol.data.local.dao.SyncLogDao
import by.csl.backupcontrol.data.local.entity.BackupEmailEntity
import by.csl.backupcontrol.data.local.entity.OrganizationEntity
import by.csl.backupcontrol.data.local.entity.SyncLogEntity

@Database(
    entities = [
        OrganizationEntity::class,
        BackupEmailEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BackupDatabase : RoomDatabase() {

    abstract fun organizationDao(): OrganizationDao
    abstract fun backupEmailDao(): BackupEmailDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile
        private var INSTANCE: BackupDatabase? = null

        fun getInstance(context: Context): BackupDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BackupDatabase::class.java,
                    "backup_control.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
