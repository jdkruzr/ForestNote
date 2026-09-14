package com.forestnote.app.notes

import com.forestnote.app.notes.SharedSettingsView.Section as S

/** Executable migration checklist. Legacy controls are accounted for without binding
 * their notebook-only services to a mixed-library owner. IDs support a parity test. */
internal object SharedSettingsInventory {
    class Item(val section:SharedSettingsView.Section,val title:Int,val detail:Int,vararg val legacyIds:Int)
    val items=listOf(
        Item(S.STARTUP,R.string.settings_startup,R.string.settings_plan_startup,R.id.rb_start_last,R.id.rb_start_library),
        Item(S.DATA,R.string.settings_plan_backup_title,R.string.settings_plan_backup,R.id.btn_backup_library,R.id.btn_restore_library),
        Item(S.DATA,R.string.settings_plan_import_title,R.string.settings_plan_import),
        Item(S.DATA,R.string.settings_plan_storage_title,R.string.settings_plan_storage),
        Item(S.SYNC,R.string.settings_plan_enrollment_title,R.string.settings_plan_enrollment,R.id.check_sync_enabled,R.id.input_sync_url,R.id.input_sync_username,R.id.input_sync_password,R.id.btn_sync_save),
        Item(S.SYNC,R.string.settings_plan_schedule_title,R.string.settings_plan_schedule,R.id.input_sync_interval,R.id.check_sync_on_close),
        Item(S.SYNC,R.string.settings_plan_transfer_title,R.string.settings_plan_transfer),
        Item(S.SYNC,R.string.settings_plan_history_title,R.string.settings_plan_history),
        Item(S.TRANSCRIPTION,R.string.settings_plan_provider_title,R.string.settings_plan_provider,R.id.rb_transcription_off,R.id.rb_transcription_openai,R.id.rb_transcription_anthropic,R.id.input_transcription_base_url,R.id.input_transcription_model,R.id.input_transcription_api_key,R.id.btn_transcription_save,R.id.btn_transcription_test),
        Item(S.RECOGNITION,R.string.settings_plan_models_title,R.string.settings_plan_models,R.id.container_recognition_models,R.id.btn_download_recognition_model),
        Item(S.RECOGNITION,R.string.settings_plan_backfill_title,R.string.settings_plan_backfill),
        Item(S.CALENDAR,R.string.settings_plan_calendar_title,R.string.settings_plan_calendar,R.id.input_caldav_url,R.id.input_caldav_username,R.id.input_caldav_password,R.id.btn_caldav_save,R.id.btn_caldav_test),
        Item(S.CALENDAR,R.string.settings_plan_queue_title,R.string.settings_plan_queue,R.id.container_caldav_queued,R.id.btn_caldav_drain_now),
        Item(S.RECYCLE,R.string.settings_plan_retention_title,R.string.settings_plan_retention,R.id.input_bin_retention_days),
        Item(S.DEVICE,R.string.settings_plan_preview_title,R.string.settings_plan_preview,R.id.check_viwoods_native_preview),
        Item(S.DEVICE,R.string.settings_plan_logs_title,R.string.settings_plan_logs,R.id.check_debug_logs),
        Item(S.DEVICE,R.string.settings_plan_refresh_title,R.string.settings_plan_refresh),
        Item(S.READING,R.string.settings_plan_reading_title,R.string.settings_plan_reading),
        Item(S.READING,R.string.settings_plan_annotations_title,R.string.settings_plan_annotations),
        Item(S.READING,R.string.settings_plan_images_title,R.string.settings_plan_images),
        Item(S.READING,R.string.settings_plan_navigation_title,R.string.settings_plan_navigation),
        Item(S.ABOUT,R.string.settings_plan_licenses_title,R.string.settings_plan_licenses),
    )
    val connectedLegacyIds=setOf(R.id.btn_notebook_defaults,R.id.text_app_version)
}
