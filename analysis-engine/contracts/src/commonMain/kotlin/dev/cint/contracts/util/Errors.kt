package dev.cint.contracts.util

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ContractError {
    @Serializable
    @SerialName("SerializationError")
    data class SerializationError(
        val message: String,
        val cause: String? = null,
    ) : ContractError

    @Serializable
    @SerialName("ValidationError")
    data class ValidationError(
        val message: String,
        val field: String? = null,
    ) : ContractError

    @Serializable
    @SerialName("NotFound")
    data class NotFound(
        val resource: String,
        val identifier: String,
    ) : ContractError

    @Serializable
    @SerialName("AlreadyExists")
    data class AlreadyExists(
        val resource: String,
        val identifier: String,
    ) : ContractError

    @Serializable
    @SerialName("InvalidInput")
    data class InvalidInput(
        val message: String,
        val input: String? = null,
    ) : ContractError

    @Serializable
    @SerialName("PreconditionFailed")
    data class PreconditionFailed(
        val message: String,
    ) : ContractError

    @Serializable
    @SerialName("InternalError")
    data class InternalError(
        val message: String,
        val cause: String? = null,
    ) : ContractError

    @Serializable
    @SerialName("DependencyUnavailable")
    data class DependencyUnavailable(
        val dependency: String,
        val reason: String,
    ) : ContractError

    @Serializable
    @SerialName("Timeout")
    data class Timeout(
        val operation: String,
        val timeoutMs: Long,
    ) : ContractError

    @Serializable
    @SerialName("Cancelled")
    data class Cancelled(
        val operation: String,
    ) : ContractError
}

@Serializable
sealed class Result<out T> {
    abstract val isSuccess: Boolean
    abstract val isFailure: Boolean

    abstract fun getValue(): Any?

    abstract val errorOrNull: ContractError?

    abstract fun getOrThrow(): T

    @Suppress("UNCHECKED_CAST")
    fun getOrElse(default: @UnsafeVariance T): T = if (isSuccess) getValue() as T else default

    abstract fun <R> map(transform: (T) -> R): Result<R>

    abstract fun <R> flatMap(transform: (T) -> Result<R>): Result<R>

    abstract fun onSuccess(action: (T) -> Unit): Result<T>

    abstract fun onFailure(action: (ContractError) -> Unit): Result<T>
}

@Serializable
@SerialName("Success")
data class Success<out T>(
    @SerialName("value") val contents: T,
) : Result<T>() {
    override val isSuccess: Boolean get() = true
    override val isFailure: Boolean get() = false

    override fun getValue(): Any? = contents

    override val errorOrNull: ContractError? get() = null

    override fun getOrThrow(): T = contents

    override fun <R> map(transform: (T) -> R): Result<R> = Success(transform(contents))

    override fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = transform(contents)

    override fun onSuccess(action: (T) -> Unit): Result<T> = also { action(contents) }

    override fun onFailure(action: (ContractError) -> Unit): Result<T> = this

    override fun toString(): String = "Success($contents)"

    companion object {
        fun <T> create(value: T): Success<T> = Success(value)
    }
}

@Serializable
@SerialName("Failure")
data class Failure(
    val error: ContractError,
) : Result<Nothing>() {
    override val isSuccess: Boolean get() = false
    override val isFailure: Boolean get() = true

    override fun getValue(): Any? = null

    override val errorOrNull: ContractError get() = error

    override fun getOrThrow(): Nothing = throw IllegalStateException("Result is failure: $error")

    override fun <R> map(transform: (Nothing) -> R): Result<R> = this

    override fun <R> flatMap(transform: (Nothing) -> Result<R>): Result<R> = this

    override fun onSuccess(action: (Nothing) -> Unit): Result<Nothing> = this

    override fun onFailure(action: (ContractError) -> Unit): Result<Nothing> = also { action(error) }

    override fun toString(): String = "Failure($error)"
}

object ResultFactory {
    fun <T> success(value: T): Result<T> = Success(value)

    fun <T> failure(error: ContractError): Result<T> = Failure(error)
}

typealias ContractResult<T> = Result<T>
