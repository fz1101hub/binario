package it.binario.wear

/** Resolves ids to resource names so tests see which string a surface picked, not its Italian copy. */
internal object ResourceNameStrings : WearStringProvider {
    private val names: Map<Int, String> = R.string::class.java.fields
        .filter { it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    override fun string(id: Int, vararg formatArgs: Any): String =
        names.getValue(id) + if (formatArgs.isEmpty()) "" else formatArgs.joinToString(", ", "(", ")")
}
