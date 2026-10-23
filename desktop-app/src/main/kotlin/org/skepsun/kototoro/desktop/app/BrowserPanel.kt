package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Interactive browser and HTML/JS debug operations on the session-owned bridge. */
@Composable
internal fun BrowserPanel(session: DesktopSession) {
    var html by remember { mutableStateOf("<html><head><title>Kototoro Windows</title></head><body>${desktopText("browser.offlinePage")}</body></html>") }
    var url by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("document.title") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun runOperation(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { result = block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { result = error.message ?: desktopText("browser.failed") }
            finally { busy = false }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(desktopText("browser.title"), style = MaterialTheme.typography.titleLarge)
        val engine = session.browser
        if (engine == null) {
            Text(desktopText("browser.hint"))
            return@Column
        }
        OutlinedTextField(
            url, { url = it }, label = { Text(desktopText("browser.address")) }, placeholder = { Text("https://…") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("browser-url"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runOperation {
                engine.openUrl(url.trim()).let { "${it.title}\n${it.url}" }
            } }, enabled = !busy && url.isNotBlank(), modifier = Modifier.testTag("browser-open")) { Text(desktopText("browser.open")) }
            OutlinedButton(onClick = { runOperation {
                engine.showWindow(); desktopText("browser.shown")
            } }, enabled = !busy, modifier = Modifier.testTag("browser-show")) { Text(desktopText("browser.show")) }
            OutlinedButton(onClick = { runOperation {
                engine.hideWindow(); desktopText("browser.hidden")
            } }, enabled = !busy, modifier = Modifier.testTag("browser-hide")) { Text(desktopText("browser.hide")) }
        }
        Text(desktopText("browser.hint2"))
        OutlinedButton(onClick = { runOperation {
            engine.syncCookies(); desktopText("browser.synced")
        } }, enabled = !busy, modifier = Modifier.testTag("browser-cookies")) { Text(desktopText("browser.syncCookies")) }
        OutlinedTextField(
            html, { html = it }, label = { Text("HTML") },
            modifier = Modifier.fillMaxWidth().height(150.dp).testTag("browser-html"),
        )
        OutlinedTextField(
            script, { script = it }, label = { Text(desktopText("browser.script")) },
            modifier = Modifier.fillMaxWidth().testTag("browser-script"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runOperation { engine.evaluate(html, script) } }, enabled = !busy,
                modifier = Modifier.testTag("browser-load")) { Text(desktopText("browser.loadHtml")) }
            OutlinedButton(onClick = { runOperation { engine.evaluateCurrent(script) } }, enabled = !busy,
                modifier = Modifier.testTag("browser-evaluate")) { Text(desktopText("browser.evaluate")) }
        }
        if (busy) Text(desktopText("browser.busy"), modifier = Modifier.testTag("browser-busy"))
        Text(result, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("browser-result"))
    }
}
