package com.unifiedart.app.data

/** Mirrors e621's public blacklist filter semantics; never adds app-owned rules. */
fun blacklisted(art: Artwork, rules: String): Boolean {
    if (art.source != Source.E621) return art.blocked
    return rules.lineSequence().map { it.substringBefore(" #").trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }.any { line ->
            val tokens = line.split(Regex("\\s+")).distinct()
            val required = tokens.filterNot { it.takeWhile { c -> c == '~' || c == '-' }.contains('~') }
            val optional = tokens - required.toSet()
            required.all { matchesToken(art, it) } && (optional.isEmpty() || optional.any { matchesToken(art, it) })
        }
}
private fun matchesToken(art: Artwork, raw: String): Boolean {
    val prefix = raw.takeWhile { it == '~' || it == '-' }
    val token = raw.drop(prefix.length)
    val type = token.substringBefore(':')
    var value = token.substringAfter(':', "")
    val numeric = setOf("tagcount", "id", "width", "height", "score", "favcount", "userid", "filesize")
    val result = when {
        type == "pool" -> value in art.pools
        type == "fav" -> art.favorite
        type in numeric -> numberMatches(art.filterFields[type]?.toDoubleOrNull() ?: Double.NaN, value, type == "filesize")
        type == "rating" -> {
            value = when (value) { "safe" -> "s"; "questionable" -> "q"; "explicit" -> "e"; else -> value }
            art.filterFields["rating"] == value
        }
        type in setOf("user", "uploader", "username") -> if (value.startsWith('!'))
            numberMatches(art.filterFields["userid"]?.toDoubleOrNull() ?: Double.NaN, value.drop(1), false)
            else art.filterFields["uploader"]?.lowercase() == value
        type in setOf("status", "type") -> value in art.filterFields[type].orEmpty().split(' ')
        else -> {
            val pattern = token.split('*').joinToString(".*") { Regex.escape(it) }.let { Regex("^$it$") }
            art.tags.any { pattern.matches(it.lowercase()) }
        }
    }
    return if ('-' in prefix) !result else result
}
private fun numberMatches(actual: Double, expression: String, filesize: Boolean): Boolean {
    fun number(raw: String): Double {
        if (!filesize) return raw.toDoubleOrNull() ?: Double.NaN
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)(b|kb|mb)?").matchEntire(raw) ?: return Double.NaN
        return (match.groupValues[1].toDouble() * when (match.groupValues[2]) { "kb" -> 1024; "mb" -> 1048576; else -> 1 }).toLong().toDouble()
    }
    if (".." in expression) {
        val parts = expression.split("..", limit = 2)
        return (parts[0].isEmpty() || actual >= number(parts[0])) && (parts[1].isEmpty() || actual <= number(parts[1]))
    }
    val op = expression.takeWhile { it in "<=>" }
    val value = number(expression.drop(op.length))
    return when (op) {
        "<" -> actual < value; "<=", "=<" -> actual <= value
        ">" -> actual > value; ">=", "=>" -> actual >= value
        else -> if (filesize) actual >= (value * .95).toLong() && actual <= (value * 1.05).toLong() else actual == value
    }
}
