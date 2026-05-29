# ============================================================================
# core:instance — Room database, Gson-serialized entities
# ============================================================================

# Room database — entities, DAOs, and database class use reflection
-keep class com.multiapp.core.instance.InstanceDatabase { *; }
-keep class com.multiapp.core.instance.InstanceDatabase$* { *; }
-keep class com.multiapp.core.instance.InstanceEntity { *; }
-keep class com.multiapp.core.instance.InstanceDao { *; }
-keep class com.multiapp.core.instance.InstanceDao$* { *; }

# InstanceManager — provided via Hilt @Inject
-keep class com.multiapp.core.instance.InstanceManager { *; }

# Data classes used in StateFlow / Gson
-keep class com.multiapp.core.instance.InstanceInfo { *; }
-keep class com.multiapp.core.instance.InstanceStatus { *; }
