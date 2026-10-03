package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

/**
 * [T-copilot-disclaimer] Risk notice shown BEFORE the GitHub Device Flow can
 * start. Cancelling returns without any network call being made.
 *
 * This is a gate, not a banner: the caller opens it instead of beginning
 * sign-in, and only [onAccept] starts the flow. Keeping the two actions that
 * far apart is deliberate — a notice the user can scroll past while the login
 * is already running would be decoration.
 *
 * [T-android-copilot-disclaimer-parity] The body is iOS's notice, point for
 * point and in the same order: unofficial status, borrowed client identity,
 * access may be withdrawn, account-termination risk, not for work accounts,
 * where the token goes, and the as-is clause.
 *
 * Each risk is its own bullet rather than a paragraph of prose. They differ in
 * KIND — one is about provenance, one about your account, one about your data —
 * and a reader skimming a block of small grey text takes none of them in. That
 * is iOS's reasoning and it applies unchanged here; Android previously ran the
 * same content together as three paragraphs and omitted the as-is clause
 * entirely.
 *
 * Scrollable because the text overflows a small screen, and a truncated
 * disclaimer is worse than none: the points most worth reading (account risk,
 * not for org accounts) sit in the middle and at the end.
 */
@Composable
fun CopilotDisclaimerDialog(
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9500),
                modifier = Modifier.size(36.dp),
            )
        },
        title = { Text(stringResource(R.string.copilot_disclaimer_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (point in disclaimerPoints) {
                    DisclaimerBullet(stringResource(point))
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onAccept) {
                Text(stringResource(R.string.copilot_disclaimer_accept))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onCancel) {
                Text(stringResource(R.string.copilot_disclaimer_cancel))
            }
        },
    )
}

/** The notice, in iOS's order. */
private val disclaimerPoints = listOf(
    R.string.copilot_disclaimer_p1,
    R.string.copilot_disclaimer_p2,
    R.string.copilot_disclaimer_p3,
    R.string.copilot_disclaimer_p4,
    R.string.copilot_disclaimer_p5,
    R.string.copilot_disclaimer_p6,
    R.string.copilot_disclaimer_p7,
)

/** One bullet of the notice — mirrors iOS `disclaimerPoint`. */
@Composable
private fun DisclaimerBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
