package pack.koala

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import java.util.Base64

class BreakpointTracker(
    private val project: Project,
    private val browser: JBCefBrowserBase,
) {
    private val connection: MessageBusConnection = project.messageBus.connect()
    private val jsQuery = JBCefJSQuery.create(browser)

    init {
        connection.subscribe(
            XBreakpointListener.TOPIC,
            object : XBreakpointListener<XBreakpoint<*>> {
                override fun breakpointAdded(breakpoint: XBreakpoint<*>) = updateUI()

                override fun breakpointRemoved(breakpoint: XBreakpoint<*>) = updateUI()

                override fun breakpointChanged(breakpoint: XBreakpoint<*>) = updateUI()
            },
        )

        jsQuery.addHandler { encoded ->
            val decoded = String(Base64.getDecoder().decode(encoded))
            val (filePath, lineStr) = decoded.split(":")
            val line = lineStr.toIntOrNull()?.minus(1) ?: return@addHandler null // convert back to 0-based line number

            val vFile =
                com.intellij.openapi.vfs.LocalFileSystem
                    .getInstance()
                    .refreshAndFindFileByPath(filePath)

            if (vFile != null && vFile.isValid) {
                OpenFileDescriptor(project, vFile, line).navigate(true)
            }
            null
        }

        updateUI()
    }

    private fun updateUI() {
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints

        val htmlList =
            breakpoints.joinToString("\n") {
                val position = it.sourcePosition ?: return@joinToString ""
                val file = position.file
                val filePath = file.path
                val fileName = file.name
                val line = position.line
                val lineDisplay = line + 1

                val encoded = Base64.getEncoder().encodeToString("$filePath:$lineDisplay".toByteArray())

                // Extract the actual line content from file
                val document =
                    com.intellij.openapi.fileEditor.FileDocumentManager
                        .getInstance()
                        .getDocument(file)
                val codeLine =
                    document?.getLineStartOffset(line)?.let { start ->
                        val end = document.getLineEndOffset(line)
                        document
                            .getText(
                                com.intellij.openapi.util
                                    .TextRange(start, end),
                            ).trim()
                    } ?: "(line unavailable)"

                """
        <div class="card">
          <div class="card-title">
            <a href="#" onclick="window.__IntelliJBridge__('$encoded')">$fileName: Line $lineDisplay</a>
          </div>
          <pre class="code-line">$codeLine</pre>
        </div>
        """
            }

        val searchScript = jsQuery.inject("window.__IntelliJBridge__")

        val html =
            """
            <html>
              <head>
                <style>
                  body { font-family: sans-serif; padding: 10px; }
                  input { width: 100%; padding: 5px; margin-bottom: 10px; }
                  .card {
                    border: 1px solid #ccc;
                    border-radius: 6px;
                    padding: 10px;
                    margin-bottom: 10px;
                    background: #f8f8f8;
                  }
                  .card-title {
                    font-weight: bold;
                    margin-bottom: 5px;
                  }
                  .code-line {
                    background-color: #eee;
                    padding: 5px;
                    border-radius: 4px;
                    font-family: monospace;
                  }
                </style>
              </head>
              <body>
                <input type="text" placeholder="Search..." oninput="filterList(this.value)">
                <h2>Breakpoints</h2>
                <div id="breakpoint-list">$htmlList</div>
                <script>
                  $searchScript
                  function filterList(query) {
                    const list = document.querySelectorAll('#breakpoint-list .card');
                    list.forEach(item => {
                      item.style.display = item.textContent.toLowerCase().includes(query.toLowerCase()) ? '' : 'none';
                    });
                  }
                </script>
              </body>
            </html>
            """.trimIndent()

        browser.loadHTML(html)
    }
}
