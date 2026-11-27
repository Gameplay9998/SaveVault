package com.savevault.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val s = vm.settingsState

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setLocalFolder(uri)
    }
    val driveAuth = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onDriveResult(if (r.resultCode == Activity.RESULT_OK) r.data else null)
    }

    LaunchedEffect(Unit) { if (vm.rootAvailable == null) vm.checkRoot() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- local ----------
            SettingsCard("Backup local", Icons.Outlined.Folder) {
                if (s.localFolder != null) {
                    Text("Pasta: ${s.localFolder}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Pasta interna do app", style = MaterialTheme.typography.bodyMedium)
                    Hint(vm.internalBackupDir())
                    Hint(
                        "Atenção: a pasta interna é apagada se o app for desinstalado. " +
                            "Escolha uma pasta sua (ex.: Documents/SaveVault) para guardar com segurança.",
                        error = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFolder.launch(null) }) { Text("Escolher pasta") }
                    if (s.localFolder != null) {
                        TextButton(onClick = { vm.setLocalFolder(null) }) { Text("Usar pasta interna") }
                    }
                }
            }

            // ---------- drive ----------
            SettingsCard("Google Drive", if (s.driveConnected) Icons.Outlined.Cloud else Icons.Outlined.CloudOff) {
                if (s.driveConnected) {
                    Text("Conectado" + (s.driveEmail?.let { " como $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Hint("Os backups ficam na pasta \"SaveVault\" do seu Drive.")
                    OutlinedButton(onClick = { vm.disconnectDrive() }) { Text("Desconectar") }
                } else {
                    Text("Não conectado", style = MaterialTheme.typography.bodyMedium)
                    Hint("O app só tem acesso aos arquivos que ele mesmo cria no seu Drive.")
                    Button(
                        onClick = {
                            vm.connectDrive { pi -> driveAuth.launch(IntentSenderRequest.Builder(pi).build()) }
                        },
                        enabled = !vm.driveBusy,
                    ) {
                        if (vm.driveBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Conectar conta Google")
                    }
                }
            }

            // ---------- automático ----------
            SettingsCard("Backup automático", null) {
                SwitchRow("Ativar backup automático", s.autoEnabled) { vm.setAutoEnabled(it) }
                if (s.autoEnabled) {
                    Text("Verificar a cada:", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 3, 6, 12, 24).forEach { h ->
                            FilterChip(
                                selected = s.intervalHours == h,
                                onClick = { vm.setInterval(h) },
                                label = { Text("${h}h") },
                            )
                        }
                    }
                    SwitchRow("Enviar ao Drive só no Wi-Fi", s.wifiOnly) { vm.setWifiOnly(it) }
                    Hint(
                        "Só os jogos com \"Incluir no backup automático\" entram. Com \"só se o save mudou\" " +
                            "ligado, nada é copiado quando o save não mudou. O Android pode atrasar a execução " +
                            "para economizar bateria.",
                    )
                    Hint("Última execução: ${relativeTime(s.lastAutoRun)}")
                }
            }

            // ---------- root ----------
            SettingsCard("Root", Icons.Outlined.Security) {
                Text(
                    when (vm.rootAvailable) {
                        null -> "Verificando…"
                        true -> "Root disponível ✓"
                        false -> "Root não disponível"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint(
                    "Root é necessário para copiar saves de jogos Android que ficam em /data/data. " +
                        "Sem root, use o modo \"Pasta\" (funciona para emuladores e jogos que salvam no armazenamento).",
                )
                TextButton(onClick = { vm.checkRoot() }) { Text("Verificar de novo") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    content: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
