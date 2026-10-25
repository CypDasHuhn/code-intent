package dev.cint.contracts.util

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor

@Serializable
sealed interface ContractError {
    @Serializable
    @SerialName("SerializationError")
    data class SerializationError(val message: String, val cause: String? = null) : ContractError
    @Serializable
    @SerialName("ValidationError")
    data class ValidationError(val message: String, val field: String? = null) : ContractError
    @Serializable
    @SerialName("NotFound")
    data class NotFound(val resource: String, val identifier: String) : ContractError
    @Serializable
    @SerialName("AlreadyExists")
    data class AlreadyExists(val resource: String, val identifier: String) : ContractError
    @Serializable
    @SerialName("InvalidInput")
    data class InvalidInput(val message: String, val input: String? = null) : ContractError
    @Serializable
    @SerialName("PreconditionFailed")
    data class PreconditionFailed(val message: String) : ContractError
    @Serializable
    @SerialName("InternalError")
    data class InternalError(val message: String, val cause: String? = null) : ContractError
    @Serializable
    @SerialName("DependencyUnavailable")
    data class DependencyUnavailable(val dependency: String, val reason: String) : ContractError
    @Serializable
    @SerialName("Timeout")
    data class Timeout(val operation: String, val timeoutMs: Long) : ContractError
    @Serializable
    @SerialName("Cancelled")
    data class Cancelled(val operation: String) : ContractError
}

interface Result<T> {
    val isSuccess: Boolean
    val isFailure: Boolean
    fun getValue(): Any?
    val errorOrNull: ContractError?
    fun getOrThrow(): T
    fun getOrElse(default: T): T
    fun <R> map(transform: (T) -> R): Result<R>
    fun <R> flatMap(transform: (T) -> Result<R>): Result<R>
    fun onSuccess(action: (T) -> Unit): Result<T>
    fun onFailure(action: (ContractError) -> Unit): Result<T>
}

@Serializable
class Success<T> private constructor(
    private val _value: T,
) : Result<T> {
    override val isSuccess: Boolean = true
    override val isFailure: Boolean = false
    override fun getValue(): Any? = _value
    override val errorOrNull: ContractError? = null
    override fun getOrThrow(): T = _value
    override fun getOrElse(default: T): T = _value
    override fun <R> map(transform: (T) -> R): Result<R> = Success(transform(_value))
    override fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = transform(_value)
    override fun onSuccess(action: (T) -> Unit): Result<T> = also { action(_value) }
    override fun onFailure(action: (ContractError) -> Unit): Result<T> = this
    override fun toString(): String = "Success($_value)"

    companion object {
        fun <T> create(value: T): Success<T> = Success(value)
    }
}

@Serializable
class Failure(val error: ContractError) : Result<Nothing> {
    override val isSuccess: Boolean = false
    override val isFailure: Boolean = true
    override fun getValue(): Any? = null
    override val errorOrNull: ContractError? = error
    override fun getOrThrow(): Nothing = throw IllegalStateException("Result is failure: $error")
    override fun getOrElse(default: Nothing): Nothing = throw IllegalStateException("Result is failure: $error")
    override fun <R> map(transform: (Nothing) -> R): Result<R> = Failure(error) as Result<R>
    override fun <R> flatMap(transform: (Nothing) -> Result<R>): Result<R> = Failure(error) as Result<R>
    override fun onSuccess(action: (Nothing) -> Unit): Result<Nothing> = this
    override fun onFailure(action: (ContractError) -> Unit): Result<Nothing> = also { action(error) }
    override fun toString(): String = "Failure($error)"
}

object ResultFactory {
    fun <T> success(value: T): Result<T> = Success.create(value)
    fun failure(error: ContractError): Result<Nothing> = Failure(error)
}

typealias ContractResult<T> = Result<T>