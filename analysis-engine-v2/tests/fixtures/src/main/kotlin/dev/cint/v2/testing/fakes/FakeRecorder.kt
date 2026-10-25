package dev.cint.v2.testing.fakes

class FakeRecorder {
    data class Call(val name: String, val details: String = "")

    private val entries = mutableListOf<Call>()

    val calls: List<Call>
        get() = entries.toList()

    fun record(name: String, details: String = "") {
        entries.add(Call(name, details))
    }

    fun names(): List<String> = entries.map { it.name }

    fun count(name: String): Int = entries.count { it.name == name }

    fun called(name: String): Boolean = count(name) > 0

    fun details(name: String): List<String> =
        entries.filter { it.name == name }.map { it.details }

    fun clear() {
        entries.clear()
    }
}
