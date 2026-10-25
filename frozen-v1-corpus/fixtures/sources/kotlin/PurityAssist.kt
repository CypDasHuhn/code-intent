package testinggrounds

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Purity")

@Pure
fun pureText(): String = "text"

@Contract(pure = true)
fun contractedText(): String = "text"

fun impureText(): String = "text"

fun literalAssist() {
    val message = "literal"
    log.warn(message)
}

fun annotatedAssist() {
    val message = pureText()
    log.warn(message)
}

fun contractedAssist() {
    val message = contractedText()
    log.warn(message)
}

fun unannotatedAssist() {
    val message = impureText()
    log.warn(message)
}

fun unusedLocal() {
    val unused = ""
    log.warn("still logs")
}
