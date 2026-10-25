package dev.cint.contracts.util

import java.security.MessageDigest

actual fun sha256(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(data)
}