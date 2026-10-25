package testinggrounds

fun requirePositive(x: Int) {
    require(x > 0)
}

fun requireValue(value: String?) {
    requireNotNull(value)
}

fun checkPositive(x: Int) {
    check(x > 0)
}

fun checkValue(value: String?) {
    checkNotNull(value)
}

fun fail(message: String) {
    error(message)
}

fun todo() {
    TODO()
}

fun rethrow(failure: Exception): Nothing = throw failure
