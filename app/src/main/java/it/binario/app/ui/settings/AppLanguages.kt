package it.binario.app.ui.settings

import android.app.LocaleManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import it.binario.app.R
import java.util.Locale

/** One language of the app: its BCP-47 tag (as in res/xml/locale_config.xml) and its name in that language. */
internal data class AppLanguage(val tag: String, val nativeName: String)

/**
 * The languages of res/xml/locale_config.xml in the order of the in-app picker (docs/l10n/LOCALES.md).
 *
 * The names are endonyms, the same in every interface language, so they are constants rather than translated
 * resources. The order is the Unicode root collation of the names: Latin script alphabetically, then Cyrillic,
 * Arabic, Devanagari, Thai, Myanmar, Hangul and Han, the order Android's own language lists follow.
 */
internal object AppLanguages {
    val all: List<AppLanguage> = listOf(
        AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("en", "English"),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("it", "Italiano"),
        AppLanguage("pt", "Português"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("ru", "Русский"),
        AppLanguage("ar", "العربية"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("th", "ไทย"),
        AppLanguage("my", "မြန်မာ"),
        AppLanguage("ko", "한국어"),
        AppLanguage("zh-CN", "中文 (简体)"),
        AppLanguage("ja", "日本語")
    )

    /**
     * The picker entry of the per-app locale [languageTag] (the first tag Android reports), or null when the app
     * follows the system language or the tag is not one of [all]. Legacy codes match their modern tag ("in" is
     * Indonesian), a regional or script variant matches its language ("pt-BR" is Português), and Traditional
     * Chinese never matches the Simplified Chinese entry.
     */
    fun match(languageTag: String?): AppLanguage? {
        if (languageTag.isNullOrBlank()) return null
        all.firstOrNull { it.tag.equals(languageTag, ignoreCase = true) }?.let { return it }
        val locale = Locale.forLanguageTag(languageTag)
        val language = modernLanguage(locale.language)
        if (language == "zh" && (locale.script.equals("Hant", ignoreCase = true) || locale.country in TRADITIONAL_CHINESE_REGIONS)) {
            return null
        }
        return all.firstOrNull { modernLanguage(Locale.forLanguageTag(it.tag).language) == language }
    }

    /** Java and older Android report Indonesian, Hebrew and Yiddish with their withdrawn ISO 639 codes. */
    private fun modernLanguage(language: String): String = when (language) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> language
    }

    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
}

/**
 * The in-app language picker (Android 13 and later, where apps can set their own language): "System" first, then
 * every language with its own name. Choosing one calls [LocaleManager.setApplicationLocales]; Android then
 * recreates the activity in the new language and remembers the choice, which is also offered in the system
 * settings from res/xml/locale_config.xml.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun AppLanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    var selected by remember(localeManager) {
        mutableStateOf(localeManager?.applicationLocales?.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()?.let(AppLanguages::match))
    }
    fun choose(language: AppLanguage?) {
        val manager = localeManager ?: return
        selected = language
        manager.applicationLocales = language?.let { android.os.LocaleList.forLanguageTags(it.tag) } ?: android.os.LocaleList.getEmptyLocaleList()
    }
    Column(modifier.fillMaxWidth().selectableGroup()) {
        LanguageRow(
            label = stringResource(R.string.theme_system_label),
            detail = stringResource(R.string.theme_system_description),
            localeTag = null,
            selected = selected == null,
            testTag = "settings-language-system",
            onSelect = { choose(null) }
        )
        AppLanguages.all.forEach { language ->
            LanguageRow(
                label = language.nativeName,
                detail = null,
                localeTag = language.tag,
                selected = selected == language,
                testTag = "settings-language-${language.tag}",
                onSelect = { choose(language) }
            )
        }
    }
}

@Composable
private fun LanguageRow(label: String, detail: String?, localeTag: String?, selected: Boolean, testTag: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            // The name in its own language: the right glyph variants (Simplified Chinese, Japanese) and the right
            // TalkBack voice, whatever the interface language.
            val style = MaterialTheme.typography.bodyLarge
            Text(label, style = localeTag?.let { style.copy(localeList = LocaleList(it)) } ?: style)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
