package me.ashikoki.listea

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.ui.theme.ListeaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ListeaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FolderScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private sealed interface FolderUiState {
    data class NoFolder(val message: String?) : FolderUiState
    data object Loading : FolderUiState
    data class Loaded(val contents: FolderContents) : FolderUiState
}

@Composable
fun FolderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { FolderStore(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<FolderUiState>(FolderUiState.Loading) }

    suspend fun load(uri: Uri?) {
        if (uri == null) {
            state = FolderUiState.NoFolder(message = null)
            return
        }
        state = FolderUiState.Loading
        val contents = withContext(Dispatchers.IO) { readFolder(context, uri) }
        state = if (contents == null) {
            store.clear()
            FolderUiState.NoFolder("That folder is no longer accessible. Please choose it again.")
        } else {
            FolderUiState.Loaded(contents)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        scope.launch {
            if (granted) {
                store.save(uri)
                load(uri)
            } else {
                state = FolderUiState.NoFolder("Could not keep access to that folder. Please try again.")
            }
        }
    }

    LaunchedEffect(Unit) { load(store.read()) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Listea", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        when (val current = state) {
            is FolderUiState.Loading -> CircularProgressIndicator()

            is FolderUiState.NoFolder -> {
                Text("No folder selected", style = MaterialTheme.typography.bodyLarge)
                current.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
            }

            is FolderUiState.Loaded -> {
                Text(current.contents.folderName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${current.contents.entries.size} entries",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { load(store.read()) } }) { Text("Refresh") }
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Change folder") }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                if (current.contents.entries.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("This folder is empty", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(current.contents.entries) { entry ->
                            EntryRow(entry)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FolderEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
        val detail = if (entry.isDirectory) {
            "Directory"
        } else {
            "File" + (entry.sizeBytes?.let { " · ${formatSize(it)}" } ?: "")
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
