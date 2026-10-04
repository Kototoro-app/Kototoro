package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Interactive browser and HTML/JS debug operations on the session-owned bridge. */
@Composable
internal fun BrowserPanel(session: DesktopSession) {
    var html by remember { mutableStateOf("<html><head><title>Kototoro Windows</title></head><body>离线浏览器</body></html>") }
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
            catch (error: Exception) { result = error.message ?: "浏览器执行失败" }
            finally { busy = false }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("网页与脚本", style = MaterialTheme.typography.h6)
        val engine = session.browser
        if (engine == null) {
            Text("配置 WebView2 桥后即可使用浏览器调试。")
            return@Column
        }
        OutlinedTextField(
            url, { url = it }, label = { Text("网页地址") }, placeholder = { Text("https://…") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("browser-url"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runOperation {
                engine.openUrl(url.trim()).let { "${it.title}\n${it.url}" }
            } }, enabled = !busy && url.isNotBlank(), modifier = Modifier.testTag("browser-open")) { Text("打开网页") }
            OutlinedButton(onClick = { runOperation {
                engine.showWindow(); "窗口已显示"
            } }, enabled = !busy, modifier = Modifier.testTag("browser-show")) { Text("显示窗口") }
            OutlinedButton(onClick = { runOperation {
                engine.hideWindow(); "窗口已隐藏"
            } }, enabled = !busy, modifier = Modifier.testTag("browser-hide")) { Text("隐藏窗口") }
        }
        Text("在浏览器窗口中操作后，可同步 Cookie 供来源请求使用。关闭窗口会隐藏它，退出应用时释放浏览器。")
        OutlinedButton(onClick = { runOperation {
            engine.syncCookies(); "Cookie 已同步"
        } }, enabled = !busy, modifier = Modifier.testTag("browser-cookies")) { Text("同步 Cookie") }
        OutlinedTextField(
            html, { html = it }, label = { Text("HTML") },
            modifier = Modifier.fillMaxWidth().height(150.dp).testTag("browser-html"),
        )
        OutlinedTextField(
            script, { script = it }, label = { Text("JavaScript 表达式") },
            modifier = Modifier.fillMaxWidth().testTag("browser-script"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runOperation { engine.evaluate(html, script) } }, enabled = !busy,
                modifier = Modifier.testTag("browser-load")) { Text("加载 HTML 并执行") }
            OutlinedButton(onClick = { runOperation { engine.evaluateCurrent(script) } }, enabled = !busy,
                modifier = Modifier.testTag("browser-evaluate")) { Text("执行当前页面 JS") }
        }
        if (busy) Text("执行中…", modifier = Modifier.testTag("browser-busy"))
        Text(result, style = MaterialTheme.typography.body1, modifier = Modifier.testTag("browser-result"))
    }
}
