/***************************************************************************************
 * Copyright (c) 2022 Ankitects Pty Ltd <https://apps.ankiweb.net>                       *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.coroutineScope
import anki.collection.Progress
import com.ichi2.anki.UIUtils.showSimpleSnackbar
import com.ichi2.libanki.Collection
import com.ichi2.themes.StyledProgressDialog
import kotlinx.coroutines.*
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.BackendException
import net.ankiweb.rsdroid.exceptions.BackendInterruptedException
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Launch a job that catches any uncaught errors and reports them to the user.
 * Errors from the backend contain localized text that is often suitable to show to the user as-is.
 * Other errors should ideally be handled in the block.
 */
fun FragmentActivity.launchCatchingTask(
    errorMessage: String? = null,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val extraInfo = errorMessage ?: ""
    return lifecycle.coroutineScope.launch {
        try {
            block()
        } catch (exc: CancellationException) {
            // do nothing
        } catch (exc: BackendInterruptedException) {
            Timber.e("caught: %s %s", exc, extraInfo)
            showSimpleSnackbar(this@launchCatchingTask, exc.localizedMessage, false)
        } catch (exc: BackendException) {
            Timber.e("caught: %s %s", exc, extraInfo)
            showError(this@launchCatchingTask, exc.localizedMessage!!)
        } catch (exc: Exception) {
            Timber.e("caught: %s %s", exc, extraInfo)
            showError(this@launchCatchingTask, exc.toString())
        }
    }
}

/** See [FragmentActivity.launchCatchingTask] */
fun Fragment.launchCatchingTask(
    errorMessage: String? = null,
    block: suspend CoroutineScope.() -> Unit
): Job = requireActivity().launchCatchingTask(errorMessage, block)

private fun showError(context: Context, msg: String) {
    AlertDialog.Builder(context)
        .setTitle(R.string.vague_error)
        .setMessage(msg)
        .setPositiveButton(R.string.dialog_ok) { _, _ -> }
        .show()
}

/** In most cases, you'll want [AnkiActivity.withProgress]
 * instead. This lower-level routine can be used to integrate your own
 * progress UI.
 */
suspend fun <T> Backend.withProgress(
    extractProgress: ProgressContext.() -> Unit,
    updateUi: ProgressContext.() -> Unit,
    block: suspend CoroutineScope.() -> T,
): T {
    return coroutineScope {
        val monitor = launch {
            monitorProgress(this@withProgress, extractProgress, updateUi)
        }
        try {
            block()
        } finally {
            monitor.cancel()
        }
    }
}

/**
 * Run the provided operation, showing a progress window until it completes.
 * Progress info is polled from the backend.
 */
suspend fun <T> FragmentActivity.withProgress(
    extractProgress: ProgressContext.() -> Unit,
    onCancel: ((Backend) -> Unit)? = { it.setWantsAbort() },
    op: suspend () -> T
): T {
    val backend = CollectionManager.getBackend()
    return withProgressDialog(
        context = this@withProgress,
        onCancel = if (onCancel != null) {
            fun() { onCancel(backend) }
        } else {
            null
        }
    ) { dialog ->
        backend.withProgress(
            extractProgress = extractProgress,
            updateUi = { updateDialog(dialog) }
        ) {
            op()
        }
    }
}

/**
 * Run the provided operation, showing a progress window with the provided
 * message until the operation completes.
 */
suspend fun <T> FragmentActivity.withProgress(
    message: String = resources.getString(R.string.dialog_processing),
    op: suspend () -> T
): T = withProgressDialog(
    context = this@withProgress,
    onCancel = null
) { dialog ->
    @Suppress("Deprecation") // ProgressDialog deprecation
    dialog.setMessage(message)
    op()
}

private suspend fun <T> withProgressDialog(
    context: FragmentActivity,
    onCancel: (() -> Unit)?,
    @Suppress("Deprecation") // ProgressDialog deprecation
    op: suspend (android.app.ProgressDialog) -> T
): T {
    val dialog = StyledProgressDialog.show(
        context, null,
        null, onCancel != null
    )
    onCancel?.let {
        dialog.setOnCancelListener { it() }
    }
    return try {
        op(dialog)
    } finally {
        dialog.dismiss()
    }
}

/**
 * Poll the backend for progress info every 100ms until cancelled by caller.
 * Calls extractProgress() to gather progress info and write it into
 * [ProgressContext]. Calls updateUi() to update the UI with the extracted
 * progress.
 */
private suspend fun monitorProgress(
    backend: Backend,
    extractProgress: ProgressContext.() -> Unit,
    updateUi: ProgressContext.() -> Unit,
) {
    var state = ProgressContext(Progress.getDefaultInstance())
    while (true) {
        state.progress = backend.latestProgress()
        state.extractProgress()
        // on main thread, so op can update UI
        withContext(Dispatchers.Main) {
            state.updateUi()
        }
        delay(100)
    }
}

/** Holds the current backend progress, and text/amount properties
 * that can be written to in order to update the UI.
 */
data class ProgressContext(
    var progress: Progress,
    var text: String = "",
    /** If set, shows progress bar with a of b complete. */
    var amount: Pair<Int, Int>? = null,
)

@Suppress("Deprecation") // ProgressDialog deprecation
private fun ProgressContext.updateDialog(dialog: android.app.ProgressDialog) {
    // ideally this would show a progress bar, but MaterialDialog does not support
    // setting progress after starting with indeterminate progress, so we just use
    // this for now
    // this code has since been updated to ProgressDialog, and the above not rechecked
    val progressText = amount?.let {
        " ${it.first}/${it.second}"
    } ?: ""
    @Suppress("Deprecation") // ProgressDialog deprecation
    dialog.setMessage(text + progressText)
}

/**
 * If a full sync is not already required, confirm the user wishes to proceed.
 * If the user agrees, the schema is bumped and the routine will return true.
 * On false, calling routine should abort.
 */
suspend fun AnkiActivity.userAcceptsSchemaChange(col: Collection): Boolean {
    if (col.schemaChanged()) {
        return true
    }
    return suspendCoroutine { coroutine ->
        AlertDialog.Builder(this)
            // generic message
            .setMessage(col.tr.deckConfigWillRequireFullSync())
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                col.modSchemaNoCheck()
                coroutine.resume(true)
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                coroutine.resume(false)
            }
            .show()
    }
}
