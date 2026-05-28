package com.multiapp.app

import android.content.Context
import androidx.room.Room
import com.multiapp.core.instance.InstanceDatabase
import com.multiapp.core.installer.StubInstaller
import com.multiapp.core.stub.StubBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideStubBuilder(): StubBuilder {
        return StubBuilder()
    }

    @Provides
    @Singleton
    fun provideStubInstaller(@ApplicationContext context: Context): StubInstaller {
        return StubInstaller(context)
    }

    @Provides
    @Singleton
    fun provideInstanceDatabase(@ApplicationContext context: Context): InstanceDatabase {
        return Room.databaseBuilder(
            context,
            InstanceDatabase::class.java,
            "multiapp_instances.db"
        ).build()
    }
}
