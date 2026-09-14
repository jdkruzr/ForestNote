package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Presentation-only: no store, credentials, network client or model manager is constructed. */
class SettingsResourcesQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun settingsLayoutInflatesWithResourceLabels() {
        instrumentation.runOnMainSync {
            val themed=ContextThemeWrapper(context,android.R.style.Theme_Material_Light_NoActionBar)
            val view=LayoutInflater.from(themed).inflate(R.layout.view_settings,null,false)
            assertEquals(context.getString(R.string.settings_save_credentials),view.findViewById<TextView>(R.id.btn_sync_save).text)
            assertEquals(context.getString(R.string.settings_download_model),view.findViewById<TextView>(R.id.btn_download_recognition_model).text)
            assertEquals(context.getString(R.string.settings_blank),view.findViewById<TextView>(R.id.rb_template_blank).text)
        }
    }

    @Test fun formattedMessagesKeepUserTextAndChoosePluralForms() {
        val config=android.content.res.Configuration(context.resources.configuration).apply {setLocale(java.util.Locale.ENGLISH)}
        val english=context.createConfigurationContext(config)
        val title="User %s / 100% / 文本"
        assertEquals("$title · 1 retry",english.resources.getQuantityString(R.plurals.settings_task_retries,1,title,1))
        assertEquals("$title · 3 retries",english.resources.getQuantityString(R.plurals.settings_task_retries,3,title,3))
        assertEquals("Remove the $title recognition model from this device?",english.getString(R.string.settings_delete_model_confirm,title))
        assertEquals("Endpoint test failed: $title",english.getString(R.string.settings_endpoint_failed,title))
        assertEquals("47 mm",english.getString(R.string.settings_pitch_mm,47))
    }
}
