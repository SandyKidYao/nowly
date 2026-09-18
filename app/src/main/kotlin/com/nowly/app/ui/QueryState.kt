package com.nowly.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Composable
fun <T> queryState(flow: Flow<T>, retainPrevious: Boolean = false): State<Result<T>?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<Result<T>?>(null, flow, lifecycle) {
        if (!retainPrevious) value = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { flow.collect { value = Result.success(it) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { value = Result.failure(error) }
        }
    }
}
