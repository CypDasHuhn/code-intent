package testinggrounds

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Scaffolds")

fun constantGuard() {
    if (1 == 1) {
        log.warn("constant")
    }
}

fun configGuard(loggingEnabled: Boolean) {
    if (loggingEnabled) {
        log.warn("config")
    }
}

fun nonLoggingGuard(x: Int) {
    if (x > 0) {
        log.warn("x")
    }
}

fun mixedBranch() {
    if (1 == 1) {
        log.warn("then")
    } else {
        doWork()
    }
}

fun nested() {
    if (1 == 1) {
        if (2 == 2) {
            log.warn("nested")
        }
    }
}

fun doWork() {
}
