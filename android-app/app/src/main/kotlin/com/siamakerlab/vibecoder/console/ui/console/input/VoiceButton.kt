package com.siamakerlab.vibecoder.console.ui.console.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.siamakerlab.vibecoder.console.R

/**
 * Microphone IconButton with an embedded SpeechRecognizer.
 *
 * Behavior:
 *  - Tap once with no permission → request RECORD_AUDIO; on grant, start listening.
 *  - Tap while listening → stop and emit final results to [onResult].
 *  - Partial results stream via [onPartial] (caller can preview-mutate the input field).
 *  - On error or denial, the button silently reverts to idle — keyboard input still works.
 */
@Composable
fun VoiceButton(
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var listening by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    val recognizer = remember(context) {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    DisposableEffect(recognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
            }
            override fun onResults(results: Bundle?) {
                val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                listening = false
                if (!txt.isNullOrBlank()) onResult(txt)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val txt = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!txt.isNullOrBlank()) onPartial(txt)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer?.setRecognitionListener(listener)
        onDispose { recognizer?.destroy() }
    }

    LaunchedEffect(hasPermission, listening) {
        if (hasPermission && listening && recognizer != null) {
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            runCatching { recognizer.startListening(intent) }
                .onFailure { listening = false }
        }
    }

    val tint = when {
        listening -> MaterialTheme.colorScheme.primary
        permissionDenied -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = {
            if (!recognizerAvailable) return@IconButton
            if (!hasPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@IconButton
            }
            if (listening) {
                recognizer?.stopListening()
                listening = false
            } else {
                listening = true
            }
        },
        enabled = recognizerAvailable,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (permissionDenied || !recognizerAvailable) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = if (listening) stringResource(R.string.console_voice_stop)
                else stringResource(R.string.console_voice_start),
            tint = tint,
        )
    }
}
