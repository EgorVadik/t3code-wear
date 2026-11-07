package com.t3tools.t3code.wear.ui

import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.input.RemoteInputIntentHelper

private const val RESULT_KEY = "t3_text"

/**
 * Returns a function that asks for spoken text and hands it to [onText]. It opens the speech
 * recognizer when the watch has one, and otherwise the system input screen, which offers voice
 * alongside the keyboard. A cancelled or empty result calls nothing.
 */
@Composable
fun rememberVoiceInput(prompt: String, onText: (String) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onText)
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(latest)
    }
    val systemInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.let(RemoteInput::getResultsFromIntent)?.getCharSequence(RESULT_KEY)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(latest)
    }
    return {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt),
            )
        } catch (_: ActivityNotFoundException) {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(RESULT_KEY).setLabel(prompt).build()))
            systemInput.launch(intent)
        }
    }
}
