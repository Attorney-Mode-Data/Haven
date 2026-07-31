package sh.haven.feature.connections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.haven.core.rclone.ParsedRemote

/**
 * Imports remotes from a Linux `rclone.conf` so the user doesn't reconfigure
 * them in Haven (#251). Paste the file or pick it; each chosen remote becomes
 * an rclone remote + an RCLONE connection profile.
 */
@Composable
fun ImportRcloneConfigDialog(
    onDismiss: () -> Unit,
    viewModel: RcloneConfigViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.importState.collectAsStateWithLifecycle()
    var pasted by remember { mutableStateOf("") }

    // OpenDocument (ACTION_OPEN_DOCUMENT), not GetContent: GET_CONTENT is
    // resolved by whatever handler the ROM ships, which is how this started
    // opening "a strange screen about intents" on some devices without any
    // change on our side (#468). OPEN_DOCUMENT always lands in the system
    // SAF picker.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Every branch reports something. Silence here is what left #468's
        // reporter unable to tell a cancelled pick from a broken one.
        if (uri == null) {
            viewModel.reportImportFailed(
                "No file was returned. If another app intercepted the file picker, " +
                    "paste the contents of rclone.conf below instead.",
            )
        } else {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrElse { e ->
                viewModel.reportImportFailed("Could not read that file: ${e.message}")
                null
            }
            when {
                text == null -> Unit // already reported above
                text.isBlank() -> viewModel.reportImportFailed("That file is empty.")
                else -> {
                    pasted = text
                    viewModel.loadConfig(text)
                }
            }
        }
    }

    // A device with no SAF picker at all throws here rather than returning a
    // null uri; #468 found a ROM where the only handler could be disabled.
    fun launchPicker() {
        runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
            viewModel.reportImportFailed(
                "No file picker is available on this device. " +
                    "Paste the contents of rclone.conf below instead.",
            )
        }
    }

    fun close() {
        viewModel.resetImport()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.rclone_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    is RcloneConfigViewModel.ImportState.Loaded -> SelectRemotes(s, viewModel)
                    is RcloneConfigViewModel.ImportState.Importing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(20.dp))
                            Spacer(Modifier.fillMaxWidth(0.05f))
                            Text(stringResource(R.string.rclone_import_title))
                        }
                    is RcloneConfigViewModel.ImportState.Encrypted ->
                        Text(stringResource(R.string.rclone_import_encrypted))
                    is RcloneConfigViewModel.ImportState.Failed -> {
                        // The error is shown ABOVE the normal controls, not
                        // instead of them: telling someone to paste the file
                        // while hiding the paste box would make them close and
                        // reopen the dialog to act on the advice (#468).
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        PickOrPaste(
                            pasted = pasted,
                            onPastedChange = { pasted = it },
                            onPick = ::launchPicker,
                        )
                    }
                    is RcloneConfigViewModel.ImportState.Done ->
                        Text(
                            stringResource(
                                R.string.rclone_import_done,
                                s.created.size, s.skipped.size, s.failed.size,
                            ) + s.failed.entries.joinToString("") { "\n• ${it.key}: ${it.value}" },
                        )
                    RcloneConfigViewModel.ImportState.Idle -> {
                        Text(stringResource(R.string.rclone_import_intro), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        PickOrPaste(
                            pasted = pasted,
                            onPastedChange = { pasted = it },
                            onPick = ::launchPicker,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                // Failed offers Parse as well, so a pasted config recovers the
                // dialog in place instead of needing it closed and reopened.
                RcloneConfigViewModel.ImportState.Idle,
                is RcloneConfigViewModel.ImportState.Failed,
                ->
                    TextButton(
                        onClick = { viewModel.loadConfig(pasted) },
                        enabled = pasted.isNotBlank(),
                    ) { Text(stringResource(R.string.rclone_import_parse)) }
                is RcloneConfigViewModel.ImportState.Done ->
                    TextButton(onClick = ::close) { Text(stringResource(R.string.common_done)) }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun SelectRemotes(
    loaded: RcloneConfigViewModel.ImportState.Loaded,
    viewModel: RcloneConfigViewModel,
) {
    // Default-select everything importable (has a type, not already added).
    val checked = remember(loaded) {
        mutableStateMapOf<String, Boolean>().apply {
            loaded.remotes.forEach { put(it.name, it.type.isNotBlank() && it.name !in loaded.existing) }
        }
    }
    Column {
        loaded.remotes.forEach { remote ->
            val already = remote.name in loaded.existing
            val noType = remote.type.isBlank()
            val disabled = already || noType
            val sub = when {
                already -> "${remote.type} · ${stringResource(R.string.rclone_import_already_added)}"
                noType -> stringResource(R.string.rclone_import_no_type)
                else -> remote.type
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = checked[remote.name] == true,
                    onCheckedChange = if (disabled) null else { v -> checked[remote.name] = v },
                    enabled = !disabled,
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(remote.name, style = MaterialTheme.typography.bodyMedium)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val selected: List<ParsedRemote> = loaded.remotes.filter { checked[it.name] == true }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { viewModel.importRemotes(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.rclone_import_select_count, selected.size)) }
        }
    }
}

/** The pick-a-file button plus the paste box, shown both initially and after a
 *  failure so the advice in an error message is actionable where it appears. */
@Composable
private fun PickOrPaste(
    pasted: String,
    onPastedChange: (String) -> Unit,
    onPick: () -> Unit,
) {
    OutlinedButton(onClick = onPick) {
        Text(stringResource(R.string.rclone_import_pick_file))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = pasted,
        onValueChange = onPastedChange,
        label = { Text(stringResource(R.string.rclone_import_paste_label)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
    )
}
