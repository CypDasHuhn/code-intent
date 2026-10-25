private val log = LoggerFactory.getLogger("Composition")

fun compose(repository: SearchRepository, call: String?, nodes: List<String>, values: List<String>): Boolean {
    val uses = repository.findAll().map { it.name }
    for (node in uses) {
        val current = node ?: continue
        if (uses.isEmpty()) continue
        if (receiverNode(node) != null) return false
        log.info(current)
    }
    val selected = uses.all { isConstant(it) || isIdentifier(it) }
    val pure =
        isConstant(selected) ||
            isIdentifier(selected) ||
            isAnnotatedPureCall(selected) ||
            isUserPureCall(selected)
    return pure
}

fun receiverNode(value: String): String? = if (value.trim().isNotEmpty()) value else null
fun isConstant(value: String): Boolean = value.isNotEmpty()
fun isIdentifier(value: String): Boolean = value.isNotBlank()
fun isAnnotatedPureCall(value: String): Boolean = value.startsWith("pure")
fun isUserPureCall(value: String): Boolean = value.endsWith("pure")
