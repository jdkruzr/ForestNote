package com.forestnote.app.notes

/** Logical UI width only. Never changes a page, ink coordinates, or the toolbar height. */
internal object WriterToolbarPolicy {
    enum class Control { LIBRARY, PREVIOUS, PAGES, NEXT, UNDO, REDO, VIEWPORT, PEN, LASSO, TEXT, ERASER, PASTE, MORE }
    data class Plan(val widths:Map<Control,Int>,val labels:Set<Control>)
    fun arrange(width:Int,active:Control?=null,fontScale:Float=1f):Plan {
        val widths=linkedMapOf<Control,Int>()
        fun add(group:List<Control>) {
            val missing=group.filter {it !in widths}
            val cost=missing.map {if(it==Control.PAGES) 60 else 32}.sum()+missing.size*4
            if(widths.values.sum()+widths.size*4+cost<=width)
                missing.forEach {widths[it]=if(it==Control.PAGES) 60 else 32}
        }
        add(listOf(Control.LIBRARY,Control.PEN,Control.ERASER,Control.MORE))
        if(active==Control.TEXT || active==Control.LASSO) add(listOf(active))
        add(listOf(Control.UNDO));add(listOf(Control.PAGES))
        add(listOf(Control.PREVIOUS,Control.NEXT));add(listOf(Control.REDO))
        add(listOf(Control.LASSO));add(listOf(Control.TEXT));add(listOf(Control.PASTE));add(listOf(Control.VIEWPORT))
        val labels=mutableSetOf<Control>()
        if(fontScale<=1.3f) for((control,target) in listOf(Control.PEN to 146,Control.ERASER to 112,
                Control.LASSO to 80,Control.TEXT to 76,Control.PASTE to 84)) {
            val old=widths[control] ?: continue
            val expanded=(target*fontScale).toInt()
            if(widths.values.sum()+widths.size*4+expanded-old<=width) {widths[control]=expanded;labels.add(control)}
        }
        return Plan(widths,labels)
    }
}
