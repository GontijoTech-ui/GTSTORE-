package com.gtstore

import android.app.Application
import android.content.Context

class GTStoreApplication : Application() {

    val httpServer: HttpServer by lazy {
        HttpServer(applicationContext, 8080)
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
