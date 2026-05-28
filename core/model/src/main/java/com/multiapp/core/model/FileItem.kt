package com.multiapp.core.model

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String,
    val isHidden: Boolean,
    val childCount: Int = 0,
    val mimeType: String = ""
)

enum class FileSortMode {
    NAME_ASC, NAME_DESC,
    SIZE_ASC, SIZE_DESC,
    DATE_ASC, DATE_DESC,
    TYPE_ASC, TYPE_DESC
}

enum class FileViewMode {
    LIST, GRID
}

data class FileClipboard(
    val items: List<FileItem>,
    val operation: ClipboardOperation
)

enum class ClipboardOperation {
    COPY, CUT
}

data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
)
