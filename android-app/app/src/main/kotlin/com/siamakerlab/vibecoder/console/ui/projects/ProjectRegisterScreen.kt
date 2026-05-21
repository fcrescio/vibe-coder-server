package com.siamakerlab.vibecoder.console.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.ProjectRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.shared.dto.KeystoreRequestDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUi(val loading: Boolean = false, val error: String? = null, val registeredId: String? = null)

@HiltViewModel
class ProjectRegisterViewModel @Inject constructor(private val repo: ProjectRepository) : ViewModel() {
    val state = MutableStateFlow(RegisterUi())
    fun submit(req: RegisterProjectRequestDto) {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.register(req) }
                .onSuccess { dto -> state.update { it.copy(loading = false, registeredId = dto.id) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

/**
 * Auto-derive helpers for the registration form. Users edit "App name" and the
 * folder + package fields pre-populate but stay editable; the auto-fill stops
 * the moment the user types into those fields manually.
 */
private fun toFolderName(appName: String): String =
    appName.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

private fun toPackageName(folderName: String): String {
    val clean = folderName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .ifEmpty { "app" }
    return "com.siamakerlab.$clean"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectRegisterScreen(
    onRegistered: (String) -> Unit,
    onBack: () -> Unit,
    vm: ProjectRegisterViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var appName by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var folderTouched by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf("") }
    var pkgTouched by remember { mutableStateOf(false) }

    var keystoreOn by remember { mutableStateOf(false) }
    var ksAlias by remember { mutableStateOf("") }
    var ksPassword by remember { mutableStateOf("") }

    LaunchedEffect(state.registeredId) {
        state.registeredId?.let(onRegistered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.register_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            field(stringResource(R.string.register_app_name), appName) {
                appName = it
                if (!folderTouched) folder = toFolderName(it)
                if (!pkgTouched) pkg = toPackageName(folder.ifEmpty { toFolderName(it) })
            }
            field(stringResource(R.string.register_folder_name), folder) {
                folderTouched = true
                folder = it
                if (!pkgTouched) pkg = toPackageName(it)
            }
            field(stringResource(R.string.register_package), pkg) {
                pkgTouched = true
                pkg = it
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.register_keystore_create),
                    style = MaterialTheme.typography.titleSmall,
                )
                Switch(checked = keystoreOn, onCheckedChange = { keystoreOn = it })
            }

            if (keystoreOn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.register_keystore_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                field(
                    label = stringResource(R.string.register_keystore_alias),
                    value = ksAlias,
                    placeholderHint = folder.ifEmpty { "app" },
                ) { ksAlias = it }
                OutlinedTextField(
                    value = ksPassword,
                    onValueChange = { ksPassword = it },
                    label = { Text(stringResource(R.string.register_keystore_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val keystore = if (keystoreOn) {
                        KeystoreRequestDto(
                            alias = ksAlias.trim().ifBlank { folder.ifBlank { "app" } },
                            password = ksPassword,
                        )
                    } else null
                    vm.submit(
                        RegisterProjectRequestDto(
                            projectId = folder.trim(),
                            appName = appName.trim(),
                            packageName = pkg.trim(),
                            keystore = keystore,
                        )
                    )
                },
                enabled = !state.loading
                    && appName.isNotBlank() && folder.isNotBlank() && pkg.isNotBlank()
                    && (!keystoreOn || ksPassword.length >= 6),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.register_button)) }
            state.error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun field(
    label: String,
    value: String,
    placeholderHint: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholderHint?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}
