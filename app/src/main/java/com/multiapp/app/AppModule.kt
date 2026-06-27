package com.multiapp.app

import android.content.Context
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceRecordStore
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHookEngine(): HookEngine = HookEngine.getInstance()

    @Provides
    @Singleton
    fun provideInstanceRecordStore(@ApplicationContext context: Context): InstanceRecordStore {
        val baseDir = File(context.filesDir, "instances")
        baseDir.mkdirs()
        return JsonInstanceRecordStore(baseDir)
    }

    @Provides
    @Singleton
    fun provideInstallRecordStore(@ApplicationContext context: Context): InstallRecordStore {
        val baseDir = File(context.filesDir, "installs")
        baseDir.mkdirs()
        return JsonInstallRecordStore(baseDir)
    }

    @Provides
    @Singleton
    fun provideInstanceManager(
        instanceRecordStore: InstanceRecordStore,
        @ApplicationContext context: Context
    ): InstanceManager {
        val dataRootBase = File(context.filesDir, "instance_data")
        dataRootBase.mkdirs()
        return DefaultInstanceManager(
            store = instanceRecordStore,
            dataRootBase = dataRootBase
        )
    }
}
