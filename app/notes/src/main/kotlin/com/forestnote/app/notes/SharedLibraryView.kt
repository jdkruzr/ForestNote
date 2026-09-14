package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.forestnote.core.reader.BookSnapshot
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookCard
import com.forestnote.core.format.NotebookMeta
import kotlinx.coroutines.*
import java.util.UUID

/** Native, reusable overlay. Both shelves borrow the same store; this View owns no data. */
internal class SharedLibraryView(
    context:Context,private val store:NotebookStore,private val books:ReaderLibraryAccess,
    private val onOpenBook:(String,Boolean)->Unit,private val onImport:()->Unit,
    private val onClose:()->Unit,private val notebookCallbacks:LibraryView.Callbacks?=null,
    private val onBookChanged:(String,Boolean,String?)->Unit={_,_,_->},
    private val onOpenNotebook:((String)->Unit)?=null,
    private val onCreateNotebook:((String,String?)->Unit)?=null,
):LinearLayout(context) {
    private val state=books.libraryUi
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val notebooks=LibraryView()
    private val notebookHost=FrameLayout(context)
    private val bookHost=LinearLayout(context).apply {orientation=VERTICAL}
    private val rows=LinearLayout(context).apply {orientation=VERTICAL}
    private val scroll=ScrollView(context).apply {addView(rows)}
    private val status=TextView(context)
    private val query=EditText(context)
    private val shelfButtons=mutableMapOf<SharedLibraryState.Shelf,Button>()
    private val filter=button("") {}
    private val more=button(context.getString(R.string.shared_library_more)) {load(false)}
    private var fetch:Job?=null
    private var generation=0L
    private var after:String?=null
    private var scanned=0
    private var shown=0
    private var popup:PopupWindow?=null
    private var prompt:AlertDialog?=null
    private var loadingNotebookPrompt=false
    private var notebookPromptGeneration=0L
    private var restoreScroll=true
    private val dp get()=resources.displayMetrics.density
    private fun pixels(value:Int)=(value*dp).toInt()
    private fun frame()=EinkUiStyle.frame(context)
    private val controlHeight get()=resources.getDimensionPixelSize(R.dimen.eink_ui_control_height)
    private fun button(label:String,action:()->Unit)=Button(context).apply {
        text=label;isAllCaps=false;EinkUiStyle.text(this,R.dimen.eink_ui_label_text,medium=true);background=frame()
        minWidth=0;minimumWidth=0;minHeight=controlHeight;minimumHeight=0
        setPadding(pixels(10),pixels(3),pixels(10),pixels(3));setOnClickListener {action()}
    }
    init {
        orientation=VERTICAL;setBackgroundColor(Color.WHITE);isClickable=true;isFocusable=true
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        header.addView(TextView(context).apply {text=context.getString(R.string.shared_library_title);EinkUiStyle.text(this,R.dimen.eink_ui_title_text,medium=true);setPadding(pixels(8),0,pixels(12),0)})
        for(shelf in SharedLibraryState.Shelf.entries) {
            val label=context.getString(if(shelf==SharedLibraryState.Shelf.NOTEBOOKS) R.string.shared_library_notebooks else R.string.shared_library_books)
            val b=button(label) {select(shelf)}.apply {tag="shelf:$shelf"}
            shelfButtons[shelf]=b;header.addView(b)
        }
        header.addView(View(context),LayoutParams(0,1,1f))
        header.addView(button("×") {onClose()}.apply {contentDescription=context.getString(R.string.shared_library_close);tag="closeSharedLibrary"})
        addView(header,LayoutParams(-1,resources.getDimensionPixelSize(R.dimen.eink_ui_header_height)))
        addView(notebookHost,LayoutParams(-1,0,1f));addView(bookHost,LayoutParams(-1,0,1f))
        val actions=LinearLayout(context)
        actions.addView(button(context.getString(R.string.shared_library_import)) {onImport()})
        filter.tag="bookFilter";filter.setOnClickListener {menu(filter,listOf(
            context.getString(R.string.shared_library_books) to {state.trash=false;load(true)},
            context.getString(R.string.shared_library_trash) to {state.trash=true;load(true)}))}
        actions.addView(filter);actions.addView(more);bookHost.addView(actions)
        query.setSingleLine(true);EinkUiStyle.text(query,R.dimen.eink_ui_label_text);query.hint=context.getString(R.string.shared_library_search)
        query.filters=arrayOf(android.text.InputFilter.LengthFilter(256));query.tag="bookQuery";query.setText(state.query)
        bookHost.addView(query,LayoutParams(-1,pixels(44)))
        EinkUiStyle.text(status,R.dimen.eink_ui_meta_text);status.setPadding(pixels(8),pixels(3),pixels(8),pixels(3));status.tag="bookStatus"
        bookHost.addView(status);bookHost.addView(scroll,LayoutParams(-1,0,1f))
        query.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {state.query=s.toString();load(true,180)}
            override fun afterTextChanged(s:Editable?) {}
        })
        select(state.shelf);load(true,preserveScroll=true)
    }
    private fun select(shelf:SharedLibraryState.Shelf) {
        if(state.shelf!=shelf) dismissNotebookPrompt()
        state.shelf=shelf
        if(shelf==SharedLibraryState.Shelf.NOTEBOOKS) hideKeyboard()
        notebookHost.visibility=if(shelf==SharedLibraryState.Shelf.NOTEBOOKS) VISIBLE else GONE
        bookHost.visibility=if(shelf==SharedLibraryState.Shelf.BOOKS) VISIBLE else GONE
        shelfButtons.forEach {(key,b)->b.isSelected=key==shelf;b.setTypeface(null,if(key==shelf) 1 else 0)}
        if(shelf==SharedLibraryState.Shelf.BOOKS) restoreBookPosition(generation)
        if(shelf==SharedLibraryState.Shelf.NOTEBOOKS && !notebooks.isShowing) {
            fun notice() {Toast.makeText(context,if(onOpenNotebook==null) R.string.shared_library_writer_pending else R.string.shared_writer_management_pending,Toast.LENGTH_LONG).show()}
            val callbacks=notebookCallbacks ?: LibraryView.Callbacks(
                onOpenNotebook={card->onOpenNotebook?.invoke(card.id) ?: notice()},
                onNotebookProperties={if(onCreateNotebook!=null) notebookProperties(it) else notice()},
                onNewNotebook={if(onCreateNotebook!=null) newNotebook() else notice()},
                onNewFolder={if(onCreateNotebook!=null) newFolder() else notice()},
                onFolderProperties={if(onCreateNotebook!=null) folderProperties(it) else notice()},
                onOpenSettings={notice()},onOpenRecycleBin={notice()},onSyncNow={notice()},
                onOpenSearch={notice()},onBulkMove={notice()},onBulkExport={notice()},onBulkDelete={notice()})
            if(notebookCallbacks==null) {
                val content=LinearLayout(context).apply {orientation=VERTICAL}
                if(onCreateNotebook!=null) {
                    val actions=LinearLayout(context)
                    actions.addView(button(context.getString(R.string.shared_writer_new_notebook)) {newNotebook()}.apply {tag="newSharedNotebook"},LayoutParams(-2,controlHeight))
                    actions.addView(button(context.getString(R.string.library_new_folder)) {newFolder()}.apply {tag="newSharedFolder"},LayoutParams(-2,controlHeight))
                    content.addView(actions)
                }
                if(onOpenNotebook==null) content.addView(TextView(context).apply {text=context.getString(R.string.shared_library_writer_pending);EinkUiStyle.text(this,R.dimen.eink_ui_meta_text);setPadding(pixels(8),pixels(4),pixels(8),pixels(4))})
                val shelfHost=FrameLayout(context);content.addView(shelfHost,LayoutParams(-1,0,1f));notebookHost.addView(content)
                notebooks.show(shelfHost,store,callbacks,state.notebooks,readOnly=true)
            } else notebooks.show(notebookHost,store,callbacks,state.notebooks)
        }
    }
    fun changed() {load(true)}
    fun notebookChanged() {if(notebooks.isShowing) notebooks.reload()}
    private fun canPromptNotebook() = scope.isActive && isAttachedToWindow && visibility==VISIBLE &&
        state.shelf==SharedLibraryState.Shelf.NOTEBOOKS && !loadingNotebookPrompt && prompt==null
    private fun trackNotebookPrompt(dialog:AlertDialog) {
        prompt=dialog
        dialog.setOnDismissListener {if(prompt===dialog) prompt=null}
    }
    private fun dismissNotebookPrompt() {
        notebookPromptGeneration++;loadingNotebookPrompt=false
        prompt?.dismiss();prompt=null
    }
    private fun notebookMutationDone() {post {
        if(scope.isActive && isAttachedToWindow) notebooks.reload()
    }}
    private fun newFolder() {
        if(!canPromptNotebook()) return
        val parent=notebooks.currentFolderId
        trackNotebookPrompt(NotebookLibraryDialogs.newFolder(context) {name ->
            store.createFolder(name,parent) {id -> post {
                if(scope.isActive && isAttachedToWindow) {
                    if(id.isBlank()) Toast.makeText(context,R.string.library_folder_create_failed,Toast.LENGTH_LONG).show()
                    notebooks.reload()
                }
            }}
        })
    }
    private fun folderProperties(folder:FolderCard) {
        if(!canPromptNotebook()) return
        trackNotebookPrompt(NotebookLibraryDialogs.folder(context,folder,onSave={name ->
            if(name!=folder.name) store.renameFolder(folder.id,name) {notebookMutationDone()}
        }))
    }
    private fun notebookProperties(card:NotebookCard) {
        if(!canPromptNotebook()) return
        trackNotebookPrompt(NotebookLibraryDialogs.notebook(context,
            NotebookMeta(card.id,card.name,card.createdAt,card.modifiedAt),
            loadPages={store.countPages(card.id,it)},onSave={name ->
                if(name!=card.name) store.renameNotebook(card.id,name) {notebookMutationDone()}
            }))
    }
    private fun newNotebook() {
        if(loadingNotebookPrompt || prompt!=null || onCreateNotebook==null) return
        loadingNotebookPrompt=true
        val epoch=++notebookPromptGeneration
        val folder=notebooks.currentFolderId
        store.loadSettings {settings -> post {
            if(epoch!=notebookPromptGeneration) return@post
            loadingNotebookPrompt=false
            if(!scope.isActive || !isAttachedToWindow || visibility!=VISIBLE || state.shelf!=SharedLibraryState.Shelf.NOTEBOOKS || folder!=notebooks.currentFolderId) return@post
            trackNotebookPrompt(NewNotebookDialog.show(context,settings) {name -> onCreateNotebook.invoke(name,folder)})
        }}
    }
    private fun load(reset:Boolean,delayMillis:Long=0,preserveScroll:Boolean=false) {
        fetch?.cancel();val epoch=++generation
        if(reset) {after=null;scanned=0;shown=0;rows.removeAllViews();if(!preserveScroll) {restoreScroll=false;state.bookScroll=0;scroll.scrollTo(0,0)}}
        filter.text=context.getString(R.string.shared_library_filter,context.getString(if(state.trash) R.string.shared_library_trash else R.string.shared_library_books))
        more.visibility=GONE;more.setText(R.string.shared_library_more);status.setText(R.string.shared_library_loading)
        val search=state.query;val trash=state.trash
        fetch=scope.launch {
            try {
                delay(delayMillis)
                var added=0
                do {
                    val page=books.list(after,32,includeDeleted=trash)
                    ensureActive();if(epoch!=generation) return@launch
                    scanned+=page.books.size
                    val matches=withContext(Dispatchers.Default) {page.books.filter {SharedBookPresentation.matches(it,search,trash)}}
                    for(book in matches) {append(book);added++;shown++}
                    after=page.next
                    if(scanned>=4096 || shown>=256) {
                        status.text=context.getString(R.string.shared_library_limit,shown);more.visibility=GONE;return@launch
                    }
                    yield()
                } while(after!=null && added<(if(preserveScroll) state.bookRows.coerceIn(32,256) else 32))
                status.text=if(shown==0) context.getString(R.string.shared_library_empty) else resources.getQuantityString(R.plurals.shared_library_count,shown,shown)
                more.visibility=if(after!=null) VISIBLE else GONE
                restoreBookPosition(epoch)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {if(epoch==generation) {status.setText(R.string.shared_library_failed);more.text=context.getString(R.string.shared_library_retry);more.visibility=VISIBLE}}
        }
    }
    private fun restoreBookPosition(epoch:Long) {
        if(restoreScroll && shown>0 && bookHost.visibility==VISIBLE) scroll.post {
            if(epoch==generation && restoreScroll && bookHost.visibility==VISIBLE) {scroll.scrollTo(0,state.bookScroll);restoreScroll=false}
        }
    }
    private fun append(book:BookSnapshot) {
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;background=frame()}
        val open=button(SharedBookPresentation.title(book)) {onOpenBook(book.book.id,false)}.apply {
            gravity=Gravity.START or Gravity.CENTER_VERTICAL;maxLines=2;ellipsize=TextUtils.TruncateAt.END
            isEnabled=book.contentReady && !book.deleted;tag="book:${book.book.id}"
        }
        val details=LinearLayout(context).apply {orientation=VERTICAL}
        details.addView(open,LayoutParams(-1,-2))
        details.addView(TextView(context).apply {
            val format=if(book.book.mediaType=="application/epub+zip") "EPUB" else "MOBI"
            text=context.getString(if(book.deleted) R.string.shared_library_trashed else if(book.contentReady) R.string.shared_library_available else R.string.shared_library_pending,
                format,android.text.format.Formatter.formatShortFileSize(context,book.book.byteLength))
            EinkUiStyle.text(this,R.dimen.eink_ui_meta_text);setPadding(pixels(10),0,0,pixels(5))
        })
        row.addView(details,LayoutParams(0,-2,1f))
        val options=button("⋯") {}.apply {contentDescription=context.getString(R.string.shared_library_book_actions);tag="actions:${book.book.id}"}
        options.setOnClickListener {
            val choices=mutableListOf<Pair<String,()->Unit>>()
            if(book.deleted) choices+=context.getString(R.string.shared_library_restore) to {mutate {books.setDeleted(UUID.randomUUID().toString(),book.book.id,false)}}
            else {
                if(book.contentReady) choices+=context.getString(R.string.shared_library_annotations) to {onOpenBook(book.book.id,true)}
                choices+=context.getString(R.string.shared_library_rename) to {rename(book)}
                choices+=context.getString(R.string.shared_library_move_trash) to {
                    prompt=AlertDialog.Builder(context).setTitle(R.string.shared_library_move_trash).setMessage(SharedBookPresentation.title(book))
                        .setNegativeButton(R.string.shared_library_cancel,null).setPositiveButton(R.string.shared_library_move_trash) {_,_->
                            mutate {books.setDeleted(UUID.randomUUID().toString(),book.book.id,true);onBookChanged(book.book.id,true,null)}
                        }.show()
                }
            }
            menu(options,choices)
        }
        row.addView(options,LayoutParams(pixels(44),controlHeight));rows.addView(row,LayoutParams(-1,-2))
    }
    private fun menu(anchor:View,choices:List<Pair<String,()->Unit>>) {
        popup?.dismiss();val content=LinearLayout(context).apply {orientation=VERTICAL}
        val window=PopupWindow(content,pixels(240),-2,true).apply {setBackgroundDrawable(frame());isOutsideTouchable=true;elevation=0f}
        for((label,action) in choices) content.addView(button(label) {window.dismiss();action()},LayoutParams(-1,-2))
        popup=window;window.showAsDropDown(anchor,0,0,Gravity.END)
    }
    private fun rename(book:BookSnapshot) {
        val input=EditText(context).apply {setSingleLine(true);setText(SharedBookPresentation.title(book));filters=arrayOf(android.text.InputFilter.LengthFilter(4096))}
        val dialog=AlertDialog.Builder(context).setTitle(R.string.shared_library_rename).setView(input)
            .setNegativeButton(R.string.shared_library_cancel,null).setPositiveButton(R.string.shared_library_apply,null).create()
        prompt=dialog;dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title=input.text.toString().trim();if(title.isEmpty()) {input.error=context.getString(R.string.shared_library_name_required);return@setOnClickListener}
            dialog.dismiss();mutate {books.rename(UUID.randomUUID().toString(),book.book.id,title);onBookChanged(book.book.id,false,title)}
        }};dialog.show()
    }
    private fun mutate(action:suspend ()->Unit) {scope.launch {
        try {action();load(true)} catch(e:CancellationException) {throw e}
        catch(_:Exception) {status.setText(R.string.shared_library_failed)}
    }}
    fun remember() {
        dismissNotebookPrompt()
        hideKeyboard()
        if(!restoreScroll) {state.bookScroll=scroll.scrollY;state.bookRows=shown}
        if(notebooks.isShowing) state.notebooks=notebooks.browsePosition()
        popup?.dismiss();prompt?.dismiss()
    }
    private fun hideKeyboard() {
        query.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken,0)
    }
    fun close() {remember();generation++;scope.cancel();notebooks.hide()}
}
