package com.nowly.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import java.util.UUID

class NowlyTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, IsolatedTestApplication::class.java.name, context)
}

class IsolatedTestApplication : NowlyApplication() {
    override val container: AppContainer by lazy { AppContainer(this, "nowly-test-${UUID.randomUUID()}.db") }
}
