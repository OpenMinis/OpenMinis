package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

/** Regression coverage for the serialized chat scroll command boundary. */
class ChatScrollControllerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rapidPreviousClicksAreSerializedWithBoundedQueue() {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { ChatScrollController(LazyListState(), maxQueuedPreviousTurns = 2) }
            var runs by remember { mutableIntStateOf(0) }
            Button(
                modifier = Modifier.testTag("previous-turn"),
                onClick = {
                    controller.enqueuePreviousTurn(scope) {
                        runs++
                        delay(10)
                    }
                },
            ) { Text("previous") }
            Text("runs=$runs")
        }
        repeat(6) { composeRule.onNodeWithTag("previous-turn").performClick() }
        composeRule.waitUntil(2_000) {
            composeRule.onNodeWithText("runs=3").fetchSemanticsNode().config != null
        }
    }
}
