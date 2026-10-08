package com.gtstore

import android.app.Application
import android.content.Context

class GTStoreApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
