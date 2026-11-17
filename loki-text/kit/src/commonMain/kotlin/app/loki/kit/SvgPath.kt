package app.loki.kit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/**
 * SVG path data (absolute M, L, H, V, C, S, Q, T and Z, as an engine writes it) as a Compose
 * [Path], each point mapped through [map] (document to screen). Anything it does not read ends the
 * path there: an engine's answer is never trusted to be well formed. At most [maxPoints] points.
 */
fun svgPath(d: String, map: (Double, Double) -> Offset, maxPoints: Int = 200_000): Path {
    val out = Path()
    val tokens = Regex("[MLHVCSQTZmlhvcsqtz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
    var i = 0
    var cmd = 'M'
    var cx = 0.0; var cy = 0.0
    var sx = 0.0; var sy = 0.0
    var lastCtl: Pair<Double, Double>? = null
    var points = 0
    fun num(): Double? = tokens.getOrNull(i)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.also { i++ }
    fun pt(x: Double, y: Double): Offset { points++; return map(x, y) }
    while (i < tokens.size && points < maxPoints) {
        val t = tokens[i]
        if (t.length == 1 && t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z' || cmd == 'z') { out.close(); cx = sx; cy = sy; lastCtl = null }; continue }
        when (cmd) {
            'M' -> { val x = num() ?: break; val y = num() ?: break; val p = pt(x, y); out.moveTo(p.x, p.y); cx = x; cy = y; sx = x; sy = y; cmd = 'L'; lastCtl = null }
            'L' -> { val x = num() ?: break; val y = num() ?: break; val p = pt(x, y); out.lineTo(p.x, p.y); cx = x; cy = y; lastCtl = null }
            'H' -> { val x = num() ?: break; val p = pt(x, cy); out.lineTo(p.x, p.y); cx = x; lastCtl = null }
            'V' -> { val y = num() ?: break; val p = pt(cx, y); out.lineTo(p.x, p.y); cy = y; lastCtl = null }
            'C' -> {
                val x1 = num() ?: break; val y1 = num() ?: break; val x2 = num() ?: break; val y2 = num() ?: break; val x = num() ?: break; val y = num() ?: break
                val a = pt(x1, y1); val b = pt(x2, y2); val c = pt(x, y); out.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y); cx = x; cy = y; lastCtl = x2 to y2
            }
            'S' -> {
                val x2 = num() ?: break; val y2 = num() ?: break; val x = num() ?: break; val y = num() ?: break
                val (x1, y1) = lastCtl?.let { (lx, ly) -> 2 * cx - lx to 2 * cy - ly } ?: (cx to cy)
                val a = pt(x1, y1); val b = pt(x2, y2); val c = pt(x, y); out.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y); cx = x; cy = y; lastCtl = x2 to y2
            }
            'Q' -> {
                val x1 = num() ?: break; val y1 = num() ?: break; val x = num() ?: break; val y = num() ?: break
                val a = pt(x1, y1); val c = pt(x, y); out.quadraticTo(a.x, a.y, c.x, c.y); cx = x; cy = y; lastCtl = x1 to y1
            }
            'T' -> {
                val x = num() ?: break; val y = num() ?: break
                val (x1, y1) = lastCtl?.let { (lx, ly) -> 2 * cx - lx to 2 * cy - ly } ?: (cx to cy)
                val a = pt(x1, y1); val c = pt(x, y); out.quadraticTo(a.x, a.y, c.x, c.y); cx = x; cy = y; lastCtl = x1 to y1
            }
            else -> break // relative commands and arcs: engines write absolute paths; stop rather than guess
        }
    }
    return out
}
