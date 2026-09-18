package com.nowly.app

import android.app.Application

open class NowlyApplication : Application() {
    open val container: AppContainer by lazy { AppContainer(this) }
}
