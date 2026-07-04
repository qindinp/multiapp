package com.multiapp.core.instance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CancellationException
import javax.inject.Inject

class InstanceLaunchUseCase internal constructor(
    private val context: Context,
    private val intentFactory: (String, String) -> Intent
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        intentFactory = { packageName, instanceId ->
            Intent().apply {
                component = ComponentName(
                    packageName,
                    "com.multiapp.app.container.ContainerActivity"
                )
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    )

    fun launch(instanceId: String): Result<Unit> {
        return try {
            require(instanceId.isNotBlank()) { "instanceId must not be blank" }
            context.startActivity(createIntent(instanceId))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createIntent(instanceId: String): Intent {
        return intentFactory(context.packageName, instanceId)
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
    }
}
