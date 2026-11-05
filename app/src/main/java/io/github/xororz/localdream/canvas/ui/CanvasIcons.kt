package io.github.xororz.localdream.canvas.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.xororz.localdream.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.canvas.CanvasTool

object CanvasIcons {
    /** Tilted eraser block, MediBang style. */
    val Eraser: ImageVector by lazy {
        ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14.5f, 3.5f)
                lineTo(20.5f, 9.5f)
                lineTo(10.5f, 19.5f)
                lineTo(4.5f, 13.5f)
                close()
                moveTo(9.5f, 8.5f)
                lineTo(15.5f, 14.5f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4.5f, 13.5f)
                lineTo(9.5f, 8.5f)
                lineTo(15.5f, 14.5f)
                lineTo(10.5f, 19.5f)
                close()
            }
        }.build()
    }

    /** Dashed rectangle for rectangular selection. */
    val SelectRect: ImageVector by lazy {
        ImageVector.Builder("SelectRect", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                val segs = listOf(
                    floatArrayOf(3f, 3f, 7f, 5f), floatArrayOf(9f, 3f, 15f, 5f), floatArrayOf(17f, 3f, 21f, 5f),
                    floatArrayOf(3f, 19f, 7f, 21f), floatArrayOf(9f, 19f, 15f, 21f), floatArrayOf(17f, 19f, 21f, 21f),
                    floatArrayOf(3f, 7f, 5f, 11f), floatArrayOf(3f, 13f, 5f, 17f),
                    floatArrayOf(19f, 7f, 21f, 11f), floatArrayOf(19f, 13f, 21f, 17f),
                )
                segs.forEach { s ->
                    moveTo(s[0], s[1])
                    lineTo(s[2], s[1])
                    lineTo(s[2], s[3])
                    lineTo(s[0], s[3])
                    close()
                }
            }
        }.build()
    }

    fun forTool(tool: CanvasTool): ImageVector = when (tool) {
        CanvasTool.PEN -> Icons.Default.Edit
        CanvasTool.AIRBRUSH -> Icons.Default.Grain
        CanvasTool.ERASER -> Eraser
        CanvasTool.BLUR -> Icons.Default.BlurOn
        CanvasTool.SMUDGE -> Icons.Default.TouchApp
        CanvasTool.INPAINT -> Icons.Default.Healing
        CanvasTool.FILL -> Icons.Default.FormatColorFill
        CanvasTool.EYEDROPPER -> Icons.Default.Colorize
        CanvasTool.SELECT_RECT -> SelectRect
        CanvasTool.SELECT_LASSO -> Icons.Default.Gesture
        CanvasTool.SELECT_WAND -> Icons.Default.AutoFixNormal
        CanvasTool.MOVE -> Icons.Default.OpenWith
        CanvasTool.HAND -> Icons.Default.PanTool
    }

    fun labelRes(tool: CanvasTool): Int = when (tool) {
        CanvasTool.PEN -> R.string.canvas_tool_pen
        CanvasTool.AIRBRUSH -> R.string.canvas_tool_airbrush
        CanvasTool.ERASER -> R.string.canvas_tool_eraser
        CanvasTool.BLUR -> R.string.canvas_tool_blur
        CanvasTool.SMUDGE -> R.string.canvas_tool_smudge
        CanvasTool.INPAINT -> R.string.canvas_tool_inpaint
        CanvasTool.FILL -> R.string.canvas_tool_fill
        CanvasTool.EYEDROPPER -> R.string.canvas_tool_eyedropper
        CanvasTool.SELECT_RECT -> R.string.canvas_tool_select_rect
        CanvasTool.SELECT_LASSO -> R.string.canvas_tool_lasso
        CanvasTool.SELECT_WAND -> R.string.canvas_tool_wand
        CanvasTool.MOVE -> R.string.canvas_tool_move
        CanvasTool.HAND -> R.string.canvas_tool_hand
    }

    @Composable
    fun label(tool: CanvasTool): String = stringResource(labelRes(tool))

    val Undo get() = Icons.AutoMirrored.Filled.Undo
    val Redo get() = Icons.AutoMirrored.Filled.Redo
    val SelectionMenu get() = Icons.Default.HighlightAlt
}
