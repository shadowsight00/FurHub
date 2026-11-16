package com.unifiedart.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unifiedart.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountSettings(model: GalleryViewModel, onClose: () -> Unit) {
    val state by model.accounts.state.collectAsState()
    var eName by remember { mutableStateOf("") }
    var eKey by remember { mutableStateOf("") }
    var iName by remember { mutableStateOf("") }
    var iPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""; model.cancel()
        scope.launch {
            try { action(); model.refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "Account connection failed" }
            finally { busy = false; eKey = ""; iPassword = "" }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Connected accounts") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (state.message.isNotEmpty()) Text(state.message)
            Text("e621", style = MaterialTheme.typography.titleMedium)
            if (state.e621Name.isNotBlank()) {
                Text("Connected as ${state.e621Name}")
                Row {
                    TextButton(onClick = { perform { model.accounts.refreshE621() } }, enabled = !busy) { Text("Sync blacklist") }
                    TextButton(onClick = { perform { model.accounts.logout(Source.E621) } }, enabled = !busy) { Text("Disconnect") }
                }
                Text("Your account blacklist applies to e621 artwork. To switch accounts, disconnect and reconnect.")
            } else {
                OutlinedTextField(eName, { eName = it }, label = { Text("Username") }, singleLine = true, enabled = !busy)
                OutlinedTextField(eKey, { eKey = it }, label = { Text("API key") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(onClick = { val key = eKey; eKey = ""; perform { model.accounts.connectE621(eName, key) } }, enabled = !busy && eName.isNotBlank() && eKey.isNotBlank()) { Text("Connect e621") }
            }
            HorizontalDivider()
            Text("Inkbunny", style = MaterialTheme.typography.titleMedium)
            if (state.inkbunnyName.isNotBlank()) {
                Text("Connected as ${state.inkbunnyName}")
                Text("Site preferences apply to this session. Reconnect to load changed viewing preferences. Expired sessions require a new login; your password isn't stored.")
                TextButton(onClick = { perform { model.accounts.logout(Source.INKBUNNY) } }, enabled = !busy) { Text("Disconnect Inkbunny") }
            } else {
                Text("Enable API Access in your Inkbunny account settings before connecting.")
                OutlinedTextField(iName, { iName = it }, label = { Text("Username") }, singleLine = true, enabled = !busy)
                OutlinedTextField(iPassword, { iPassword = it }, label = { Text("Password") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(onClick = { val password = iPassword; iPassword = ""; perform { model.accounts.connectInkbunny(iName, password) } }, enabled = !busy && iName.isNotBlank() && iPassword.isNotBlank()) { Text("Connect Inkbunny") }
            }
            Text("Favorites are merged into one gallery. Inkbunny's documented API doesn't expose favorites editing or full preference export.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Done") } })
}
