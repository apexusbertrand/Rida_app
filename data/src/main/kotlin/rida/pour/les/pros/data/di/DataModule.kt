package rida.pour.les.pros.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import rida.pour.les.pros.data.db.MIGRATION_1_2
import rida.pour.les.pros.data.db.RidaDatabase
import rida.pour.les.pros.data.repo.RidaClock
import rida.pour.les.pros.data.repo.SecretProvider
import rida.pour.les.pros.data.repo.SecretStore
import rida.pour.les.pros.data.repo.SettingsProvider
import rida.pour.les.pros.data.repo.SettingsRepository
import rida.pour.les.pros.data.repo.SystemRidaClock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): RidaDatabase =
        Room.databaseBuilder(context, RidaDatabase::class.java, RidaDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    abstract fun clock(impl: SystemRidaClock): RidaClock

    @Binds
    abstract fun settings(impl: SettingsRepository): SettingsProvider

    @Binds
    abstract fun secrets(impl: SecretStore): SecretProvider
}
