package pack.koala

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

class BreakpointToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val browser = JBCefBrowser()
        val debugger = DebuggerEventListener(project)
        val tracker = BreakpointTracker(project, browser, debugger)

        browser.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    println("Request url: ${request?.url}")
                    val url = request?.url ?: return false

                    if (url.startsWith("navigate://")) {
                        val decoded = url.removePrefix("navigate://")
                        if (decoded == "__toggle__") {
                            ApplicationManager.getApplication().invokeLater {
                                tracker.toggleView()
                            }
                            return true
                        }

                        val lastColonIndex = decoded.lastIndexOf(':')
                        if (lastColonIndex == -1) return true

                        val filePath = decoded.substring(0, lastColonIndex)
                        val lineStr = decoded.substring(lastColonIndex + 1)
                        val line = lineStr.toIntOrNull()?.minus(1) ?: return true

                        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                        if (vFile != null && vFile.isValid) {
                            ApplicationManager.getApplication().invokeLater {
                                OpenFileDescriptor(project, vFile, line, 0).navigate(true)
                            }
                        }

                        return true
                    }

                    return false
                }
            },
            browser.cefBrowser,
        )

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)

        debugger.bindTracker(tracker)
        tracker.resetView()
    }
}
