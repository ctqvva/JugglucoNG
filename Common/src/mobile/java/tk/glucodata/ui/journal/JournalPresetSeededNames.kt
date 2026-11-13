package tk.glucodata.ui.journal

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import tk.glucodata.R

@Volatile
private var seededGenericPresetNames: Set<String>? = null

/**
 * Every name the app seeds, or has seeded, for the two generic presets: the current string in
 * each language of `locales_config.xml`, plus [LEGACY_SEEDED_GENERIC_PRESET_NAMES]. Read once;
 * the resources of an installed build do not change.
 */
internal fun seededGenericPresetNames(context: Context): Set<String> {
    seededGenericPresetNames?.let { return it }
    val app = context.applicationContext
    val names = LinkedHashSet(LEGACY_SEEDED_GENERIC_PRESET_NAMES)
    for (tag in shippedLanguageTags(app)) {
        runCatching {
            val configuration = Configuration(app.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(tag))
            val localized = app.createConfigurationContext(configuration)
            names += localized.getString(R.string.journal_preset_rapid_generic).trim()
            names += localized.getString(R.string.journal_preset_long_generic).trim()
        }
    }
    // Whatever the list of languages said, the strings in use right now are seeded names.
    names += app.getString(R.string.journal_preset_rapid_generic).trim()
    names += app.getString(R.string.journal_preset_long_generic).trim()
    return names.also { seededGenericPresetNames = it }
}

private fun shippedLanguageTags(context: Context): List<String> = runCatching {
    val tags = ArrayList<String>()
    val parser = context.resources.getXml(R.xml.locales_config)
    try {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(tags::add)
            }
            event = parser.next()
        }
    } finally {
        parser.close()
    }
    tags
}.getOrDefault(emptyList())
