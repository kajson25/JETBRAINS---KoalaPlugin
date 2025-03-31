# Koala: IntelliJ Breakpoint Tracker Plugin

**Koala** is an intelligent IntelliJ IDEA plugin that tracks, analyzes, and visualizes breakpoints during your debug sessions — and goes beyond with smart breakpoint suggestions based on your actual code and runtime behavior.

---

## Features

### Real-Time Breakpoint Tracker
- Lists all current breakpoints across your project
- Clickable links to jump directly to file & line
- Automatically updates on add/remove/change

### Execution Trace View
- Displays the exact order in which breakpoints were hit during the last debug session
- Highlights frequently hit breakpoints with 🔥
- Distinguishes hit vs unhit breakpoints (green vs red)

### Breakpoint Diagram (Map View)
- Visual timeline of breakpoint flow
- Each node represents a unique code location
- Arrows show the actual runtime order
- Beautiful styling and clickable code snippets

### Heuristic Suggestions
Intelligent recommendations for potential new breakpoints:
- Print/log lines without breakpoints
- Code near frequently hit breakpoints (but never executed)
- Long, complex methods with no existing breakpoints

### Interactive ToolWindow UI
- Toggle between views: list, diagram, suggestions
- Search/filter support
- Smooth UX via JCEF browser engine

---

## Installation

1. Clone this repo or open it directly in IntelliJ IDEA
2. Run the **Gradle Plugin task**:
   ```bash
   ./gradlew runIde
