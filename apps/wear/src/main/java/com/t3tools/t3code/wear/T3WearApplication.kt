package com.t3tools.t3code.wear

import android.app.Application

class T3WearApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
