package com.semanticpainter.core.hashing

import java.security.MessageDigest

fun contentHash(text: String): String {
    val canonical = text.replace("\r\n", "\n").removeSuffix("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
