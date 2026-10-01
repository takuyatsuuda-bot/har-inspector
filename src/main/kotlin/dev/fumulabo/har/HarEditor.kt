package dev.fumulabo.har

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private const val MAX_BODY_CHARS = 200_000

class HarEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val root = JPanel(BorderLayout())
    private val status = JBLabel("Loading…")
    private val search = SearchTextField(false)
    private val model = EntryTableModel()
    private val table = JBTable(model)
    private val headers = textArea()
    private val request = textArea()
    private val response = textArea()
    private val timing = textArea()
    private val copyCurl = JButton("Copy as cURL")
    private var all: List<HarEntry> = emptyList()

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoCreateRowSorter = true
        table.setShowGrid(false)
        val widths = intArrayOf(40, 60, 55, 420, 90, 80, 80, 220)
        widths.forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = w }
        table.columnModel.getColumn(COL_STATUS).cellRenderer = StatusRenderer()
        table.columnModel.getColumn(COL_SIZE).cellRenderer = right { formatSize(it as Long) }
        table.columnModel.getColumn(COL_TIME).cellRenderer = right { formatMs(it as Double) }
        table.columnModel.getColumn(COL_WATERFALL).cellRenderer = WaterfallRenderer(model)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }

        search.textEditor.emptyText.text = "Filter: URL, method, status or type"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        copyCurl.isEnabled = false
        copyCurl.addActionListener {
            selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(Curl.of(it))) }
        }

        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(search, BorderLayout.CENTER)
            add(copyCurl, BorderLayout.EAST)
        }
        val tabs = JBTabbedPane().apply {
            addTab("Headers", JBScrollPane(headers))
            addTab("Request", JBScrollPane(request))
            addTab("Response", JBScrollPane(response))
            addTab("Timing", JBScrollPane(timing))
        }
        val splitter = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = tabs
        }
        status.border = JBUI.Borders.empty(4, 8)
        root.add(top, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        root.add(status, BorderLayout.SOUTH)
        load()
    }

    private fun load() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                file.inputStream.reader(StandardCharsets.UTF_8).use { HarParser.parse(it) }
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess {
                    all = it.entries
                    model.range(it.startMillis, it.endMillis)
                    applyFilter()
                }.onFailure {
                    status.text = "Could not read this file as HAR: ${it.message}"
                }
            }
        }
    }

    private fun applyFilter() {
        val words = search.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = if (words.isEmpty()) all else all.filter { e ->
            val hay = "${e.method} ${e.status} ${e.url} ${e.mimeType}".lowercase()
            words.all { it in hay }
        }
        model.set(shown)
        val bytes = shown.sumOf { it.bodySize }
        status.text = "${shown.size} of ${all.size} requests · ${formatSize(bytes)} · " +
            formatMs((model.endMillis - model.startMillis).toDouble())
        showSelected()
    }

    private fun selected(): HarEntry? {
        val row = table.selectedRow
        return if (row < 0) null else model.entryAt(table.convertRowIndexToModel(row))
    }

    private fun showSelected() {
        val e = selected()
        copyCurl.isEnabled = e != null
        if (e == null) {
            listOf(headers, request, response, timing).forEach { it.text = "" }
            return
        }
        headers.text = buildString {
            appendLine("${e.method} ${e.url} ${e.httpVersion}")
            appendLine("Status: ${e.status} ${e.statusText}")
            appendLine()
            appendLine("Request headers")
            e.requestHeaders.forEach { appendLine("  ${it.name}: ${it.value}") }
            appendLine()
            appendLine("Response headers")
            e.responseHeaders.forEach { appendLine("  ${it.name}: ${it.value}") }
        }
        request.text = buildString {
            if (e.query.isNotEmpty()) {
                appendLine("Query")
                e.query.forEach { appendLine("  ${it.name} = ${it.value}") }
                appendLine()
            }
            append(e.requestBody?.let(::clip) ?: "(no request body)")
        }
        response.text = responseText(e)
        timing.text = with(e.timings) {
            listOf(
                "Blocked" to blocked, "DNS" to dns, "Connect" to connect, "TLS" to ssl,
                "Send" to send, "Wait" to wait, "Receive" to receive, "Total" to e.timeMs,
            ).joinToString("\n") { (k, v) -> k.padEnd(10) + formatMs(v) }
        }
        listOf(headers, request, response, timing).forEach { it.caretPosition = 0 }
    }

    private fun responseText(e: HarEntry): String {
        val body = e.responseBody ?: return "(no response body in this file)"
        if (e.responseEncoding != "base64") return clip(body)
        val textual = e.mimeType.startsWith("text/") || "json" in e.mimeType || "xml" in e.mimeType ||
            "javascript" in e.mimeType
        if (!textual) return "(binary, ${formatSize(e.bodySize)}, ${e.mimeType})"
        return runCatching { clip(String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)) }
            .getOrDefault("(could not decode body)")
    }

    private fun clip(s: String) =
        if (s.length <= MAX_BODY_CHARS) s else s.take(MAX_BODY_CHARS) + "\n… (truncated)"

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create("JetBrains Mono", JBUI.Fonts.label().size)
        border = JBUI.Borders.empty(6, 8)
    }

    private fun right(format: (Any) -> String): TableCellRenderer = object : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.RIGHT }
        override fun setValue(value: Any?) { text = value?.let(format) ?: "" }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName(): String = "Requests"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}

internal const val COL_STATUS = 2
internal const val COL_SIZE = 5
internal const val COL_TIME = 6
internal const val COL_WATERFALL = 7

internal class EntryTableModel : AbstractTableModel() {
    private val columns = arrayOf("#", "Method", "Status", "URL", "Type", "Size", "Time", "Waterfall")
    private var rows: List<HarEntry> = emptyList()
    var startMillis = 0L; private set
    var endMillis = 0L; private set

    fun range(start: Long, end: Long) { startMillis = start; endMillis = end }
    fun set(entries: List<HarEntry>) { rows = entries; fireTableDataChanged() }
    fun entryAt(row: Int): HarEntry = rows[row]

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getColumnClass(column: Int): Class<*> = when (column) {
        0, COL_STATUS -> Integer::class.java
        COL_SIZE -> java.lang.Long::class.java
        COL_TIME -> java.lang.Double::class.java
        COL_WATERFALL -> java.lang.Long::class.java
        else -> String::class.java
    }

    override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
        when (column) {
            0 -> it.index
            1 -> it.method
            COL_STATUS -> it.status
            3 -> it.url
            4 -> it.shortType
            COL_SIZE -> it.bodySize
            COL_TIME -> it.timeMs
            else -> it.startedMillis
        }
    }
}

private class StatusRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val code = value as? Int ?: 0
        text = if (code == 0) "—" else code.toString()
        if (!isSelected) foreground = when {
            code >= 500 || code == 0 -> JBColor.RED
            code >= 400 -> JBColor.ORANGE
            else -> table.foreground
        }
        return c
    }
}

private class WaterfallRenderer(private val model: EntryTableModel) : JComponent(), TableCellRenderer {
    private var from = 0.0
    private var width = 0.0
    private var selected = false
    private val bar = JBColor(0x4A88C7, 0x548AF7)

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val e = model.entryAt(table.convertRowIndexToModel(row))
        val span = (model.endMillis - model.startMillis).coerceAtLeast(1L).toDouble()
        from = (e.startedMillis - model.startMillis) / span
        width = e.timeMs / span
        selected = isSelected
        background = if (isSelected) table.selectionBackground else table.background
        return this
    }

    override fun paintComponent(g: Graphics) {
        g.color = background
        g.fillRect(0, 0, getWidth(), height)
        val pad = JBUI.scale(4)
        val usable = (getWidth() - pad * 2).coerceAtLeast(1)
        val x = pad + (from.coerceIn(0.0, 1.0) * usable).toInt()
        val w = (width * usable).toInt().coerceIn(JBUI.scale(2), (pad + usable - x).coerceAtLeast(JBUI.scale(2)))
        g.color = bar
        g.fillRect(x, height / 2 - JBUI.scale(3), w, JBUI.scale(6))
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatMs(ms: Double): String =
    if (ms >= 1000) "%.2f s".format(ms / 1000) else "%.0f ms".format(ms)
