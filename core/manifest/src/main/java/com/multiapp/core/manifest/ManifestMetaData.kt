package com.multiapp.core.manifest

import com.multiapp.core.model.virtual.VirtualMetaDataValue

fun List<ManifestParser.MetaDataInfo>?.toVirtualMetaDataMap(): Map<String, VirtualMetaDataValue> =
    this?.mapNotNull { item ->
        val typedValue = item.typedValue
            ?: item.resourceId.takeIf { it != 0 }?.let(VirtualMetaDataValue::resource)
            ?: item.resource?.let(VirtualMetaDataValue::resource)
            ?: item.value?.let(VirtualMetaDataValue::infer)
        typedValue?.let { item.name to it }
    }?.toMap().orEmpty()
