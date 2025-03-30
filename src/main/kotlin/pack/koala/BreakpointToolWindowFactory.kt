package pack.koala

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser

class BreakpointToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val browser = JBCefBrowser()
        val contentFactory = ContentFactory.getInstance() // ovde je bio service
        val content = contentFactory.createContent(browser.component, "", false)

        toolWindow.contentManager.addContent(content)

        // Inject minimal HTML template
        browser.loadHTML(
            """
            <html>
              <head><style>body { font-family: sans-serif; }</style></head>
              <body>
                <h2>Breakpoints</h2>
                <ul id="breakpoint-list"></ul>
              </body>
            </html>
            """.trimIndent(),
        )

        // Start observing breakpoints
        BreakpointTracker(project, browser)
    }
}
