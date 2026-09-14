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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val densityPreference=DeviceUiDensityPreference(context)
    private var densityMode=UiDensity.AUTO
    private var densityChoiceVersion=0
    private var compact=false
    private var bookView=LibraryShelfView.LIST
    private var bookViewChoiceVersion=0
    private var notebookView=LibraryShelfView.TILES
    private var notebookViewChoiceVersion=0
    private val notebooks=LibraryView()
    private val notebookPanel=LinearLayout(context).apply {orientation=VERTICAL}
    private val notebookHost=FrameLayout(context)
    private val bookHost=LinearLayout(context).apply {orientation=VERTICAL}
    private val covers=BookCoverLoader(context.cacheDir,books::coverBytes)
    private val bookLayout=GridLayoutManager(context,1)
    private val bookAdapter=BookShelfAdapter({bookRow(it)},{view -> view.findViewById<ImageView>(R.id.book_cover_thumbnail)?.let(covers::cancel)})
    private val rows=RecyclerView(context).apply {layoutManager=bookLayout;adapter=bookAdapter;itemAnimator=null}
    private val status=TextView(context)
    private val query=EditText(context)
    private val notebookQuery=EditText(context)
    private val chrome=LibraryChromeView(context,state.shelf,onCreateNotebook!=null && notebookCallbacks==null,
        {select(it)},onClose,{newNotebook()},{newFolder()},{densityMenu()})
    private val filter=button("") {}
    private val viewChoice=button("") {bookViewMenu()}.apply {tag="bookView";contentDescription=context.getString(R.string.library_book_view)}
    private val notebookViewChoice=button("") {notebookViewMenu()}.apply {tag="notebookView";contentDescription=context.getString(R.string.library_notebook_view)}
    private val importButton=button(context.getString(R.string.shared_library_import)) {onImport()}
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
    private fun frame()=LibrarySurfaceStyle.surface(context)
    private val inset get()=LibrarySurfaceStyle.px(context,if(compact) R.dimen.library_compact_inset else R.dimen.library_surface_inset)
    private val gap get()=LibrarySurfaceStyle.px(context,if(compact) R.dimen.library_compact_gap else R.dimen.library_surface_gap)
    private val controlHeight get()=resources.getDimensionPixelSize(R.dimen.eink_ui_control_height)
    private fun button(label:String,action:()->Unit)=Button(context).apply {
        text=label;LibrarySurfaceStyle.action(this,compact=compact);setOnClickListener {action()}
    }
    init {
        orientation=VERTICAL;setBackgroundColor(Color.WHITE);isClickable=true;isFocusable=true
        addView(chrome,LayoutParams(-1,-2))
        addView(notebookPanel,LayoutParams(-1,0,1f));addView(bookHost,LayoutParams(-1,0,1f))
        bookHost.setPadding(inset,0,inset,0)
        val actions=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        actions.addView(importButton.apply {
            LibrarySurfaceStyle.action(this,primary=true,icon=R.drawable.ic_add)
        },LayoutParams(-2,-2).apply {marginEnd=gap})
        filter.tag="bookFilter";filter.setOnClickListener {menu(filter,listOf(
            context.getString(R.string.shared_library_books) to {state.trash=false;load(true)},
            context.getString(R.string.shared_library_trash) to {state.trash=true;load(true)}),R.string.library_filter_menu_heading)}
        actions.addView(filter)
        updateBookViewLabel()
        bookHost.addView(HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;addView(actions)},LayoutParams(-1,-2))
        configureQuery(query,"bookQuery",R.string.shared_library_search,state.query)
        bookHost.addView(searchRow(query,viewChoice),LayoutParams(-1,-2))
        configureQuery(notebookQuery,"notebookQuery",R.string.library_search_folder_names,state.notebookQuery)
        notebookPanel.addView(searchRow(notebookQuery,notebookViewChoice).apply {
            setPadding(inset,0,inset,0)
        },LayoutParams(-1,-2))
        notebookPanel.addView(notebookHost,LayoutParams(-1,0,1f))
        notebooks.setShelfView(notebookView);notebooks.setNameQuery(state.notebookQuery)
        updateNotebookViewLabel()
        notebookQuery.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                state.notebookQuery=s.toString();notebooks.setNameQuery(state.notebookQuery)
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        EinkUiStyle.text(status,R.dimen.eink_ui_meta_text);status.setPadding(pixels(8),pixels(3),pixels(8),pixels(3));status.tag="bookStatus"
        bookHost.addView(status);bookHost.addView(rows,LayoutParams(-1,0,1f))
        // Pagination belongs with the results, not beside Import and filtering.
        bookHost.addView(more,LayoutParams(-1,-2).apply {topMargin=gap/2;bottomMargin=gap/2})
        query.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {state.query=s.toString();load(true,180)}
            override fun afterTextChanged(s:Editable?) {}
        })
        select(state.shelf);load(true,preserveScroll=true)
        val version=densityChoiceVersion
        densityPreference.load {mode ->
            if(scope.isActive && version==densityChoiceVersion) {densityMode=mode;applyDensity()}
        }
        val viewVersion=bookViewChoiceVersion
        densityPreference.loadBookView {mode ->
            if(scope.isActive && viewVersion==bookViewChoiceVersion) {bookView=mode;updateBookViewLabel();relayoutBooks()}
        }
        val notebookVersion=notebookViewChoiceVersion
        densityPreference.loadNotebookView {mode ->
            if(scope.isActive && notebookVersion==notebookViewChoiceVersion) {
                notebookView=mode;updateNotebookViewLabel();notebooks.setShelfView(mode)
            }
        }
    }
    private fun configureQuery(field:EditText,fieldTag:String,hint:Int,value:String) {
        field.setSingleLine(true);field.hint=context.getString(hint);field.contentDescription=context.getString(hint)
        field.filters=arrayOf(android.text.InputFilter.LengthFilter(256));field.tag=fieldTag;field.setText(value)
        LibrarySurfaceStyle.input(field,search=true);field.minimumHeight=controlHeight
    }
    private fun searchRow(field:EditText,choice:Button)=LinearLayout(context).apply {
        gravity=Gravity.CENTER_VERTICAL
        // Equal vertical margins: the menu button and search field share an exact centerline.
        addView(field,LayoutParams(0,-2,1f).apply {topMargin=gap;bottomMargin=gap;marginEnd=gap})
        addView(choice,LayoutParams(-2,-2))
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w!=oldw) post {if(scope.isActive) {applyDensity();relayoutBooks()}}
    }

    private fun bookViewMenu() {
        shelfViewMenu(viewChoice,R.string.library_book_view_heading,bookView) {mode ->
            bookViewChoiceVersion++;bookView=mode;densityPreference.saveBookView(mode);updateBookViewLabel();relayoutBooks()
        }
    }
    private fun notebookViewMenu() {
        shelfViewMenu(notebookViewChoice,R.string.library_notebook_view_heading,notebookView) {mode ->
            notebookViewChoiceVersion++;notebookView=mode;densityPreference.saveNotebookView(mode)
            updateNotebookViewLabel();notebooks.setShelfView(mode)
        }
    }
    private fun shelfViewMenu(anchor:View,heading:Int,current:LibraryShelfView,choose:(LibraryShelfView)->Unit) {
        fun label(mode:LibraryShelfView)=context.getString(if(mode==LibraryShelfView.LIST) R.string.library_book_list else R.string.library_book_tiles)
        menu(anchor,LibraryShelfView.entries.map {mode -> label(mode) to {choose(mode)}},heading,label(current))
    }
    private fun updateNotebookViewLabel() {
        notebookViewChoice.text=context.getString(R.string.shared_library_filter,context.getString(
            if(notebookView==LibraryShelfView.LIST) R.string.library_book_list else R.string.library_book_tiles))
    }
    private fun updateBookViewLabel() {
        viewChoice.text=context.getString(R.string.shared_library_filter,context.getString(
            if(bookView==LibraryShelfView.LIST) R.string.library_book_list else R.string.library_book_tiles))
    }
    private fun relayoutBooks() {
        if(width<=0) return
        val first=bookLayout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val offset=bookLayout.findViewByPosition(first)?.top ?: 0
        val columns=LibraryDensityPolicy.shelf(densityMode,
            (width-2*inset)/dp,resources.configuration.fontScale,bookView).columns
        bookLayout.spanCount=columns
        bookAdapter.restyle()
        bookLayout.scrollToPositionWithOffset(first,offset)
    }

    private fun densityMenu() {
        menu(chrome,UiDensity.entries.map {mode -> context.getString(LibraryChromeView.densityLabel(mode)) to {
            densityChoiceVersion++;densityMode=mode;densityPreference.save(mode);applyDensity()
        }},R.string.library_density_heading,context.getString(LibraryChromeView.densityLabel(densityMode)))
    }

    private fun applyDensity() {
        chrome.densityMode=densityMode
        notebooks.setDensityMode(densityMode)
        if(width<=0) return
        val next=LibraryDensityPolicy.resolve(densityMode,width/dp,resources.configuration.fontScale).compact
        chrome.compact=next
        if(next==compact) return
        compact=next
        bookHost.setPadding(inset,0,inset,0)
        LibrarySurfaceStyle.action(importButton,primary=true,icon=R.drawable.ic_add,compact=compact)
        LibrarySurfaceStyle.action(filter,compact=compact);LibrarySurfaceStyle.action(more,compact=compact)
        LibrarySurfaceStyle.action(viewChoice,compact=compact)
        LibrarySurfaceStyle.action(notebookViewChoice,compact=compact)
        (notebookQuery.parent as View).setPadding(inset,0,inset,0)
        for(field in listOf(query,notebookQuery)) {
            LibrarySurfaceStyle.input(field,search=true,compact=compact)
            (field.layoutParams as LayoutParams).apply {topMargin=gap;bottomMargin=gap;marginEnd=gap}
            field.requestLayout()
        }
        EinkUiStyle.text(status,if(compact) R.dimen.library_compact_meta_text else R.dimen.eink_ui_meta_text)
        // Resize already-loaded presentation only: no book query, renderer reload or sync.
        relayoutBooks()
    }
    private fun select(shelf:SharedLibraryState.Shelf) {
        if(state.shelf!=shelf) {dismissNotebookPrompt();hideKeyboard()}
        state.shelf=shelf
        if(shelf==SharedLibraryState.Shelf.NOTEBOOKS) hideKeyboard()
        notebookPanel.visibility=if(shelf==SharedLibraryState.Shelf.NOTEBOOKS) VISIBLE else GONE
        bookHost.visibility=if(shelf==SharedLibraryState.Shelf.BOOKS) VISIBLE else GONE
        if(shelf==SharedLibraryState.Shelf.NOTEBOOKS) covers.pause() else bookAdapter.restyle()
        chrome.shelf=shelf
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
                if(onOpenNotebook==null) content.addView(TextView(context).apply {text=context.getString(R.string.shared_library_writer_pending);EinkUiStyle.text(this,R.dimen.eink_ui_meta_text);setPadding(pixels(8),pixels(4),pixels(8),pixels(4))})
                val shelfHost=FrameLayout(context);content.addView(shelfHost,LayoutParams(-1,0,1f));notebookHost.addView(content)
                notebooks.show(shelfHost,store,callbacks,state.notebooks,readOnly=true,sharedSurfaces=true)
            } else notebooks.show(notebookHost,store,callbacks,state.notebooks,sharedSurfaces=true)
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
        if(reset) {after=null;scanned=0;shown=0;bookAdapter.clear();if(!preserveScroll) {restoreScroll=false;state.bookScroll=0;state.bookItem=0;bookLayout.scrollToPositionWithOffset(0,0)}}
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
                    for(book in matches) {bookAdapter.append(book);added++;shown++}
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
        if(restoreScroll && shown>0 && bookHost.visibility==VISIBLE) rows.post {
            if(epoch==generation && restoreScroll && bookHost.visibility==VISIBLE) {bookLayout.scrollToPositionWithOffset(state.bookItem,state.bookScroll);restoreScroll=false}
        }
    }
    private fun bookRow(book:BookSnapshot):View {
        val tiles=bookView==LibraryShelfView.TILES
        val row=LinearLayout(context).apply {
            orientation=if(tiles) VERTICAL else HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL;background=frame();setPadding(inset,inset,inset,inset)
            layoutParams=FrameLayout.LayoutParams(-1,-2).apply {
                setMargins(if(tiles) gap/2 else 0,gap/2,if(tiles) gap/2 else 0,gap/2)
            }
        }
        val artwork=ImageView(context).apply {
            setImageResource(R.drawable.ic_notebook);setColorFilter(Color.BLACK)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType=ImageView.ScaleType.CENTER_INSIDE
        }
        if(tiles) {
            artwork.id=R.id.book_cover_thumbnail
            artwork.clearColorFilter()
            artwork.isEnabled=book.contentReady && !book.deleted
            artwork.setOnClickListener {if(artwork.isEnabled) onOpenBook(book.book.id,false)}
            row.addView(artwork,LayoutParams(-1,pixels(if(compact) 128 else 200)))
            if(book.contentReady) covers.load(book.book.id,artwork)
        } else row.addView(artwork,LayoutParams(pixels(28),pixels(36)).apply {marginEnd=inset})
        val open=button(SharedBookPresentation.title(book)) {onOpenBook(book.book.id,false)}.apply {
            gravity=Gravity.START or Gravity.CENTER_VERTICAL;maxLines=3;ellipsize=TextUtils.TruncateAt.END
            isEnabled=book.contentReady && !book.deleted;tag="book:${book.book.id}"
            LibrarySurfaceStyle.action(this,outlined=false,compact=compact)
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0,0,0,0)
            contentDescription=SharedBookPresentation.title(book)
        }
        val details=LinearLayout(context).apply {orientation=VERTICAL}
        val metadata=TextView(context).apply {
            val format=if(book.book.mediaType=="application/epub+zip") "EPUB" else "MOBI"
            text=context.getString(if(book.deleted) R.string.shared_library_trashed else if(book.contentReady) R.string.shared_library_available else R.string.shared_library_pending,
                format,android.text.format.Formatter.formatShortFileSize(context,book.book.byteLength))
            EinkUiStyle.text(this,if(compact) R.dimen.library_compact_meta_text else R.dimen.eink_ui_meta_text);setPadding(0,0,0,pixels(3))
        }
        if(tiles) row.addView(open,LayoutParams(-1,-2))
        else {details.addView(open,LayoutParams(-1,-2));details.addView(metadata);row.addView(details,LayoutParams(0,-2,1f))}
        val options=button("⋯") {}.apply {contentDescription=context.getString(R.string.library_options_for,SharedBookPresentation.title(book));tag="actions:${book.book.id}"}
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
                        }.show().also {NotebookLibraryDialogs.style(it)}
                }
            }
            menu(options,choices)
        }
        if(tiles) {
            val footer=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
            footer.addView(metadata,LayoutParams(0,-2,1f));footer.addView(options,LayoutParams(pixels(44),controlHeight).apply {marginStart=gap})
            row.addView(footer,LayoutParams(-1,-2))
        } else row.addView(options,LayoutParams(pixels(44),controlHeight).apply {marginStart=gap})
        return row
    }
    private fun menu(anchor:View,choices:List<Pair<String,()->Unit>>,heading:Int=R.string.library_book_menu_heading,selectedLabel:String?=null) {
        popup?.dismiss();val content=LinearLayout(context).apply {orientation=VERTICAL;setPadding(gap,gap,gap,gap)}
        val panel=ScrollView(context).apply {addView(content)}
        val window=PopupWindow(panel,pixels(320).coerceAtMost(width-inset*2),-2,true).apply {
            setBackgroundDrawable(frame());isOutsideTouchable=true;elevation=0f;inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        content.addView(TextView(context).apply {
            setText(heading);EinkUiStyle.text(this,R.dimen.eink_ui_meta_text,medium=true)
            setPadding(gap,0,gap,gap/2)
        })
        content.addView(View(context).apply {setBackgroundColor(Color.BLACK)},LayoutParams(-1,EinkUiStyle.borderPixels(context)))
        for((label,action) in choices) content.addView(button(label) {window.dismiss();action()}.apply {
            LibrarySurfaceStyle.action(this,outlined=false,compact=compact,
                icon=if(label==selectedLabel) R.drawable.ic_check_circle else null)
            isSelected=label==selectedLabel
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
        },LayoutParams(-1,-2))
        popup=window;window.showAsDropDown(anchor,0,0,Gravity.END)
    }
    private fun rename(book:BookSnapshot) {
        val input=EditText(context).apply {setSingleLine(true);setText(SharedBookPresentation.title(book));filters=arrayOf(android.text.InputFilter.LengthFilter(4096));LibrarySurfaceStyle.input(this)}
        val dialog=AlertDialog.Builder(context).setTitle(R.string.shared_library_rename).setView(input)
            .setNegativeButton(R.string.shared_library_cancel,null).setPositiveButton(R.string.shared_library_apply,null).create()
        prompt=dialog;dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title=input.text.toString().trim();if(title.isEmpty()) {input.error=context.getString(R.string.shared_library_name_required);return@setOnClickListener}
            dialog.dismiss();mutate {books.rename(UUID.randomUUID().toString(),book.book.id,title);onBookChanged(book.book.id,false,title)}
        }};dialog.show();NotebookLibraryDialogs.style(dialog)
    }
    private fun mutate(action:suspend ()->Unit) {scope.launch {
        try {action();load(true)} catch(e:CancellationException) {throw e}
        catch(_:Exception) {status.setText(R.string.shared_library_failed)}
    }}
    fun remember() {
        dismissNotebookPrompt()
        hideKeyboard()
        if(!restoreScroll) {
            state.bookItem=bookLayout.findFirstVisibleItemPosition().coerceAtLeast(0)
            state.bookScroll=bookLayout.findViewByPosition(state.bookItem)?.top ?: 0;state.bookRows=shown
        }
        if(notebooks.isShowing) state.notebooks=notebooks.browsePosition()
        popup?.dismiss();prompt?.dismiss()
    }
    private fun hideKeyboard() {
        query.clearFocus()
        notebookQuery.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken,0)
    }
    fun close() {remember();generation++;scope.cancel();covers.close();notebooks.hide()}
}
