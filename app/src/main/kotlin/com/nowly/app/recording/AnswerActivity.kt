package com.nowly.app.recording

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nowly.app.NowlyApplication
import com.nowly.app.platform.reminders.AndroidReminderGateway
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.theme.NowlyTheme

class AnswerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(AndroidReminderGateway.EXTRA_OCCURRENCE)
        if (id == null) { finish(); return }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val container = (application as NowlyApplication).container
        setContent { NowlyTheme { val vm = viewModel { MvpViewModel(container) }; AnswerRoute(id, vm, ::finish) } }
    }
}
