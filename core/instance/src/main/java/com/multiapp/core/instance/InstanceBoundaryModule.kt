package com.multiapp.core.instance

import com.multiapp.core.model.CloneCreationCoordinator
import com.multiapp.core.model.InstalledAppCatalog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InstanceBoundaryModule {
    @Binds
    abstract fun bindInstalledAppCatalog(
        repository: InstalledAppRepository
    ): InstalledAppCatalog

    @Binds
    abstract fun bindCloneCreationCoordinator(
        useCase: CloneCreateUseCase
    ): CloneCreationCoordinator
}
