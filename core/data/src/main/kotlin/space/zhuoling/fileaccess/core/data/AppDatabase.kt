package space.zhuoling.fileaccess.core.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import space.zhuoling.fileaccess.core.data.db.BackupBaselineEntity
import space.zhuoling.fileaccess.core.data.db.BackupDao
import space.zhuoling.fileaccess.core.data.db.BackupRuleEntity
import space.zhuoling.fileaccess.core.data.db.BackupScanCursorEntity
import space.zhuoling.fileaccess.core.data.db.ConnectionDao
import space.zhuoling.fileaccess.core.data.db.ConnectionEntity
import space.zhuoling.fileaccess.core.data.db.TransferDao
import space.zhuoling.fileaccess.core.data.db.TransferTaskEntity

@Database(
    entities = [ConnectionEntity::class, TransferTaskEntity::class, BackupRuleEntity::class,
        BackupBaselineEntity::class, BackupScanCursorEntity::class], version = 1, exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connections(): ConnectionDao
    abstract fun transfers(): TransferDao
    abstract fun backups(): BackupDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder<AppDatabase>(
            context.applicationContext, "fileaccess.db",
        ).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}
