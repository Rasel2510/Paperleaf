package com.rasel.paperleaf

import android.app.Application
import com.rasel.paperleaf.core.Library
import com.rasel.paperleaf.core.Prefs
import com.rasel.paperleaf.data.Db
import com.rasel.paperleaf.data.Repository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Holds the app-wide services; screens reach them through [App]. */
class PaperleafApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        App.prefs = Prefs(this)
        App.repo = Repository(Db(this))
        App.library = Library(this, App.repo)
    }
}

object App {
    lateinit var prefs: Prefs
    lateinit var repo: Repository
    lateinit var library: Library
}
