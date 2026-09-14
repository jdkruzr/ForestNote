package com.forestnote.app.notes

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import kotlinx.coroutines.*

/** OG Settings' opaque header/scrolling-page pattern, with same-owner service capabilities. */
internal class SharedSettingsView(context:Context,private val store:NotebookStore,private val books:ReaderLibraryAccess,
    private val onClose:()->Unit,private val onRecovery:(()->Unit)?,saved:Bundle?=null):LinearLayout(context) {
    enum class Section(val title:Int) {
        HOME(R.string.settings_title),DEFAULTS(R.string.settings_notebook_defaults),SYNC(R.string.shared_sync_title),RECOGNITION(R.string.recognition_settings_title),
        LANGUAGES(R.string.handwriting_language_title),STARTUP(R.string.settings_startup),DATA(R.string.settings_data_title),
        TRANSCRIPTION(R.string.settings_transcription_title),CALENDAR(R.string.settings_calendar_title),RECYCLE(R.string.settings_recycle_bin),
        DEVICE(R.string.settings_device_title),READING(R.string.settings_reading_title),ABOUT(R.string.settings_about)
    }
    private val gap=resources.getDimensionPixelSize(R.dimen.library_surface_gap)
    private var job:Job?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var defaults:NotebookDefaultsEditor?=null
    private var languages:HandwritingLanguageEditor?=null
    private val handwriting=HandwritingPreferences.shared(context)
    private val body=LinearLayout(context).apply {orientation=VERTICAL;setPadding(gap,gap,gap,gap)}
    private val scroll=ScrollView(context).apply {addView(body);isFillViewport=true}
    private val heading:TextView
    var section=Section.HOME;private set
    init {
        orientation=VERTICAL;setBackgroundColor(Color.WHITE);isClickable=true;tag="sharedSettingsPage"
        LayoutInflater.from(context).inflate(R.layout.settings_page_header,this,true)
        heading=findViewById<TextView>(R.id.settings_page_title).apply {tag="settingsHeading"}
        findViewById<Button>(R.id.btn_settings_back).apply {tag="settingsBack";LibrarySurfaceStyle.action(this);setOnClickListener {back()}}
        addView(scroll,LayoutParams(-1,0,1f))
        val restored=runCatching {Section.valueOf(saved?.getString("section").orEmpty())}.getOrDefault(Section.HOME)
        show(restored,saved?.getBundle(if(restored==Section.LANGUAGES) "language" else "defaults"))
    }
    private fun action(id:Int,key:String,callback:()->Unit)=Button(context).apply {
        setText(id);tag=key;LibrarySurfaceStyle.action(this);setOnClickListener {callback()}
    }.also {body.addView(it,LayoutParams(-1,-2).apply {bottomMargin=gap})}
    private fun label(id:Int,key:String?=null)=TextView(context).apply {
        setText(id);tag=key;EinkUiStyle.text(this,R.dimen.eink_ui_body_text);setPadding(0,0,0,gap)
    }.also {body.addView(it,LayoutParams(-1,-2))}
    fun back() {
        if(defaults?.saving==true || languages?.saving==true) return
        if(section==Section.HOME) onClose() else show(if(section==Section.LANGUAGES) Section.RECOGNITION else Section.HOME)
    }
    fun show(next:Section,savedDefaults:Bundle?=null) {
        if(defaults?.saving==true || languages?.saving==true) return
        job?.cancel();job=null;defaults?.close();defaults=null;languages?.close();languages=null;body.removeAllViews();scroll.scrollTo(0,0)
        section=next;heading.setText(next.title)
        findViewById<Button>(R.id.btn_settings_back).isEnabled=true
        when(next) {
            Section.HOME -> {
                label(R.string.shared_settings_description)
                label(R.string.settings_available)
                for(item in listOf(Section.DEFAULTS,Section.RECOGNITION,Section.SYNC)) action(item.title,"settingsSection:$item") {show(item)}
                onRecovery?.let {action(R.string.shared_sync_recovery,"settingsRecovery",it)}
                label(R.string.settings_integration_checklist)
                label(R.string.settings_plan_notice)
                for(item in listOf(Section.STARTUP,Section.DATA,Section.TRANSCRIPTION,Section.CALENDAR,Section.RECYCLE,Section.DEVICE,Section.READING,Section.ABOUT))
                    action(item.title,"settingsSection:$item") {show(item)}
            }
            Section.DEFAULTS -> {
                defaults=NotebookDefaultsEditor(context,store,{show(Section.HOME)},changed={
                    findViewById<Button>(R.id.btn_settings_back).isEnabled=defaults?.saving!=true
                },saved=savedDefaults).also {body.addView(it)}
                label(R.string.shared_settings_draft_notice)
            }
            Section.SYNC -> {
                val message=label(R.string.shared_sync_unconfigured,"sharedSyncStatus")
                val retry=action(R.string.shared_sync_retry,"sharedSyncRetry") {store.sharedSyncControls.retry()}.apply {isEnabled=false}
                onRecovery?.let {action(R.string.shared_sync_recovery,"sharedSyncRecovery",it)}
                val detail=label(R.string.shared_sync_description)
                label(R.string.shared_sync_retry_detail)
                job=scope.launch {store.sharedSyncControls.status.collect {value ->
                    message.setText(SharedSyncDialog.message(value))
                    retry.isEnabled=SharedSyncAccess.canRetry(value);retry.alpha=if(retry.isEnabled) 1f else .4f
                    detail.setText(when(value) {ForegroundSyncStatus.NotConfigured -> R.string.shared_sync_unconfigured_detail
                        ForegroundSyncStatus.Closed -> R.string.shared_sync_closed_detail;else -> R.string.shared_sync_active_detail})
                }}
            }
            Section.RECOGNITION -> {
                action(R.string.handwriting_language_change,"recognitionChangeLanguage") {show(Section.LANGUAGES)}
                label(R.string.recognition_settings_description)
                label(R.string.handwriting_reader_status)
                val retry=action(R.string.settings_retry,"recognitionRetry") {if(books.recognitionStatus()?.value?.retryable==true) books.retryRecognition()}.apply {isEnabled=false}
                val language=label(R.string.recognition_settings_title,"recognitionLanguage")
                val message=label(R.string.recognition_not_configured,"recognitionStatus")
                label(R.string.handwriting_writer_status)
                books.recognitionStatus()?.let {state -> job=scope.launch {state.collect {value ->
                    language.text=context.getString(R.string.recognition_language,ReaderRecognitionText.language(context,value.language))
                    message.text=ReaderRecognitionText.message(context,value);retry.isEnabled=value.retryable;retry.alpha=if(value.retryable) 1f else .4f
                }}}
            }
            Section.LANGUAGES -> {
                languages=HandwritingLanguageEditor(context,handwriting,{show(Section.RECOGNITION)},changed={
                    findViewById<Button>(R.id.btn_settings_back).isEnabled=languages?.saving!=true
                },saved=savedDefaults).also {body.addView(it)}
            }
            Section.ABOUT -> {
                label(R.string.alexandria_name)
                val version=label(R.string.settings_loading,"settingsBuildVersion")
                job=scope.launch {
                    val name=withContext(Dispatchers.IO) {runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull()}
                    version.text=context.getString(R.string.alexandria_build,name ?: context.getString(R.string.settings_unknown_error))
                }
                label(R.string.alexandria_server_name)
                label(R.string.alexandria_identity_note)
            }
            else -> label(R.string.settings_plan_notice)
        }
        for(item in SharedSettingsInventory.items.filter {it.section==next}) planned(item)
    }
    private fun planned(item:SharedSettingsInventory.Item) {
        val card=LinearLayout(context).apply {
            orientation=VERTICAL;tag="settingsPlanned:${item.title}";background=LibrarySurfaceStyle.surface(context)
            setPadding(gap,gap,gap,gap)
        }
        fun line(id:Int,title:Boolean=false)=TextView(context).apply {
            setText(id);EinkUiStyle.text(this,if(title) R.dimen.eink_ui_label_text else R.dimen.eink_ui_body_text,medium=title)
            setPadding(0,0,0,gap)
        }.also {card.addView(it)}
        line(item.title,true);line(R.string.settings_not_connected,true);line(item.detail)
        body.addView(card,LayoutParams(-1,-2).apply {topMargin=gap})
    }
    fun snapshot()=Bundle().apply {putString("section",section.name);defaults?.let {putBundle("defaults",it.snapshot())};languages?.let {putBundle("language",it.snapshot())}}
    fun close() {defaults?.close();languages?.close();scope.cancel()}
}
