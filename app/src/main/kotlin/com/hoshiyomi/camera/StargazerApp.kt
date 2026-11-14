package com.hoshiyomi.camera

import android.app.Application
import com.hoshiyomi.camera.native.NativeCore

class StargazerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The single native dependency Stargazer ships. No per-vendor blobs.
        NativeCore.ensureLoaded()
    }
}
