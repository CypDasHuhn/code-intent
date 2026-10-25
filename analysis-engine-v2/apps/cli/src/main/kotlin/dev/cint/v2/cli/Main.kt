@file:JvmName("Main")

package dev.cint.v2.cli

import kotlin.system.exitProcess

fun main(argv: Array<String>) {
    exitProcess(Cli.run(argv))
}
