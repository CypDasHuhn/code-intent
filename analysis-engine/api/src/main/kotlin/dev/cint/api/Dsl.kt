package dev.cint.api

sealed interface Condition {
    fun test(): Boolean
}

@JvmInline
value class DslValue<T>(
    internal val raw: T,
)

internal class EqualsCondition<T>(
    private val actual: T,
    private val expected: T,
) : Condition {
    override fun test(): Boolean = actual == expected
}

internal class NotEqualsCondition<T>(
    private val actual: T,
    private val expected: T,
) : Condition {
    override fun test(): Boolean = actual != expected
}

internal class GreaterThanCondition<T : Comparable<T>>(
    private val actual: T,
    private val expected: T,
) : Condition {
    override fun test(): Boolean = actual > expected
}

internal class LessThanCondition<T : Comparable<T>>(
    private val actual: T,
    private val expected: T,
) : Condition {
    override fun test(): Boolean = actual < expected
}

internal class AndCondition(
    private val left: Condition,
    private val right: Condition,
) : Condition {
    override fun test(): Boolean = left.test() && right.test()
}

internal class OrCondition(
    private val left: Condition,
    private val right: Condition,
) : Condition {
    override fun test(): Boolean = left.test() || right.test()
}

infix fun <T> DslValue<T>.eq(other: T): Condition = EqualsCondition(raw, other)

infix fun <T> DslValue<T>.ne(other: T): Condition = NotEqualsCondition(raw, other)

infix fun <T : Comparable<T>> DslValue<T>.gt(other: T): Condition = GreaterThanCondition(raw, other)

infix fun <T : Comparable<T>> DslValue<T>.lt(other: T): Condition = LessThanCondition(raw, other)

infix fun Condition.and(other: Condition): Condition = AndCondition(this, other)

infix fun Condition.or(other: Condition): Condition = OrCondition(this, other)

fun interface Predicate {
    fun test(view: View): Boolean

    infix fun and(other: Predicate): Predicate = Predicate { view -> test(view) && other.test(view) }

    infix fun or(other: Predicate): Predicate = Predicate { view -> test(view) || other.test(view) }
}
