package com.f0x1d.logfox.core.io

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun ZipOutputStream.putZipEntry(name: String, content: ByteArray) {
    val entry = ZipEntry(name)
    putNextEntry(entry)

    write(content, 0, content.size)
    closeEntry()
}

fun ZipOutputStream.putZipEntry(name: String, file: File) {
    // Fail before creating the entry: otherwise a deleted source left a half-written zip
    // at the user's chosen destination
    require(file.exists()) { "Missing file for zip entry $name" }

    val entry = ZipEntry(name)
    putNextEntry(entry)

    file.inputStream().use {
        it.copyTo(this)
    }

    closeEntry()
}
