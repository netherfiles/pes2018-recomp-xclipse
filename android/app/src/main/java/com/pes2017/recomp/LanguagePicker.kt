package com.pes2017.recomp

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

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
        val currentTag = AppCompatDelegate
            .getApplicationLocales()
            .get(0)
            ?.toLanguageTag()
            ?: "en"

        val selectedIndex = languageTags.indexOfFirst {
            it.equals(currentTag, ignoreCase = true)
        }.let { if (it < 0) 0 else it }

        AlertDialog.Builder(activity)
            .setTitle("Select language / Idioma")
            .setSingleChoiceItems(
                languageNames,
                selectedIndex
            ) { dialog, which ->

                val selectedLanguage = languageTags[which]

                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(
                        selectedLanguage
                    )
                )

                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
