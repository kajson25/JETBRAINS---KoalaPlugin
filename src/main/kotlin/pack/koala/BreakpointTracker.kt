@file:Suppress("ktlint:standard:no-wildcard-imports")

package pack.koala

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
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
    private var showTrace = true
    private var showDiagram = false

    init {
        connection.subscribe(
            XBreakpointListener.TOPIC,
            object : XBreakpointListener<XBreakpoint<*>> {
                override fun breakpointAdded(breakpoint: XBreakpoint<*>) = updateUI()

                override fun breakpointRemoved(breakpoint: XBreakpoint<*>) = updateUI()

                override fun breakpointChanged(breakpoint: XBreakpoint<*>) = updateUI()
            },
        )

        jsQuery.addHandler { request ->
            if (request == "TOGGLE_VIEW") {
                showDiagram = !showDiagram
                updateUI()
                return@addHandler JBCefJSQuery.Response("")
            }

            val padded = request.padEnd((request.length + 3) / 4 * 4, '=')
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

        browser.cefBrowser.executeJavaScript(
            "window.cefQuery = function(obj) { " +
                jsQuery.inject("obj.request") +
                "};",
            browser.cefBrowser.url,
            0,
        )

        updateUI()
    }

    fun displayDebugTrace() {
        showTrace = true
        showDiagram = false
        updateUI()
    }

    fun toggleView() {
        showTrace = true
        showDiagram = !showDiagram
        updateUI()
    }

    fun resetView() {
        showTrace = false
        showDiagram = false
        updateUI()
    }

    private fun updateUI() {
        val breakpoints =
            runReadAction {
                XDebuggerManager
                    .getInstance(project)
                    .breakpointManager.allBreakpoints
                    .toList()
            }

        val hits = debugger.getHits()
        val hitStats = hits.groupingBy { it.filePath to it.line }.eachCount()
        val hitSet = hits.map { it.filePath to it.line }.toSet()

        val html =
            when {
                showTrace && debugger.hasDebugSessionStarted && showDiagram -> {
                    val diagramHtml = renderHitDiagram(hits)
                    wrapInHTML(diagramHtml, "Breakpoint Diagram")
                }

                showTrace && debugger.hasDebugSessionStarted -> {
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

                    wrapInHTML((hitCards + unhitCards).joinToString("\n"), "Post-Debug Breakpoints")
                }

                else -> {
                    val htmlList =
                        breakpoints.joinToString("\n") {
                            val position = it.sourcePosition ?: return@joinToString ""
                            val file = position.file
                            val filePath = file.path
                            val line = position.line
                            val displayLine = line + 1
                            val encodedPath = "$filePath:$displayLine"

                            """
                            <div class="card">
                              <div class="card-title">
                                <a href="navigate://$encodedPath">${File(filePath).name}: Line $displayLine</a>
                              </div>
                            </div>
                            """
                        }
                    wrapInHTML(htmlList, "Breakpoints")
                }
            }

        browser.loadHTML(html)
    }

    private fun wrapInHTML(
        content: String,
        title: String = "Breakpoints",
    ): String =
        """
        <html>
          <head>
            <style>
              body {
                font-family: sans-serif;
                padding: 20px;
                background-color: #262626;
                color: #f5f5f5;
              }
              input {
                width: 100%;
                padding: 6px;
                margin-bottom: 15px;
                border-radius: 4px;
                border: none;
                font-size: 14px;
              }
              .card {
                border: 1px solid #444;
                border-radius: 6px;
                padding: 10px;
                margin-bottom: 10px;
                background-color: #2a2a2a;
              }
              .card-title {
                font-weight: bold;
                margin-bottom: 5px;
              }
              .code-line {
                background-color: #333;
                padding: 5px;
                border-radius: 4px;
                font-family: monospace;
              }
              .diagram-container {
                display: flex;
                flex-wrap: wrap;
                gap: 12px;
                margin: 20px 0;
                justify-content: center;
              }
              .diagram-node {
                background-color: #28a745;
                color: white;
                border-radius: 8px;
                padding: 10px 14px;
                font-family: monospace;
                font-size: 13px;
                box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
                max-width: 250px;
                word-break: break-word;
                text-align: center;
                transition: transform 0.2s ease;
              }
              .diagram-node:hover {
                transform: scale(1.05);
                background-color: #34c759;
              }
              .diagram-arrows {
                font-family: monospace;
                margin-top: 25px;
                background: #282c34;
                padding: 10px;
                border-radius: 6px;
                white-space: pre-wrap;
                font-size: 13px;
                color: #ccc;
                line-height: 1.5;
              }
              .arrow {
                margin: 8px 0;
                padding: 10px;
                background-color: #2e2e2e;
                border-radius: 6px;
                color: #ffcc00;
                box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
                font-family: monospace;
              }
              .btn-toggle {
                display: inline-block;
                margin-bottom: 10px;
                padding: 6px 10px;
                background: #007bff;
                color: white;
                border: none;
                border-radius: 4px;
                text-decoration: none;
                cursor: pointer;
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
                
                const nodes = document.querySelectorAll('.diagram-node');
                nodes.forEach(node => {
                  const text = node.innerText.toLowerCase();
                  node.style.display = text.includes(query) ? 'inline-block' : 'none';
                });
              }

              function toggleView() {
                const url = window.location.href;
                const newUrl = url.includes("diagram=true") ? url.replace("diagram=true", "") : url + "?diagram=true";
                window.location.href = newUrl;
              }
            </script>
          </head>
          <body>
            <a href="navigate://__toggle__" class="btn-toggle">Switch View</a>
            <a href="navigate://__suggest__" class="btn-toggle">Suggest Breakpoints</a>
            <input type="text" placeholder="Search..." oninput="filterList(this.value)">
            <h2>$title</h2>
            $content
          </body>
        </html>
        """.trimIndent()

    private fun renderHitDiagram(hits: List<DebuggerEventListener.BreakpointHit>): String {
        val distinctPoints = hits.map { it.filePath to it.line }.distinct()
        val hitStats = hits.groupingBy { it.filePath to it.line }.eachCount()

        val codePreview =
            distinctPoints.associateWith { (filePath, line) ->
                runCatching {
                    File(filePath)
                        .readLines()
                        .getOrNull(line)
                        ?.trim()
                        ?.take(60) ?: "[unavailable]"
                }.getOrDefault("[unavailable]")
            }

        val nodesHtml =
            distinctPoints.map { (filePath, line) ->
                val fire = if ((hitStats[filePath to line] ?: 0) >= 3) " 🔥" else ""
                val code = codePreview[filePath to line] ?: "[unavailable]"
                val encoded = "$filePath:${line + 1}"
                """
            <div class="diagram-node">
              <a href="navigate://$encoded" style="color: inherit; text-decoration: none;">
                $code$fire
              </a>
            </div>
            """
            }

        val arrowsHtml =
            hits.zipWithNext().mapNotNull { (a, b) ->
                val fromCode = codePreview[a.filePath to a.line]
                val toCode = codePreview[b.filePath to b.line]
                if (fromCode != null && toCode != null && fromCode != toCode) {
                    """<div class="arrow">$fromCode ➡ $toCode</div>"""
                } else {
                    null
                }
            }

        return """
      <div class="diagram-container">
        ${nodesHtml.joinToString("\n")}
      </div>
      <div class="diagram-arrows">
        ${arrowsHtml.joinToString("\n")}
      </div>
    """
    }

    fun displaySuggestions() {
        val suggestions = HeuristicEngine(project, debugger).analyze()

        val cards =
            suggestions.joinToString("\n") { suggestion ->
                val encoded = "${suggestion.filePath}:${suggestion.line + 1}"
                """
                <div class="card" style="background-color: #fff3cd; color: #333;">
                  <div class="card-title">
                    <a href="navigate://$encoded">${File(suggestion.filePath).name}: Line ${suggestion.line + 1}</a>
                  </div>
                  <div class="code-line">${suggestion.code}</div>
                  <div><i>${suggestion.reason}</i></div>
                </div>
                """
            }

        val html = wrapInHTML(cards, "Suggested Breakpoints")
        browser.loadHTML(html)
    }
}
