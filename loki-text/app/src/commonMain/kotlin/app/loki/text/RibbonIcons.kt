package app.loki.text

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AArrowDown
import com.composables.icons.lucide.AArrowUp
import com.composables.icons.lucide.ALargeSmall
import com.composables.icons.lucide.Accessibility
import com.composables.icons.lucide.AppWindow
import com.composables.icons.lucide.Bold
import com.composables.icons.lucide.BookA
import com.composables.icons.lucide.BookMarked
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.BringToFront
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronsLeft
import com.composables.icons.lucide.ChevronsRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Columns2
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Copyright
import com.composables.icons.lucide.Droplet
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileCog
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Focus
import com.composables.icons.lucide.Frame
import com.composables.icons.lucide.GitCompare
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Grid3x3
import com.composables.icons.lucide.Group
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Highlighter
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Italic
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.LassoSelect
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.List
import com.composables.icons.lucide.ListIndentDecrease
import com.composables.icons.lucide.ListIndentIncrease
import com.composables.icons.lucide.ListOrdered
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.MousePointer2
import com.composables.icons.lucide.MoveHorizontal
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.PanelBottom
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.PanelTop
import com.composables.icons.lucide.Pen
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.PencilLine
import com.composables.icons.lucide.Pilcrow
import com.composables.icons.lucide.Printer
import com.composables.icons.lucide.Quote
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Replace
import com.composables.icons.lucide.Ruler
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Scissors
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.SendToBack
import com.composables.icons.lucide.SeparatorHorizontal
import com.composables.icons.lucide.Sigma
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.SpellCheck
import com.composables.icons.lucide.Split
import com.composables.icons.lucide.SquareCheck
import com.composables.icons.lucide.Strikethrough
import com.composables.icons.lucide.Subscript
import com.composables.icons.lucide.Superscript
import com.composables.icons.lucide.Table
import com.composables.icons.lucide.TableOfContents
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.TextAlignCenter
import com.composables.icons.lucide.TextAlignEnd
import com.composables.icons.lucide.TextAlignJustify
import com.composables.icons.lucide.TextAlignStart
import com.composables.icons.lucide.TextCursorInput
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Underline
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.Ungroup
import com.composables.icons.lucide.Unlink
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.X
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut

/*
 * Ribbon icons are Lucide (https://lucide.dev, ISC licence) through the `icons-lucide-cmp`
 * Compose Multiplatform package; see the README's credits. A command without an entry here has no
 * icon and appears by name in its group's menu.
 */

private fun iconFor(id: String): ImageVector? = when (id) {
    "file.open" -> Lucide.FolderOpen
    "file.saveAs" -> Lucide.Save
    "edit.paste" -> Lucide.ClipboardPaste
    "edit.cut" -> Lucide.Scissors
    "edit.copy" -> Lucide.Copy
    "edit.findReplace" -> Lucide.Replace
    "edit.undo" -> Lucide.Undo2
    "edit.redo" -> Lucide.Redo2
    "format.bold" -> Lucide.Bold
    "format.italic" -> Lucide.Italic
    "format.underline" -> Lucide.Underline
    "format.strikethrough" -> Lucide.Strikethrough
    "format.subscript" -> Lucide.Subscript
    "format.superscript" -> Lucide.Superscript
    "format.growFont" -> Lucide.AArrowUp
    "format.shrinkFont" -> Lucide.AArrowDown
    "para.bullets" -> Lucide.List
    "para.numbering" -> Lucide.ListOrdered
    "para.outdent" -> Lucide.ListIndentDecrease
    "para.indent" -> Lucide.ListIndentIncrease
    "para.alignLeft" -> Lucide.TextAlignStart
    "para.alignCenter" -> Lucide.TextAlignCenter
    "para.alignRight" -> Lucide.TextAlignEnd
    "para.justify" -> Lucide.TextAlignJustify
    "view.marks" -> Lucide.Pilcrow
    "insert.pageBreak" -> Lucide.SeparatorHorizontal
    "text.pageBreak" -> Lucide.SeparatorHorizontal
    "insert.blankPage" -> Lucide.File
    "insert.coverPage" -> Lucide.FileText
    "insert.picture" -> Lucide.Image
    "insert.textBox" -> Lucide.TextCursorInput
    "insert.dateTime" -> Lucide.CalendarClock
    "insert.pageNumber" -> Lucide.Hash
    "insert.equation" -> Lucide.Sigma
    "insert.header" -> Lucide.PanelTop
    "insert.footer" -> Lucide.PanelBottom
    "link.targets" -> Lucide.Link
    "insert.removeLink" -> Lucide.Unlink
    "table.quick" -> Lucide.Table
    "table.fromText" -> Lucide.Table
    "insert.dropCap" -> Lucide.Type
    "text.copyright" -> Lucide.Copyright
    "layout.margins" -> Lucide.Frame
    "layout.size" -> Lucide.Ruler
    "text.columnBreak" -> Lucide.Columns2
    "layout.lineNumbers" -> Lucide.ListOrdered
    "layout.pageSetup" -> Lucide.FileCog
    "arrange.bringForward" -> Lucide.BringToFront
    "arrange.sendBackward" -> Lucide.SendToBack
    "arrange.group" -> Lucide.Group
    "arrange.ungroup" -> Lucide.Ungroup
    "view.printLayout" -> Lucide.Printer
    "view.webLayout" -> Lucide.Globe
    "view.draft" -> Lucide.FileText
    "view.outline" -> Lucide.ListTree
    "view.readMode" -> Lucide.BookOpen
    "view.ruler" -> Lucide.Ruler
    "view.gridlines" -> Lucide.Grid3x3
    "view.navigationPane" -> Lucide.PanelLeft
    "view.zoomIn" -> Lucide.ZoomIn
    "view.zoomOut" -> Lucide.ZoomOut
    "view.pageWidth" -> Lucide.MoveHorizontal
    "view.fullScreen" -> Lucide.Maximize
    "view.focus" -> Lucide.Focus
    "view.darkMode" -> Lucide.Moon
    "view.newWindow" -> Lucide.AppWindow
    "view.split" -> Lucide.Split
    "review.trackChanges" -> Lucide.PencilLine
    "review.acceptAll" -> Lucide.CheckCheck
    "review.rejectAll" -> Lucide.X
    "review.accept" -> Lucide.Check
    "review.reject" -> Lucide.X
    "review.nextChange" -> Lucide.ChevronRight
    "review.previousChange" -> Lucide.ChevronLeft
    "review.wordCount" -> Lucide.ALargeSmall
    "review.spelling" -> Lucide.SpellCheck
    "review.thesaurus" -> Lucide.BookA
    "review.comments" -> Lucide.MessageSquare
    "review.nextComment" -> Lucide.ChevronRight
    "review.previousComment" -> Lucide.ChevronLeft
    "review.resolveComment" -> Lucide.CircleCheck
    "review.deleteComment" -> Lucide.Trash2
    "review.compare" -> Lucide.GitCompare
    "review.language" -> Lucide.Languages
    "review.restrict" -> Lucide.Lock
    "review.readAloud" -> Lucide.Volume2
    "file.accessibility" -> Lucide.Accessibility
    "references.toc" -> Lucide.TableOfContents
    "references.caption" -> Lucide.Captions
    "references.citation" -> Lucide.Quote
    "references.bibliography" -> Lucide.Library
    "references.updateToc" -> Lucide.RefreshCw
    "references.updateFields" -> Lucide.RefreshCw
    "references.updateIndex" -> Lucide.RefreshCw
    "references.sources" -> Lucide.BookMarked
    "references.markEntry" -> Lucide.Tag
    "references.index" -> Lucide.ListTree
    "design.themes" -> Lucide.Palette
    "design.themeColors" -> Lucide.Palette
    "design.watermark" -> Lucide.Droplet
    "design.effects" -> Lucide.Sparkles
    "mailings.editRecipients" -> Lucide.Users
    "mailings.preview" -> Lucide.Eye
    "mailings.next" -> Lucide.ChevronRight
    "mailings.previous" -> Lucide.ChevronLeft
    "mailings.first" -> Lucide.ChevronsLeft
    "mailings.last" -> Lucide.ChevronsRight
    "mailings.finish" -> Lucide.Send
    "mailings.printDocuments" -> Lucide.Printer
    "mailings.checkErrors" -> Lucide.CircleAlert
    "draw.pen" -> Lucide.Pen
    "draw.pencil" -> Lucide.Pencil
    "draw.highlighter" -> Lucide.Highlighter
    "draw.eraser" -> Lucide.Eraser
    "draw.select" -> Lucide.MousePointer2
    "draw.lasso" -> Lucide.LassoSelect
    "developer.checkBox" -> Lucide.SquareCheck
    "developer.datePicker" -> Lucide.Calendar
    "developer.richText" -> Lucide.Type
    "developer.plainText" -> Lucide.TextCursorInput
    "developer.picture" -> Lucide.Image
    else -> null
}

/** Does [id] have an icon (and so appear as a button rather than only in the group menu)? */
fun hasRibbonIcon(id: String): Boolean = iconFor(id) != null

@Composable
fun RibbonIcon(id: String) {
    val icon = iconFor(id) ?: return
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
}
