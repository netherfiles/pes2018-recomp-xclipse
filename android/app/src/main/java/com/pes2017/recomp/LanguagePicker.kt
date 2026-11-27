package com.pes2017.recomp

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LanguagePicker {

    private val languageTags = arrayOf(
        "en",
        "pt-BR",
        "es"
    )

    private val languageNames = arrayOf(
        "English",
        "Português (Brasil)",
        "Español"
    )

    fun show(activity: AppCompatActivity) {

        val appLocales = AppCompatDelegate.getApplicationLocales()

        val currentTag = appLocales.get(0)
            ?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()

        val currentLanguage = currentTag.substringBefore("-")

        val selectedIndex = languageTags.indexOfFirst { tag ->
            tag.equals(currentTag, ignoreCase = true) ||
                tag.substringBefore("-").equals(
                    currentLanguage,
                    ignoreCase = true
                )
        }.let { index ->
            if (index < 0) 0 else index
        }

        AlertDialog.Builder(activity)
            .setTitle("Idioma / Language")
            .setSingleChoiceItems(
                languageNames,
                selectedIndex
            ) { dialog, which ->

                if (which in languageTags.indices) {
                    val selectedLanguage = languageTags[which]

                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            selectedLanguage
                        )
                    )
                }

                dialog.dismiss()
            }
            .setNegativeButton("Cancelar / Cancel", null)
            .show()
    }
}
