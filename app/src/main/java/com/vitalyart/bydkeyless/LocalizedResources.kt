package com.vitalyart.bydkeyless

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes

fun Context.localizedString(language: String, @StringRes resourceId: Int): String {
    if (language == "system") return getString(resourceId)
    val configuration = Configuration(resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(language))
    }
    return createConfigurationContext(configuration).getString(resourceId)
}
