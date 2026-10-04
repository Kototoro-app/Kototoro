using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;

namespace Kototoro.WebViewBridge {
    static class Program {
        [STAThread]
        static void Main(string[] args) {
            Console.InputEncoding = Encoding.UTF8;
            Console.OutputEncoding = Encoding.UTF8;

            var context = new BridgeApplicationContext();
            Application.Run(context);
        }
    }

    class BridgeApplicationContext : ApplicationContext {
        private readonly Form form;
        private readonly Panel browserHost;
        private readonly TextBox address;
        private readonly JavaScriptSerializer serializer = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 };
        private CoreWebView2Environment env;
        private CoreWebView2Controller controller;
        private CoreWebView2 webView;
        private Thread readerThread;
        private volatile bool isClosing = false;
        private TaskCompletionSource<bool> navCompletion;
        private ulong? navigationId;
        private bool allowHttpErrorResponse;
        private readonly SemaphoreSlim commands = new SemaphoreSlim(1, 1);

        public BridgeApplicationContext() {
            form = new Form {
                ShowInTaskbar = true,
                FormBorderStyle = FormBorderStyle.Sizable,
                Text = "Kototoro 浏览器",
                ClientSize = new Size(1024, 720),
                MinimumSize = new Size(400, 320),
                StartPosition = FormStartPosition.CenterScreen
            };
            browserHost = new Panel { Dock = DockStyle.Fill };
            address = new TextBox { Dock = DockStyle.Top, ReadOnly = true, TabStop = false };
            form.Controls.Add(browserHost);
            form.Controls.Add(address);
            browserHost.Resize += (s, e) => UpdateBounds();
            form.LocationChanged += (s, e) => {
                if (controller != null && !isClosing) controller.NotifyParentWindowPositionChanged();
            };
            form.FormClosing += (s, e) => {
                if (!isClosing && e.CloseReason == CloseReason.UserClosing) {
                    e.Cancel = true;
                    SetWindowVisible(false, 0, 0);
                }
            };
            // Create a stable, initially hidden HWND. Changing taskbar/border styles after init recreates
            // the parent window and disposes its WebView2 controller.
            form.Handle.ToInt64();
            readerThread = new Thread(ReadInputLoop) {
                IsBackground = true,
                Name = "Kototoro Bridge Stdin Reader"
            };
            readerThread.Start();
        }

        private void ReadInputLoop() {
            try {
                string line;
                while ((line = Console.ReadLine()) != null) {
                    line = line.Trim();
                    if (string.IsNullOrEmpty(line)) continue;
                    try {
                        var req = serializer.Deserialize<Dictionary<string, object>>(line);
                        form.BeginInvoke(new Action(async () => {
                            string method = req.ContainsKey("method") ? req["method"] as string : null;
                            bool control = method == "close" || method == "stop" || method == "visibility" ||
                                method == "window" || method == "dismissWindow";
                            if (!control) await commands.WaitAsync();
                            try { await HandleRequestAsync(req); }
                            finally { if (!control) commands.Release(); }
                        }));
                    } catch (Exception ex) {
                        SendError(0, "Invalid JSON: " + ex.Message);
                    }
                }
            } catch {
                // EOF or error
            } finally {
                try {
                    if (!form.IsDisposed && form.IsHandleCreated) form.BeginInvoke(new Action(CloseAll));
                } catch (InvalidOperationException) {
                    // The STA may have disposed the form after a close request but before stdin reached EOF.
                }
            }
        }

        private async Task HandleRequestAsync(Dictionary<string, object> req) {
            long id = 0;
            if (req.ContainsKey("id") && req["id"] != null) {
                long.TryParse(req["id"].ToString(), out id);
            }
            string method = req.ContainsKey("method") ? req["method"] as string : null;
            var @params = req.ContainsKey("params") ? req["params"] as Dictionary<string, object> : null;

            try {
                switch (method) {
                    case "init": {
                        if (webView != null) throw new InvalidOperationException("Bridge is already initialized");
                        string userDataDir = null;
                        if (@params != null && @params.ContainsKey("userDataDir") && @params["userDataDir"] != null) {
                            userDataDir = @params["userDataDir"].ToString();
                        }
                        if (string.IsNullOrEmpty(userDataDir)) {
                            userDataDir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "wv2_profile_" + Guid.NewGuid().ToString("N"));
                        }
                        Directory.CreateDirectory(userDataDir);

                        env = await CoreWebView2Environment.CreateAsync(null, userDataDir);
                        controller = await env.CreateCoreWebView2ControllerAsync(browserHost.Handle);
                        webView = controller.CoreWebView2;
                        controller.IsVisible = false;
                        UpdateBounds();
                        webView.SourceChanged += (s, e) => address.Text = webView.Source;
                        webView.DocumentTitleChanged += (s, e) => {
                            form.Text = string.IsNullOrEmpty(webView.DocumentTitle) ? "Kototoro 浏览器" :
                                webView.DocumentTitle + " · Kototoro 浏览器";
                        };

                        if (@params != null && @params.ContainsKey("userAgent") && @params["userAgent"] != null) {
                            webView.Settings.UserAgent = @params["userAgent"].ToString();
                        }

                        webView.NavigationStarting += (s, e) => {
                            if (navCompletion != null && navigationId == null) navigationId = e.NavigationId;
                        };
                        webView.NavigationCompleted += (s, e) => {
                            if (navCompletion != null && navigationId == e.NavigationId) {
                                if (e.IsSuccess || (allowHttpErrorResponse && e.HttpStatusCode >= 400)) navCompletion.TrySetResult(true);
                                else navCompletion.TrySetException(new Exception("Navigation failed: " + e.WebErrorStatus));
                            }
                        };

                        var res = new Dictionary<string, object> {
                            { "status", "ok" },
                            { "browserVersion", webView.Environment.BrowserVersionString }
                        };
                        SendResult(id, res);
                        break;
                    }
                    case "loadHtml": {
                        EnsureInit();
                        string html = @params != null && @params.ContainsKey("html") ? @params["html"] as string : "";
                        string baseUrl = ReadString(@params, "baseUrl", null);
                        string mimeType = ReadString(@params, "mimeType", "text/html");
                        if (mimeType.IndexOf('\r') >= 0 || mimeType.IndexOf('\n') >= 0) throw new ArgumentException("Invalid MIME type");
                        EventHandler<CoreWebView2WebResourceRequestedEventArgs> handler = null;
                        MemoryStream content = null;
                        if (!string.IsNullOrEmpty(baseUrl) && baseUrl != "about:blank") {
                            Uri parsed = HttpUrl(baseUrl);
                            baseUrl = parsed.AbsoluteUri;
                            var documentRequest = new UriBuilder(parsed); documentRequest.Fragment = "";
                            string requestUrl = documentRequest.Uri.AbsoluteUri;
                            content = new MemoryStream(Encoding.UTF8.GetBytes(html));
                            bool served = false;
                            handler = (s, e) => {
                                if (!served && e.Request.Uri == requestUrl) {
                                    served = true;
                                    e.Response = env.CreateWebResourceResponse(content, 200, "OK", "Content-Type: " + mimeType + "; charset=utf-8");
                                }
                            };
                            webView.AddWebResourceRequestedFilter(requestUrl, CoreWebView2WebResourceContext.Document);
                            webView.WebResourceRequested += handler;
                        }
                        BeginNavigation();
                        try {
                            if (handler == null) webView.NavigateToString(html); else webView.Navigate(baseUrl);
                            await WaitForNavigation();
                        } finally {
                            navCompletion = null;
                            if (handler != null) {
                                webView.WebResourceRequested -= handler;
                                webView.RemoveWebResourceRequestedFilter(new UriBuilder(baseUrl) { Fragment = "" }.Uri.AbsoluteUri,
                                    CoreWebView2WebResourceContext.Document);
                                content.Dispose();
                            }
                        }
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        break;
                    }
                    case "navigate": {
                        EnsureInit();
                        string url = @params != null && @params.ContainsKey("url") ? @params["url"] as string : "";
                        string methodName = ReadString(@params, "httpMethod", "GET");
                        if (methodName != "GET" && methodName != "POST") throw new ArgumentException("Only GET/POST navigation is supported");
                        HttpUrl(url);
                        string headers = "";
                        var headerMap = @params.ContainsKey("headers") ? @params["headers"] as Dictionary<string, object> : null;
                        if (headerMap != null) foreach (var entry in headerMap) {
                            string value = entry.Value as string;
                            if (!System.Text.RegularExpressions.Regex.IsMatch(entry.Key, "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$") ||
                                value == null || value.IndexOf('\r') >= 0 || value.IndexOf('\n') >= 0)
                                throw new ArgumentException("Invalid request header");
                            headers += entry.Key + ": " + value + "\r\n";
                        }
                        byte[] bytes = Convert.FromBase64String(ReadString(@params, "bodyBase64", ""));
                        using (var body = new MemoryStream(bytes)) {
                            var request = env.CreateWebResourceRequest(url, methodName, methodName == "POST" ? body : null, headers);
                            BeginNavigation(ReadBool(@params, "allowHttpErrorResponse", false));
                            try { webView.NavigateWithWebResourceRequest(request); await WaitForNavigation(); }
                            finally { navCompletion = null; }
                        }
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        break;
                    }
                    case "evaluateJs": {
                        EnsureInit();
                        string script = @params != null && @params.ContainsKey("script") ? @params["script"] as string : "";
                        string resultJson = await webView.ExecuteScriptAsync(script);
                        SendResult(id, new Dictionary<string, object> {
                            { "status", "ok" },
                            { "value", resultJson }
                        });
                        break;
                    }
                    case "document": {
                        EnsureInit();
                        SendResult(id, new Dictionary<string, object> { { "url", webView.Source }, { "title", webView.DocumentTitle } });
                        break;
                    }
                    case "visibility": {
                        EnsureInit();
                        int width = ReadWindowSize(@params, "width");
                        int height = ReadWindowSize(@params, "height");
                        SetWindowVisible(ReadBool(@params, "visible", true), width, height);
                        SendResult(id, WindowState());
                        break;
                    }
                    case "window": {
                        EnsureInit();
                        SendResult(id, WindowState());
                        break;
                    }
                    case "dismissWindow": {
                        EnsureInit();
                        form.Close();
                        SendResult(id, WindowState());
                        break;
                    }
                    case "capturePreview": {
                        EnsureInit();
                        using (var image = new MemoryStream()) {
                            await webView.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, image);
                            SendResult(id, new Dictionary<string, object> { { "pngBase64", Convert.ToBase64String(image.ToArray()) } });
                        }
                        break;
                    }
                    case "stop": {
                        EnsureInit();
                        webView.Stop();
                        if (navCompletion != null) navCompletion.TrySetCanceled();
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        break;
                    }
                    case "settings": {
                        EnsureInit();
                        webView.Settings.IsScriptEnabled = ReadBool(@params, "javaScript", true);
                        string ua = ReadString(@params, "userAgent", null);
                        if (!string.IsNullOrWhiteSpace(ua)) webView.Settings.UserAgent = ua;
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        break;
                    }
                    case "getCookies": {
                        EnsureInit();
                        string url = @params != null && @params.ContainsKey("url") ? @params["url"] as string : "";
                        var cookies = await webView.CookieManager.GetCookiesAsync(url);
                        var list = new List<Dictionary<string, object>>();
                        foreach (var c in cookies) {
                            list.Add(new Dictionary<string, object> {
                                { "name", c.Name },
                                { "value", c.Value },
                                { "domain", c.Domain },
                                { "path", c.Path },
                                { "isHttpOnly", c.IsHttpOnly },
                                { "isSecure", c.IsSecure },
                                { "isSession", c.IsSession },
                                { "expiresEpochMillis", c.IsSession ? (object)null :
                                    (long)(c.Expires.ToUniversalTime() - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds },
                                { "expires", c.Expires }
                            });
                        }
                        SendResult(id, new Dictionary<string, object> {
                            { "status", "ok" },
                            { "cookies", list }
                        });
                        break;
                    }
                    case "setCookie": {
                        EnsureInit();
                        string url = @params != null && @params.ContainsKey("url") ? @params["url"] as string : "";
                        string name = @params != null && @params.ContainsKey("name") ? @params["name"] as string : "";
                        string val = @params != null && @params.ContainsKey("value") ? @params["value"] as string : "";
                        string domain = @params != null && @params.ContainsKey("domain") ? @params["domain"] as string : "";
                        string path = @params != null && @params.ContainsKey("path") ? @params["path"] as string : "/";

                        if (string.IsNullOrEmpty(domain)) domain = HttpUrl(url).Host;
                        var c = webView.CookieManager.CreateCookie(name, val, domain, path);
                        c.IsHttpOnly = ReadBool(@params, "isHttpOnly", false);
                        c.IsSecure = ReadBool(@params, "isSecure", false);
                        if (@params.ContainsKey("expiresEpochMillis") && @params["expiresEpochMillis"] != null) {
                            long expiry = Convert.ToInt64(@params["expiresEpochMillis"]);
                            c.Expires = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(expiry);
                        }
                        webView.CookieManager.AddOrUpdateCookie(c);
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        break;
                    }
                    case "deleteCookie": {
                        EnsureInit();
                        string name = ReadString(@params, "name", "");
                        string domain = ReadString(@params, "domain", "");
                        string path = ReadString(@params, "path", "/");
                        webView.CookieManager.DeleteCookiesWithDomainAndPath(name, domain, path);
                        // The native API queues a mutation. A successful RPC must mean the deletion is observable.
                        var deadline = DateTime.UtcNow.AddSeconds(2);
                        while (true) {
                            var cookies = await webView.CookieManager.GetCookiesAsync("");
                            bool remaining = false;
                            foreach (var cookie in cookies) {
                                if (cookie.Name == name && cookie.Domain == domain && cookie.Path == path) {
                                    remaining = true;
                                    break;
                                }
                            }
                            if (!remaining) break;
                            if (DateTime.UtcNow >= deadline) throw new TimeoutException("Cookie deletion was not applied");
                            await Task.Delay(20);
                            EnsureInit();
                        }
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        break;
                    }
                    case "close": {
                        SendResult(id, new Dictionary<string, object> { { "status", "ok" } });
                        CloseAll();
                        break;
                    }
                    default:
                        SendError(id, "Unknown method: " + method);
                        break;
                }
            } catch (Exception ex) {
                SendError(id, ex.Message);
            }
        }

        private void EnsureInit() {
            if (webView == null) throw new InvalidOperationException("WebView2 is not initialized");
        }

        private void UpdateBounds() {
            if (controller != null && !isClosing) controller.Bounds = browserHost.ClientRectangle;
        }

        private void SetWindowVisible(bool visible, int width, int height) {
            if (visible) {
                form.WindowState = FormWindowState.Normal;
                if (width != 0 || height != 0) form.ClientSize = new Size(width == 0 ? form.ClientSize.Width : width,
                    height == 0 ? form.ClientSize.Height : height);
                form.Show();
                UpdateBounds();
                if (controller != null) {
                    controller.IsVisible = true;
                    controller.MoveFocus(CoreWebView2MoveFocusReason.Programmatic);
                }
                form.Activate();
            } else {
                if (controller != null) controller.IsVisible = false;
                form.Hide();
            }
        }

        private Dictionary<string, object> WindowState() {
            return new Dictionary<string, object> {
                { "visible", form.Visible && form.WindowState != FormWindowState.Minimized && controller.IsVisible },
                { "width", controller.Bounds.Width }, { "height", controller.Bounds.Height }, { "title", form.Text }
            };
        }

        private static int ReadWindowSize(Dictionary<string, object> values, string key) {
            if (values == null || !values.ContainsKey(key)) return 0;
            int size = Convert.ToInt32(values[key]);
            if (size < 320 || size > 8192) throw new ArgumentException("Window size must be between 320 and 8192");
            return size;
        }

        private static string ReadString(Dictionary<string, object> values, string key, string fallback) {
            return values != null && values.ContainsKey(key) && values[key] != null ? values[key].ToString() : fallback;
        }

        private static bool ReadBool(Dictionary<string, object> values, string key, bool fallback) {
            return values != null && values.ContainsKey(key) && values[key] is bool ? (bool)values[key] : fallback;
        }

        private static Uri HttpUrl(string value) {
            Uri url;
            if (!Uri.TryCreate(value, UriKind.Absolute, out url) || (url.Scheme != "http" && url.Scheme != "https"))
                throw new ArgumentException("Expected an HTTP(S) URL");
            return url;
        }

        private void BeginNavigation(bool allowHttpError = false) {
            allowHttpErrorResponse = allowHttpError;
            navigationId = null;
            navCompletion = new TaskCompletionSource<bool>();
        }

        private async Task WaitForNavigation() {
            Task finished = navCompletion.Task;
            if (await Task.WhenAny(finished, Task.Delay(14000)) != finished) {
                webView.Stop();
                throw new TimeoutException("Navigation timed out");
            }
            await finished;
        }

        private void SendResult(long id, object result) {
            var resp = new Dictionary<string, object> {
                { "id", id },
                { "result", result }
            };
            string line = serializer.Serialize(resp);
            lock (Console.Out) {
                Console.WriteLine(line);
                Console.Out.Flush();
            }
        }

        private void SendError(long id, string message) {
            if (isClosing) return;
            var resp = new Dictionary<string, object> {
                { "id", id },
                { "error", new Dictionary<string, object> { { "message", message } } }
            };
            string line = serializer.Serialize(resp);
            lock (Console.Out) {
                Console.WriteLine(line);
                Console.Out.Flush();
            }
        }

        private void CloseAll() {
            if (isClosing) return;
            isClosing = true;
            if (navCompletion != null) navCompletion.TrySetCanceled();
            try {
                if (controller != null) {
                    controller.Close();
                    controller = null;
                }
                form.Dispose();
            } catch {
            } finally {
                ExitThread();
            }
        }
    }
}
