package dev.cint.contracts.data

typealias Intent = String

object Intents {
    const val UNTAGGED = "UNTAGGED"
    const val LOGGING = "LOGGING"
    const val ERROR_HANDLING = "ERROR_HANDLING"
    const val BOOKKEEPING = "BOOKKEEPING"
    const val VALIDATION = "VALIDATION"
    const val NORMALIZATION = "NORMALIZATION"
    const val SEARCH = "SEARCH"
    const val LOOKUP = "LOOKUP"
    const val SERVICE_DELEGATION = "SERVICE_DELEGATION"
    const val SERVICE_INIT = "SERVICE_INIT"
    const val DATA_MODIFY = "DATA_MODIFY"
    const val LOCAL_VALUE_MODIFY = "LOCAL_VALUE_MODIFY"
    const val BOOL_COMBINATORICS = "BOOL_COMBINATORICS"
    const val DATA_TRANSFORMATION = "DATA_TRANSFORMATION"
    const val RETURN_VALUE = "RETURN_VALUE"
    const val CHAINING = "CHAINING"
    const val LOCAL_COPY = "LOCAL_COPY"
    const val USER_FEEDBACK = "USER_FEEDBACK"

    val allKnown = setOf(
        UNTAGGED, LOGGING, ERROR_HANDLING, BOOKKEEPING, VALIDATION,
        NORMALIZATION, SEARCH, LOOKUP, SERVICE_DELEGATION, SERVICE_INIT,
        DATA_MODIFY, LOCAL_VALUE_MODIFY, BOOL_COMBINATORICS, DATA_TRANSFORMATION,
        RETURN_VALUE, CHAINING, LOCAL_COPY, USER_FEEDBACK
    )

    fun isKnown(intent: Intent): Boolean = intent in allKnown
}