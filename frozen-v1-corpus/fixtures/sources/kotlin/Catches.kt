package testinggrounds

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Catches")

fun loggingCatch() {
    try {
        work()
    } catch (failure: Exception) {
        log.warn("failed", failure)
    }
}

fun catchWithRethrow() {
    try {
        work()
    } catch (e: Exception) {
        log.warn("failed", e)
        throw e
    }
}

fun catchWithDeadLocal() {
    try {
        work()
    } catch (ex: Exception) {
        if (1 == 1) {
            val unused = ""
            log.warn("failed", ex)
        }
    }
}

fun allLoggingTry() {
    try {
        log.warn("attempt")
    } catch (failure: Exception) {
        log.warn("failed", failure)
    }
}

fun catchWithAssistCandidate() {
    try {
        work()
    } catch (ex: Exception) {
        val message = "failed"
        log.warn(message, ex)
    }
}

fun work() {
}
