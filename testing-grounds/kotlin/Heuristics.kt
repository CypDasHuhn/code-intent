package testinggrounds

class Service {
    fun warn(message: String) {}
    fun info(message: String) {}
}

private val logger = Service()

fun falsePositiveByName() {
    logger.warn("looks like logging")
}
