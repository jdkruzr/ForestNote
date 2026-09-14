package com.forestnote.app.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Borrows the already-selected owner. Activity Back restores the exact caller, reader or writer. */
class SettingsQualificationActivity:Activity() {
    private var page:SharedSettingsView?=null
    private var store:NotebookStore?=null
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        check(packageName=="com.forestnote.qualification")
        val owner=ReaderHostQualificationSession.store ?: runCatching {SetupQualificationSession.host?.readerStore()}.getOrNull()
        if(owner==null) {setContentView(TextView(this).apply {setText(R.string.shared_settings_unavailable)});return}
        store=owner
        val books=owner.readerLibraryForQualification(applicationContext.cacheDir)
        page=SharedSettingsView(this,owner,books,{finish()},if(ReaderHostQualificationSession.store==null) ({
            // Clear any borrowed writer first; Reader owns the render-cleanup barrier to setup.
            startActivity(Intent(this,ReaderHostQualificationActivity::class.java)
                .putExtra(ReaderHostQualificationActivity.RETURN_TO_SETUP,true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }) else null,saved?.getBundle("settings"))
        setContentView(page)
    }
    override fun onResume() {super.onResume();store?.resumeReaderWork()}
    override fun onPause() {
        if(!isFinishing && !isChangingConfigurations) store?.pauseReaderWork()
        super.onPause()
    }
    override fun onSaveInstanceState(out:Bundle) {page?.let {out.putBundle("settings",it.snapshot())};super.onSaveInstanceState(out)}
    @Deprecated("Settings owns section back navigation")
    override fun onBackPressed() {page?.back() ?: finish()}
    override fun onDestroy() {page?.close();super.onDestroy()}
}
