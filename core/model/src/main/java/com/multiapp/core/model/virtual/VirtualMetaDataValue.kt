package com.multiapp.core.model.virtual

enum class VirtualMetaDataValueType {
    STRING,
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    RESOURCE
}

/** Pure-model representation of one Android manifest meta-data value. */
data class VirtualMetaDataValue(
    val type: VirtualMetaDataValueType,
    val encodedValue: String
) {
    init {
        require(encodedValue.isNotBlank() || type == VirtualMetaDataValueType.STRING) {
            "encodedValue must not be blank for $type"
        }
    }

    companion object {
        fun string(value: String) = VirtualMetaDataValue(VirtualMetaDataValueType.STRING, value)
        fun boolean(value: Boolean) = VirtualMetaDataValue(VirtualMetaDataValueType.BOOLEAN, value.toString())
        fun int(value: Int) = VirtualMetaDataValue(VirtualMetaDataValueType.INT, value.toString())
        fun long(value: Long) = VirtualMetaDataValue(VirtualMetaDataValueType.LONG, value.toString())
        fun float(value: Float) = VirtualMetaDataValue(VirtualMetaDataValueType.FLOAT, value.toString())
        fun double(value: Double) = VirtualMetaDataValue(VirtualMetaDataValueType.DOUBLE, value.toString())
        fun resource(value: Int) = VirtualMetaDataValue(VirtualMetaDataValueType.RESOURCE, value.toString())
        fun resource(value: String) = VirtualMetaDataValue(VirtualMetaDataValueType.RESOURCE, value)

        fun fromAny(value: Any?): VirtualMetaDataValue? = when (value) {
            null -> null
            is Boolean -> boolean(value)
            is Int -> int(value)
            is Long -> long(value)
            is Float -> float(value)
            is Double -> double(value)
            is String -> string(value)
            is CharSequence -> string(value.toString())
            else -> string(value.toString())
        }

        fun infer(value: String): VirtualMetaDataValue {
            val normalized = value.trim()
            if (normalized.equals("true", ignoreCase = true) || normalized.equals("false", ignoreCase = true)) {
                return boolean(normalized.toBoolean())
            }
            parseInt(normalized)?.let { return int(it) }
            normalized.toLongOrNull()?.let { return long(it) }
            normalized.removeSuffix("f").removeSuffix("F").toFloatOrNull()?.let { parsed ->
                if (normalized.contains('.') || normalized.contains('e', true) || normalized.endsWith("f", true)) {
                    return float(parsed)
                }
            }
            return string(value)
        }

        private fun parseInt(value: String): Int? = when {
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLongOrNull(16)?.toInt()
            else -> value.toIntOrNull()
        }
    }
}

fun Map<String, VirtualMetaDataValue>.toLegacyMetaDataMap(): Map<String, String> =
    mapValues { (_, value) -> value.encodedValue }
