package com.openminis.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.DatabaseVersionGuard

/**
 * [T-android-downgrade-compat] Shown when the chat database on disk was
 * written by a newer build than the one now running.
 *
 * The point of this screen is that the previous behaviour — Room throwing at
 * first database access, so the app simply never opened — was indistinguishable
 * from data loss even though nothing was lost. The database has NOT been
 * touched: the version was read through a read-only handle and Room was never
 * constructed, so no migration and no `dropAllTables` could have run.
 *
 * Deliberately offers no "clear data and continue" button. That is the one
 * action that would actually destroy the user's history, and offering it to
 * someone who is confused and just wants the app to open is how data gets
 * lost. Reinstalling the newer build restores everything.
 */
@Composable
fun NewerDatabaseGuidanceScreen(onExit: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.newer_db_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.newer_db_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // Concrete numbers, because "a newer version" alone leaves
                    // the user with nothing to check or report.
                    Text(
                        text = stringResource(
                            R.string.newer_db_versions,
                            DatabaseVersionGuard.CODE_DB_VERSION,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.newer_db_exit))
                    }
                }
            }
        }
    }
}
