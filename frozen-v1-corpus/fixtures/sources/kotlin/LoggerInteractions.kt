package testinggrounds

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TestingGrounds")

fun directLogger() {
    logger.info("direct")
}

fun stdoutPrint() {
    System.out.println("stdout")
}

fun plainPrint() {
    println("plain")
}

fun unqualifiedError() {
    error("not logging")
}

fun fakeReceiver() {
    val fake = FakeLogger()
    fake.info("not logging")
}

class FakeLogger {
    fun info(message: String) {}
}
