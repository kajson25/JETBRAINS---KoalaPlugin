@file:Suppress("ktlint:standard:filename", "ktlint:standard:no-wildcard-imports")

package pack.koala

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.*

class DebuggerEventListener(
    private val project: Project,
) {
    private val connection: MessageBusConnection = project.messageBus.connect()
    val hitTrace = mutableListOf<BreakpointHit>()
    private lateinit var tracker: BreakpointTracker
    var hasDebugSessionStarted = false
        private set

    fun bindTracker(tracker: BreakpointTracker) {
        this.tracker = tracker
    }

    init {
        connection.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    hasDebugSessionStarted = true
                    hitTrace.clear()
                    tracker.resetView()

                    val session = debugProcess.session
                    session.addSessionListener(
                        object : XDebugSessionListener {
                            override fun sessionPaused() {
                                val stackFrame = session.currentStackFrame
                                val sourcePosition = stackFrame?.sourcePosition
                                val file = sourcePosition?.file
                                val line = sourcePosition?.line

                                if (file != null && line != null) {
                                    hitTrace.add(
                                        BreakpointHit(
                                            filePath = file.path,
                                            line = line,
                                            timestamp = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            }

                            override fun sessionStopped() {
                                println("🔚 Debug session ended. Trace collected:")
                                hitTrace.forEachIndexed { index, hit ->
                                    println("${index + 1}. ${hit.filePath}:${hit.line + 1}")
                                }
                                tracker.displayDebugTrace()
                            }
                        },
                    )
                }
            },
        )
    }

    data class BreakpointHit(
        val filePath: String,
        val line: Int,
        val timestamp: Long,
    )

    fun getHits(): List<BreakpointHit> = hitTrace.toList()
}
