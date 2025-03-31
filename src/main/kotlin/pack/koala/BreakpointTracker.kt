@file:Suppress("ktlint:standard:no-wildcard-imports")

package pack.koala

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BreakpointTracker(
    private val project: Project,
    private val browser: JBCefBrowserBase,
    private val debugger: DebuggerEventListener,
) {
    private val connection: MessageBusConnection = project.messageBus.connect()
    private val jsQuery = JBCefJSQuery.create(browser)
    private var showTrace: Boolean = false

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
            val padded = encoded.padEnd((encoded.length + 3) / 4 * 4, '=')
            val decoded = String(Base64.getUrlDecoder().decode(padded))

            val lastColonIndex = decoded.lastIndexOf(':')
            if (lastColonIndex == -1) return@addHandler null

            val filePath = decoded.substring(0, lastColonIndex)
            val lineStr = decoded.substring(lastColonIndex + 1)
            val line = lineStr.toIntOrNull() ?: return@addHandler null

            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            if (vFile != null && vFile.isValid) {
                ApplicationManager.getApplication().invokeLater {
                    OpenFileDescriptor(project, vFile, line, 0).navigate(true)
                }
            }

            null
        }

        updateUI()
    }

    fun displayDebugTrace() {
        showTrace = true
        updateUI()
    }

    private fun updateUI() {
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
        val hits = debugger.getHits()
        val hitStats = hits.groupingBy { it.filePath to it.line }.eachCount()
        val hitSet = hits.map { it.filePath to it.line }.toSet()

        val hitCards =
            hits.map { hit ->
                val count = hitStats[hit.filePath to hit.line] ?: 0
                val fire = if (count >= 3) "🔥" else ""
                val encoded = "${hit.filePath}:${hit.line + 1}"
                val name = File(hit.filePath).name

                """
                <div class="card" style="background-color: #d4edda;">
                  <div class="card-title">
                    <a href="navigate://$encoded">$name: Line ${hit.line + 1}</a> $fire
                  </div>
                  <div class="code-line">Hit at ${SimpleDateFormat("HH:mm:ss").format(Date(hit.timestamp))} ($count times)</div>
                </div>
                """
            }

        val unhit =
            breakpoints.filter {
                val position = it.sourcePosition ?: return@filter false
                (position.file.path to position.line) !in hitSet
            }

        val unhitCards =
            unhit.mapNotNull {
                val position = it.sourcePosition ?: return@mapNotNull null
                val filePath = position.file.path
                val line = position.line
                val encoded = "$filePath:${line + 1}"
                val name = File(filePath).name

                """
        <div class="card" style="background-color: #f8d7da;">
          <div class="card-title">
            <a href="navigate://$encoded">$name: Line ${line + 1}</a>
          </div>
          <div class="code-line">Not hit</div>
        </div>
        """
            }

        val finalHtml = wrapInHTML((hitCards + unhitCards).joinToString("\n"), "Post-Debug Breakpoints")
        browser.loadHTML(finalHtml)
    }

    private fun wrapInHTML(
        content: String,
        title: String = "Breakpoints",
    ): String =
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
            <script>
              function filterList(query) {
                query = query.toLowerCase();
                const cards = document.querySelectorAll('.card');
                cards.forEach(card => {
                  const text = card.innerText.toLowerCase();
                  card.style.display = text.includes(query) ? 'block' : 'none';
                });
              }
            </script>
          </head>
          <body>
            <input type="text" placeholder="Search..." oninput="filterList(this.value)">
            <h2>$title</h2>
            <div id="breakpoint-list">$content</div>
          </body>
        </html>
        """.trimIndent()

    private fun renderHitTrace(hits: List<DebuggerEventListener.BreakpointHit>): String =
        hits.joinToString("\n") { hit ->
            val displayLine = hit.line + 1
            val time = SimpleDateFormat("HH:mm:ss").format(Date(hit.timestamp))
            """
            <div class="card">
              <div class="card-title">
                <a href="navigate://${hit.filePath}:$displayLine">${File(hit.filePath).name}: Line $displayLine</a>
              </div>
              <div class="code-line">Hit at $time</div>
            </div>
            """
        }
}
