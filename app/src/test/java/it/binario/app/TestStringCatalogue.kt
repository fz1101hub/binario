package it.binario.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The strings of `app/src/main/res/values[-locale]/strings.xml` as Android resolves them (escaped quotes and new lines
 * unescaped), so JVM tests check the wording users really read. Null [locale] is the Italian default.
 */
object TestStringCatalogue {
    private val cache = HashMap<String?, Map<String, String>>()

    fun of(locale: String? = null): Map<String, String> = synchronized(cache) {
        cache.getOrPut(locale) { read(locale) }
    }

    private fun read(locale: String?): Map<String, String> {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(repositoryRoot, "app/src/main/res/values${locale?.let { "-$it" }.orEmpty()}/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                if (element.tagName == "string") {
                    put(element.getAttribute("name"), element.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n"))
                }
            }
        }
    }
}
