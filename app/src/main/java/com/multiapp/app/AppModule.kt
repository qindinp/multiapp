package com.multiapp.app

import android.content.Context
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceRecordStore
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.installer.ProductionVirtualInstallService
import com.multiapp.core.model.installer.VirtualInstallService
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
    fun provideHookEngine(): HookEngine = HookEngine.getInstance()

    @Provides
    @Singleton
    fun provideInstanceRecordStore(@ApplicationContext context: Context): InstanceRecordStore {
        return JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(context))
    }

    @Provides
    @Singleton
    fun provideInstallRecordStore(@ApplicationContext context: Context): InstallRecordStore {
        return JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(context))
    }

    @Provides
    @Singleton
    fun provideVirtualInstallService(
        installRecordStore: InstallRecordStore,
        @ApplicationContext context: Context
    ): VirtualInstallService {
        return ProductionVirtualInstallService(
            installRecordStore,
            ContainerRuntimePaths.artifactDir(context)
        )
    }

    @Provides
    @Singleton
    fun provideInstanceManager(
        instanceRecordStore: InstanceRecordStore,
        installRecordStore: InstallRecordStore,
        @ApplicationContext context: Context
    ): InstanceManager {
        return DefaultInstanceManager(
            store = instanceRecordStore,
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(context),
            installRecordStore = installRecordStore
        )
    }
}
