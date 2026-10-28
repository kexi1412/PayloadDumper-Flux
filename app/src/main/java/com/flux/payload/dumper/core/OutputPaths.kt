package com.flux.payload.dumper.core

import java.io.File
import java.io.IOException
import java.util.Locale

/** Only leaf names from an archive/provider enter the selected output directory. */
internal object OutputPaths {
    fun leaf(value: String, default: String = "firmware"): String {
        val name = HttpArchiveHeaders.safeName(value)
        return name.takeUnless { it == "payload.bin" && value.replace('\\', '/').substringAfterLast('/').trim('.').isBlank() }
            ?: default
    }

    fun archiveFolder(value: String): String {
        val name = leaf(value)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = if (extension in setOf("zip", "bin", "app")) name.substringBeforeLast('.') else name
        return stem.trim().trim('.').ifBlank { "firmware" }
    }

    /** A canonical parent check also protects against an existing escaping symlink. */
    fun child(parent: File, name: String): File {
        val target = File(parent, leaf(name)).canonicalFile
        if (target.parentFile != parent.canonicalFile) throw IOException("Output path escapes the selected directory")
        return target
    }
}
