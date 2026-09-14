package com.forestnote.app.notes

/** Uses the actual writer, but never its production database factory or legacy sync driver.
 * If Android restores this screen without its process owner, fail closed to Library Setup. */
class WriterHostQualificationActivity : MainActivity() {
    internal override val requiresWriterAttachment = true
    internal override fun writerAttachment(): WriterAttachment? {
        check(packageName == "com.forestnote.qualification")
        val store = ReaderHostQualificationSession.store
            ?: runCatching { SetupQualificationSession.host?.readerStore() }.getOrNull()
            ?: return null
        intent.getStringExtra(CREATION)?.let { id ->
            val creation = store.readerLibraryForQualification(applicationContext.cacheDir).libraryUi.notebookCreation
                ?.takeIf { it.id == id } ?: return null
            return WriterAttachment(store, creation = creation,openSettings=::openSharedSettings)
        }
        val notebook = intent.getStringExtra(NOTEBOOK)?.takeIf { it.isNotBlank() } ?: return null
        return WriterAttachment(store, notebook,openSettings=::openSharedSettings)
    }
    private fun openSharedSettings() {startActivity(android.content.Intent(this,SettingsQualificationActivity::class.java))}
    companion object { const val NOTEBOOK = "notebook"; const val CREATION = "creation" }
}
