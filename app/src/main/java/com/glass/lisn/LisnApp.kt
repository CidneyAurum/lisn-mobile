package com.glass.lisn

import android.app.Application

class LisnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EngineHub.init(this)
    }
}
