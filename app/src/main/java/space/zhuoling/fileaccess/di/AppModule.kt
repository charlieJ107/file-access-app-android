package space.zhuoling.fileaccess.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import space.zhuoling.fileaccess.core.data.*
import space.zhuoling.fileaccess.core.security.CredentialStore
import space.zhuoling.fileaccess.core.security.KeystoreCredentialStore
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.transfer.NetworkPolicy
import space.zhuoling.fileaccess.protocol.smb.SmbStorageProvider

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context) = AppDatabase.create(context)
    @Provides @Singleton fun credentials(@ApplicationContext context: Context): CredentialStore = KeystoreCredentialStore(context)
    @Provides @Singleton fun connections(db: AppDatabase, credentials: CredentialStore) = ConnectionRepository(db, credentials)
    @Provides @Singleton fun transfers(db: AppDatabase) = TransferRepository(db)
    @Provides @Singleton fun backups(db: AppDatabase) = BackupRepository(db)
    @Provides @Singleton fun settings(@ApplicationContext context: Context) = SettingsRepository(context)
    @Provides @Singleton fun providers(network: NetworkPolicy) = ProviderRegistry(setOf(
        SmbStorageProvider(socketFactoryProvider = { network.socketFactory() }),
    ))
}
