package dev.cint.v2.syntax.treesitter

internal data class TextEdit(
    val startByte: Int,
    val oldEndByte: Int,
    val newEndByte: Int,
    val startLine: Int,
    val startColumn: Int,
    val oldEndLine: Int,
    val oldEndColumn: Int,
    val newEndLine: Int,
    val newEndColumn: Int,
) {
    val isNoOp: Boolean get() = startByte == oldEndByte && oldEndByte == newEndByte

    companion object {
        fun between(
            old: ByteArray,
            new: ByteArray,
        ): TextEdit {
            val prefixLimit = minOf(old.size, new.size)
            var prefix = 0
            while (prefix < prefixLimit && old[prefix] == new[prefix]) prefix++

            val suffixLimit = minOf(old.size - prefix, new.size - prefix)
            var suffix = 0
            while (suffix < suffixLimit && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++

            val oldEnd = old.size - suffix
            val newEnd = new.size - suffix
            val startPoint = pointAt(old, prefix)
            val oldEndPoint = pointAt(old, oldEnd)
            val newEndPoint = pointAt(new, newEnd)
            return TextEdit(
                startByte = prefix,
                oldEndByte = oldEnd,
                newEndByte = newEnd,
                startLine = startPoint.first,
                startColumn = startPoint.second,
                oldEndLine = oldEndPoint.first,
                oldEndColumn = oldEndPoint.second,
                newEndLine = newEndPoint.first,
                newEndColumn = newEndPoint.second,
            )
        }

        private fun pointAt(
            bytes: ByteArray,
            offset: Int,
        ): Pair<Int, Int> {
            var line = 0
            var column = 0
            val limit = offset.coerceIn(0, bytes.size)
            for (index in 0 until limit) {
                if (bytes[index] == '\n'.code.toByte()) {
                    line++
                    column = 0
                } else {
                    column++
                }
            }
            return line to column
        }
    }
}
