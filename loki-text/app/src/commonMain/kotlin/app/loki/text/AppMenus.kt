package app.loki.text

import app.loki.kit.*

/**
 * The menu bar, laid out as Mac word processors lay theirs out. About, Settings and Quit live in
 * the application menu, which the system builds.
 */
private val MENU_SPEC: List<Pair<String, List<MenuSpec>>> = listOf(
    "File" to listOf(
        menuCmd("file.new", key = "Mod+N"), menuCmd("file.open", "Open…", key = "Mod+O"), MenuSep,
        menuCmd("file.close", key = "Mod+W"), menuCmd("file.save", key = "Mod+S"), menuCmd("file.saveAs", "Save As…", key = "Mod+Shift+S"),
        menuCmd("file.saveTemplate", "Save as Template…"), MenuSep,
        menuCmd("file.exportPdf", "Export PDF…"), menuCmd("file.exportPng", "Export Page as PNG…"), MenuSep,
        menuCmd("layout.pageSetup", "Page Setup…"), menuCmd("file.print", "Print…", key = "Mod+P"), MenuSep,
        menuCmd("file.properties", "Properties…"), menuCmd("file.info", "Info"),
    ),
    "Edit" to listOf(
        menuCmd("edit.undo"), menuCmd("edit.redo"), MenuSep,
        menuCmd("edit.cut"), menuCmd("edit.copy"), menuCmd("edit.paste"), menuCmd("edit.pasteText", "Paste and Match Style"),
        menuCmd("edit.pasteSpecial", "Paste Special…"), menuCmd("select.all"), MenuSep,
        menuCmd("edit.copyFormat"), menuCmd("edit.pasteFormat"), MenuSep,
        MenuSub(
            "Find",
            listOf(menuCmd("edit.find", "Find…"), menuCmd("edit.findReplace", "Find and Replace…"), menuCmd("edit.findNext"), menuCmd("edit.findPrevious"), MenuSep, menuCmd("edit.goto", "Go To…")),
        ),
        MenuSub("Spelling and Grammar", listOf(menuCmd("review.spelling", "Check Document Now"), menuCheck("review.proofing"))),
    ),
    "View" to listOf(
        menuCheck("view.printLayout"), menuCheck("view.webLayout"), menuCheck("view.outline"), menuCheck("view.draft"), menuCheck("view.readMode"), MenuSep,
        menuCheck("view.ruler"), menuCheck("view.marks", "Formatting Marks"), menuCheck("view.navigationPane"), menuCheck("view.commentsPane"), menuCheck("view.stylesPane"), MenuSep,
        menuCmd("view.zoomIn"), menuCmd("view.zoomOut"), menuCmd("view.zoom100", "Actual Size"), MenuSep,
        MenuSub("Appearance", listOf(menuCheck("app.theme.system", "System"), menuCheck("app.theme.light", "Light"), menuCheck("app.theme.dark", "Dark"))),
        menuCheck("view.darkMode", "Dark Page"),
    ),
    "Insert" to listOf(
        menuCmd("insert.pageBreak"), menuCmd("insert.blankPage"), MenuSep,
        menuCmd("insert.table", "Table…"), menuCmd("insert.picture", "Picture…"), menuCmd("insert.textBox"), menuCmd("insert.equation"), MenuSep,
        menuCmd("insert.link", "Link…"), menuCmd("insert.bookmark", "Bookmark…"), menuCmd("insert.crossReference", "Cross-reference…"), MenuSep,
        menuCmd("review.newComment"), menuCmd("references.footnote"), menuCmd("references.endnote"), MenuSep,
        menuCmd("insert.header"), menuCmd("insert.footer"), menuCmd("insert.pageNumber"), MenuSep,
        menuCmd("insert.dateTime", "Date & Time…"), menuCmd("insert.symbol", "Symbol…"), menuCmd("insert.field", "Field…"),
    ),
    "Format" to listOf(
        menuCmd("format.fontDialog", "Font…"), menuCmd("para.dialog", "Paragraph…"), MenuSep,
        MenuSub(
            "Style",
            listOf(
                menuCheck("format.bold"), menuCheck("format.italic"), menuCheck("format.underline"), menuCheck("format.strikethrough"), MenuSep,
                menuCheck("format.superscript"), menuCheck("format.subscript"),
            ),
        ),
        MenuSub("Alignment", listOf(menuCheck("para.alignLeft"), menuCheck("para.alignCenter"), menuCheck("para.alignRight"), menuCheck("para.justify"))),
        menuCheck("para.bullets"), menuCheck("para.numbering"), MenuSep,
        menuCmd("styles.manage", "Styles…"), menuCmd("format.clear"), MenuSep,
        menuCmd("layout.columns", "Columns…"), menuCmd("layout.margins", "Margins…"),
    ),
    "Tools" to listOf(
        menuCmd("review.spelling", "Spelling and Grammar…"), menuCmd("review.thesaurus"), menuCmd("review.wordCount"), menuCmd("review.language", "Language…"), MenuSep,
        menuCheck("review.trackChanges"), menuCmd("review.acceptAll"), menuCmd("review.rejectAll"), MenuSep,
        menuCmd("tools.autocorrect", "AutoCorrect Options…"), menuCmd("file.protect", "Protect Document…"), menuCmd("file.accessibility"),
    ),
    "Table" to listOf(
        menuCmd("insert.table", "Insert Table…"), menuCmd("table.fromText", "Convert Text to Table…"), MenuSep,
        menuCmd("table.insertRowAbove", "Insert Row Above"), menuCmd("table.insertRowBelow", "Insert Row Below"),
        menuCmd("table.insertColumnLeft", "Insert Column Left"), menuCmd("table.insertColumnRight", "Insert Column Right"), MenuSep,
        menuCmd("table.deleteRow"), menuCmd("table.deleteColumn"), menuCmd("table.deleteTable"), MenuSep,
        menuCmd("table.merge"), menuCmd("table.split", "Split Cells…"), menuCmd("table.splitTable"), MenuSep,
        menuCmd("table.selectTable"), menuCmd("table.sort", "Sort…"), menuCmd("table.formula", "Formula…"), menuCmd("table.toText", "Convert to Text…"),
    ),
    "Window" to listOf(menuCmd(MENU_MINIMIZE, "Minimize", key = "Mod+M"), menuCmd(MENU_ZOOM, "Zoom")),
    "Help" to listOf(menuCmd("app.commands", "Search All Commands…"), menuCmd("app.about", "About Loki Text")),
)

/** The menu bar for [catalog] (the engine's commands by id): this app's layout, built by the kit. */
fun appMenus(catalog: Map<String, CommandInfo>, mac: Boolean): List<BarMenu> = buildMenus(MENU_SPEC, catalog, mac)
