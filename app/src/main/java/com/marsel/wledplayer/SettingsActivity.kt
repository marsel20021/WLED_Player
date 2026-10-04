package com.marsel.wledplayer

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Меняем тему на NoActionBar, чтобы наша Toolbar встала без конфликтов
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Настройка нашей кастомной Toolbar
        val toolbar = findViewById<Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки WLED"

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish() // Возврат к плееру при нажатии на стрелочку
        return true
    }

    // Класс фрагмента, который берет настройки из XML
    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // Принудительно задаем темный фон, чтобы элементы настроек были видны
            view.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        }
    }
}