package com.flux.payload.dumper.model

/** Local input is always a document picked with ACTION_OPEN_DOCUMENT, never editable text. */
enum class SourceMode { LOCAL, NETWORK }

data class LocalDocument(
    val uri: String,
    val displayName: String = "",
    val size: Long? = null,
)

data class SourceSelection(
    val mode: SourceMode = SourceMode.LOCAL,
    val local: LocalDocument? = null,
    val networkUrl: String = "",
) {
    val activeSource: String
        get() = when (mode) {
            SourceMode.LOCAL -> local?.uri.orEmpty()
            SourceMode.NETWORK -> networkUrl.trim()
        }

    val canParse: Boolean
        get() = when (mode) {
            SourceMode.LOCAL -> local?.uri?.startsWith("content://") == true
            SourceMode.NETWORK -> isHttpUrl(networkUrl.trim())
        }

    companion object {
        fun isHttpUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)

        /** Migrate only SAF URIs; an old typed file path must be picked again. */
        fun fromLegacy(value: String): SourceSelection = when {
            value.startsWith("content://") -> SourceSelection(local = LocalDocument(value))
            isHttpUrl(value) -> SourceSelection(mode = SourceMode.NETWORK, networkUrl = value)
            else -> SourceSelection()
        }
    }
}
