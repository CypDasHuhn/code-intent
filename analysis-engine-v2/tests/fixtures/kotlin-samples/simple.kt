package dev.cint.test

fun main() {
    println("Hello, World!")
}

class DataClass(val name: String, val value: Int)

interface Repository<T> {
    fun findById(id: String): T?
    fun save(entity: T): T
}

sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
}

fun <T> Repository<T>.findOrThrow(id: String): T {
    return findById(id) ?: throw IllegalArgumentException("Not found: $id")
}