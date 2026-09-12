package space.zhuoling.fileaccess.core.transfer

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupClock

@Module
@InstallIn(SingletonComponent::class)
object BackupClockModule {
    @Provides @BackupClock fun clock(): Clock = Clock.systemUTC()
}
