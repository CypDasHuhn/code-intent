package sample

import mu.KotlinLogging
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Sample::class.java)
private val logger = KotlinLogging.logger {}

class Sample {
    fun doWork(id: String): Int {
        log.info("starting work for {}", id)
        val value = compute(id)
        if (value < 0) {
            log.error("negative value for {}", id)
            throw IllegalStateException("bad")
        }
        println("done: $value")
        return value
    }

    fun compute(id: String): Int {
        require(id.isNotEmpty()) { "id must not be empty" }
        logger.debug { "computing $id" }
        return id.hashCode()
    }

    fun guard(x: Int) {
        if (x == 0) {
            error("x must not be zero")
        }
        check(x > 0)
        System.out.println("guard ok")
    }
}
