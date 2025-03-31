@file:Suppress("ktlint:standard:no-wildcard-imports")

package pack.koala

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.*
import com.intellij.xdebugger.XDebuggerManager
import java.io.File

class HeuristicEngine(
    private val project: Project,
    private val debugger: DebuggerEventListener,
) {
    data class HeuristicSuggestion(
        val filePath: String,
        val line: Int,
        val code: String,
        val reason: String,
    )

    fun analyze(): List<HeuristicSuggestion> {
        val suggestions = mutableListOf<HeuristicSuggestion>()
        val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
        val allBreakpoints =
            runReadAction {
                breakpointManager.allBreakpoints
                    .mapNotNull { it.sourcePosition?.let { pos -> File(pos.file.path).canonicalPath to pos.line } }
                    .toSet()
            }

        val hitStats = debugger.hitTrace.groupingBy { it.filePath to it.line }.eachCount()
        val hitSet = debugger.hitTrace.map { it.filePath to it.line }.toSet()

        val projectDir = project.baseDir
        val psiFiles = mutableListOf<PsiFile>()

        VfsUtilCore.iterateChildrenRecursively(projectDir, null) { vFile ->
            if (!vFile.isDirectory && (vFile.name.endsWith(".kt") || vFile.name.endsWith(".java"))) {
                val psiFile = runReadAction { PsiManager.getInstance(project).findFile(vFile) }
                if (psiFile != null) {
                    psiFiles.add(psiFile)
                }

                val file = File(vFile.path)
                if (file.exists()) {
                    val lines = file.readLines()
                    lines.forEachIndexed { index, line ->
                        val cleanLine = line.trim()
                        val isLogLine =
                            cleanLine.contains("println(") ||
                                cleanLine.contains("System.out.print") ||
                                cleanLine.contains("log(") ||
                                cleanLine.contains("logger.")
                        if (isLogLine && (file.canonicalPath to index) !in allBreakpoints) {
                            suggestions.add(
                                HeuristicSuggestion(
                                    filePath = file.canonicalPath,
                                    line = index,
                                    code = cleanLine.take(80),
                                    reason = "Contains print/log statement",
                                ),
                            )
                        }
                    }
                }
            }
            true
        }

        // 🔁 Nearby Unhit Code Around Hit Points
        val expandedHitSuggestions =
            hitStats.flatMap { (location, count) ->
                val (filePath, line) = location
                if (count < 1) return@flatMap emptyList()
                val file = File(filePath)
                if (!file.exists()) return@flatMap emptyList()
                val lines = file.readLines()

                (-3..3).mapNotNull { offset ->
                    val targetLine = line + offset
                    if (targetLine < 0 || targetLine >= lines.size || targetLine == line) return@mapNotNull null
                    if ((file.canonicalPath to targetLine) in allBreakpoints) return@mapNotNull null
                    if ((filePath to targetLine) in hitSet) return@mapNotNull null

                    val text = lines[targetLine].trim()
                    if (text.isBlank()) return@mapNotNull null

                    HeuristicSuggestion(
                        filePath = file.canonicalPath,
                        line = targetLine,
                        code = text.take(80),
                        reason = "Unhit nearby line to frequently hit breakpoint",
                    )
                }
            }

        // 🧠 Complex Method with No Breakpoints
        val complexMethodSuggestions = mutableListOf<HeuristicSuggestion>()
        psiFiles.forEach { psiFile ->
            psiFile.accept(
                object : PsiRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        super.visitElement(element)
                        val isFunction =
                            element is PsiMethod || element is PsiNamedElement && element.text.contains("fun ")
                        if (!isFunction) return

                        val bodyRange = element.textRange ?: return
                        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
                        val startLine = document.getLineNumber(bodyRange.startOffset)
                        val endLine = document.getLineNumber(bodyRange.endOffset)
                        val length = endLine - startLine

                        if (length < 10) return
                        val bodyText = element.text.lowercase()
                        val complexity = listOf("if", "else", "while", "for", "try").count { it in bodyText }
                        if (complexity < 2) return

                        val filePath = psiFile.virtualFile.canonicalPath ?: return
                        if ((filePath to startLine) in allBreakpoints) return

                        complexMethodSuggestions.add(
                            HeuristicSuggestion(
                                filePath = filePath,
                                line = startLine,
                                code =
                                    element.text
                                        .lines()
                                        .firstOrNull()
                                        ?.take(80) ?: "[unavailable]",
                                reason = "Complex method with no breakpoints",
                            ),
                        )
                    }
                },
            )
        }

        return suggestions + expandedHitSuggestions + complexMethodSuggestions
    }
}
