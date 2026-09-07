package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import java.util.Locale

/**
 * [T-usage-actions] Thin system-TTS wrapper scoped to the chat screen.
 *
 * Holds a single [TextToSpeech] instance and tracks which assistant message
 * is currently being read aloud, so one tap on a 📣 button stops the previous
 * reply and starts the new one (mirroring iOS chat read-aloud UX). The screen
 * calls [shutdown] on dispose to release the engine.
 *
 * Long replies are split into bounded chunks and queued so they read
 * continuously (Android's TTS rejects over-long single utterances).
 */
class MessageTtsController(context: Context) {
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.CHINESE
        }
    }
    private val _speakingMessageId = mutableStateOf<String?>(null)
    val speakingMessageId: State<String?> = _speakingMessageId

    /** Start reading [messageId]; if it is already playing, stop it instead. */
    fun toggle(messageId: String, text: String) {
        if (_speakingMessageId.value == messageId) {
            stop()
            return
        }
        stopInternal()
        if (text.isBlank()) return
        speakChunked(messageId, text)
        _speakingMessageId.value = messageId
    }

    fun stop() {
        stopInternal()
        _speakingMessageId.value = null
    }

    fun shutdown() {
        _speakingMessageId.value = null
        tts.stop()
        tts.shutdown()
    }

    private fun stopInternal() {
        tts.stop()
    }

    private fun speakChunked(messageId: String, text: String) {
        val chunks = chunkText(text)
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, "msg-$messageId-$i")
        }
    }

    private fun chunkText(text: String, max: Int = 1500): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + max, text.length)
            // Prefer breaking on a newline / sentence boundary near the cap.
            var cut = end
            if (end < text.length) {
                val window = text.substring(start, end)
                val nl = window.lastIndexOf('\n')
                val dot = window.lastIndexOf('。')
                val breakAt = maxOf(nl, dot)
                if (breakAt > max / 2) cut = start + breakAt + 1
            }
            out.add(text.substring(start, cut).trim())
            start = cut
        }
        return out.filter { it.isNotEmpty() }
    }
}

/**
 * [T-usage-actions] Renders the two per-assistant-message action buttons
 * ("Copy whole reply" / "Read aloud") at the bottom of each AI reply bubble.
 */
@Composable
fun AssistantMessageFooterRow(
    messageId: String,
    plainText: String,
    controller: MessageTtsController,
) {
    val context = LocalContext.current
    val isSpeaking = controller.speakingMessageId.value == messageId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinisTextButton(onClick = {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("reply", plainText))
            Toast.makeText(context, R.string.usage_action_copied, Toast.LENGTH_SHORT).show()
        }) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.usage_action_copy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        MinisTextButton(onClick = { controller.toggle(messageId, plainText) }) {
            Icon(
                if (isSpeaking) Icons.Default.Stop else Icons.Default.GraphicEq,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
                tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (isSpeaking) R.string.usage_action_stop else R.string.usage_action_read_aloud),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
