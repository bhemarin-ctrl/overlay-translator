package com.gameocr.app.overlay

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gameocr.app.R
import com.gameocr.app.gallery.GalleryRect
import com.gameocr.app.ui.PageEditorCore
import com.gameocr.app.ui.theme.GameOcrTheme
import com.gameocr.app.ui.theme.ThemeMode
import com.gameocr.app.util.physicalDisplaySize
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Editor transparente sobreposto ao leitor. O screenshot só serve para traduzir: aqui são
 * desenhados apenas os balões traduzidos, exatamente nas posições originais, e cada um pode
 * ser editado (texto, caixa, fonte, espaçamento, horizontal/vertical) sem sair do leitor.
 */
class PageEditOverlay(
    private val context: Context,
    private val core: PageEditorCore,
    private val onOpenFullEditor: (itemId: String?) -> Unit,
) {
    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val owner = OverlayComposeOwner()

    private var canvasView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var textDialog: Dialog? = null
    private val editing = mutableStateOf(true)
    private val dockTop = mutableStateOf(false)
    private val collapsed = mutableStateOf(false)

    val isShowing: Boolean get() = canvasView != null
    val isEditing: Boolean get() = isShowing && editing.value

    private val overlayType: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun show(itemId: String?) {
        if (isShowing) {
            startEditing()
            return
        }
        if (itemId != null) core.open(itemId) else core.openLastReadingPage()
        owner.resume()
        editing.value = true
        addCanvas()
        addPanel()
    }

    /** Volta a permitir edição (reabre o painel) sem recarregar a página. */
    fun startEditing() {
        if (!isShowing) return
        editing.value = true
        setCanvasTouchable(true)
        if (panelView == null) addPanel()
    }

    /** Fecha o painel e deixa os balões traduzidos visíveis, sem capturar toques. */
    fun enterViewMode() {
        if (!isShowing) return
        core.saveNow()
        core.select(-1)
        editing.value = false
        removePanel()
        setCanvasTouchable(false)
    }

    fun dismiss() {
        if (!isShowing && panelView == null) return
        core.saveNow()
        textDialog?.dismiss()
        textDialog = null
        removePanel()
        canvasView?.let { view -> runCatching { wm.removeView(view) } }
        canvasView = null
        owner.destroy()
    }

    private fun addCanvas() {
        val size = physicalDisplaySize(context)
        val view = ComposeView(context).apply {
            attachOwners(this)
            setContent {
                GameOcrTheme(themeMode = ThemeMode.LIGHT) {
                    EditCanvasLayer(core = core, editing = editing.value)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            size.width,
            size.height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        runCatching { wm.addView(view, params) }
            .onSuccess { canvasView = view }
            .onFailure { Timber.w(it, "PageEditOverlay canvas could not be added") }
    }

    private fun setCanvasTouchable(touchable: Boolean) {
        val view = canvasView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun addPanel() {
        if (panelView != null) return
        val view = ComposeView(context).apply {
            attachOwners(this)
            setContent {
                GameOcrTheme(themeMode = ThemeMode.LIGHT) {
                    EditPanel(
                        core = core,
                        collapsed = collapsed.value,
                        dockTop = dockTop.value,
                        onToggleCollapsed = { collapsed.value = !collapsed.value },
                        onToggleDock = {
                            dockTop.value = !dockTop.value
                            applyPanelGravity()
                        },
                        onEditText = ::showTextDialog,
                        onFinish = ::enterViewMode,
                        onOpenFull = { onOpenFullEditor(core.item?.id) },
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (dockTop.value) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
        }
        runCatching { wm.addView(view, params) }
            .onSuccess { panelView = view }
            .onFailure { Timber.w(it, "PageEditOverlay panel could not be added") }
    }

    private fun applyPanelGravity() {
        val view = panelView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.gravity = (if (dockTop.value) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun removePanel() {
        panelView?.let { view -> runCatching { wm.removeView(view) } }
        panelView = null
    }

    private fun attachOwners(view: ComposeView) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun showTextDialog() {
        val seg = core.segments.getOrNull(core.selected) ?: return
        textDialog?.dismiss()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val input = EditText(context).apply {
            setText(seg.translatedText)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF111111.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val dialog = Dialog(context, R.style.Theme_GameOcr_Transparent)
        val ok = Button(context).apply {
            text = "OK"
            setOnClickListener {
                val value = input.text.toString()
                core.updateSelected { it.copy(translatedText = value) }
                dialog.dismiss()
            }
        }
        val cancel = Button(context).apply {
            text = "Cancelar"
            setOnClickListener { dialog.dismiss() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancel)
            addView(ok)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFF000000.toInt())
            }
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(buttons, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val root = android.widget.FrameLayout(context).apply {
            setPadding(dp(16), dp(48), dp(16), dp(16))
            addView(column, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.let { window ->
            window.setType(overlayType)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
        dialog.setOnDismissListener { if (textDialog === dialog) textDialog = null }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        textDialog = dialog
        input.requestFocus()
    }
}

/** LifecycleOwner + SavedStateRegistryOwner mínimos para hospedar Compose numa janela de serviço. */
private class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

private enum class OverlayDrag { NONE, MOVE, TL, TR, BL, BR }

@Composable
private fun EditCanvasLayer(core: PageEditorCore, editing: Boolean) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf(OverlayDrag.NONE) }
    val handleRadiusPx = 48f

    fun sx(): Float = if (core.coordWidth > 0) viewSize.width / core.coordWidth.toFloat() else 1f
    fun sy(): Float = if (core.coordHeight > 0) viewSize.height / core.coordHeight.toFloat() else 1f
    fun toViewRect(box: GalleryRect): androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect(
        box.left * sx(), box.top * sy(), box.right * sx(), box.bottom * sy(),
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(editing) {
                if (!editing) return@pointerInput
                detectTapGestures { tap ->
                    val px = tap.x / sx()
                    val py = tap.y / sy()
                    val hit = core.segments.withIndex()
                        .filter { (_, seg) ->
                            val b = core.effectiveBox(seg)
                            px >= b.left && px <= b.right && py >= b.top && py <= b.bottom
                        }
                        .minByOrNull { (_, seg) ->
                            val b = core.effectiveBox(seg)
                            (b.right - b.left).toLong() * (b.bottom - b.top)
                        }
                    core.select(hit?.index ?: -1)
                }
            }
            .pointerInput(editing) {
                if (!editing) return@pointerInput
                detectDragGestures(
                    onDragStart = { start ->
                        val seg = core.segments.getOrNull(core.selected)
                        drag = OverlayDrag.NONE
                        if (seg != null) {
                            val r = toViewRect(core.effectiveBox(seg))
                            fun near(c: Offset) = (c - start).getDistance() <= handleRadiusPx
                            drag = when {
                                near(r.topLeft) -> OverlayDrag.TL
                                near(r.topRight) -> OverlayDrag.TR
                                near(r.bottomLeft) -> OverlayDrag.BL
                                near(r.bottomRight) -> OverlayDrag.BR
                                start.x in r.left..r.right && start.y in r.top..r.bottom -> OverlayDrag.MOVE
                                else -> OverlayDrag.NONE
                            }
                        }
                    },
                    onDragEnd = { drag = OverlayDrag.NONE },
                    onDragCancel = { drag = OverlayDrag.NONE },
                    onDrag = { change, amount ->
                        if (drag == OverlayDrag.NONE) return@detectDragGestures
                        change.consume()
                        val seg = core.segments.getOrNull(core.selected) ?: return@detectDragGestures
                        val box = core.effectiveBox(seg)
                        val dx = (amount.x / sx()).roundToInt()
                        val dy = (amount.y / sy()).roundToInt()
                        var l = box.left
                        var t = box.top
                        var r = box.right
                        var b = box.bottom
                        when (drag) {
                            OverlayDrag.MOVE -> { l += dx; r += dx; t += dy; b += dy }
                            OverlayDrag.TL -> { l += dx; t += dy }
                            OverlayDrag.TR -> { r += dx; t += dy }
                            OverlayDrag.BL -> { l += dx; b += dy }
                            OverlayDrag.BR -> { r += dx; b += dy }
                            OverlayDrag.NONE -> Unit
                        }
                        val minSize = 16
                        if (drag == OverlayDrag.MOVE) {
                            val w = r - l
                            val h = b - t
                            l = l.coerceIn(0, max(0, core.coordWidth - w))
                            t = t.coerceIn(0, max(0, core.coordHeight - h))
                            r = l + w
                            b = t + h
                        } else {
                            l = l.coerceIn(0, core.coordWidth - minSize)
                            t = t.coerceIn(0, core.coordHeight - minSize)
                            r = r.coerceIn(l + minSize, core.coordWidth)
                            b = b.coerceIn(t + minSize, core.coordHeight)
                        }
                        core.setBox(GalleryRect(l, t, r, b))
                    },
                )
            },
    ) {
        val bitmap = core.rendered
        if (bitmap != null && viewSize.width > 0) {
            // Só as regiões alteradas (texto original apagado + caixa da tradução) são desenhadas;
            // o resto fica transparente e o leitor continua visível por baixo.
            val clip = Path()
            core.segments.forEach { seg ->
                if (seg.keepOriginal || seg.translatedText.isBlank()) return@forEach
                val boxes = seg.sourceBoxes.ifEmpty { listOf(seg.boundingBox) } + core.effectiveBox(seg)
                boxes.forEach { box ->
                    val margin = 14
                    clip.addRect(
                        androidx.compose.ui.geometry.Rect(
                            (box.left - margin) * sx(),
                            (box.top - margin) * sy(),
                            (box.right + margin) * sx(),
                            (box.bottom + margin) * sy(),
                        )
                    )
                }
            }
            clipPath(clip) {
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstSize = IntSize(viewSize.width, viewSize.height),
                )
            }
        }
        if (!editing) return@Canvas
        core.segments.forEachIndexed { index, seg ->
            val r = toViewRect(core.effectiveBox(seg))
            val isSelected = index == core.selected
            drawRect(
                color = if (isSelected) Color(0xFFD50000) else Color(0x99000000),
                topLeft = r.topLeft,
                size = Size(r.width, r.height),
                style = Stroke(
                    width = if (isSelected) 4f else 1.5f,
                    pathEffect = if (isSelected) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                ),
            )
            if (isSelected) {
                listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { c ->
                    drawCircle(color = Color(0xFFD50000), radius = 16f, center = c)
                    drawCircle(color = Color.White, radius = 8f, center = c)
                }
            }
        }
    }
}

@Composable
private fun EditPanel(
    core: PageEditorCore,
    collapsed: Boolean,
    dockTop: Boolean,
    onToggleCollapsed: () -> Unit,
    onToggleDock: () -> Unit,
    onEditText: () -> Unit,
    onFinish: () -> Unit,
    onOpenFull: () -> Unit,
) {
    val compact = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                M3Button(onClick = onFinish, contentPadding = compact) { Text("Concluir", fontSize = 13.sp) }
                OutlinedButton(onClick = onToggleCollapsed, contentPadding = compact) {
                    Text(if (collapsed) "Expandir" else "Recolher", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onToggleDock, contentPadding = compact) {
                    Text(if (dockTop) "Painel ↓" else "Painel ↑", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { core.addBubble() }, contentPadding = compact) {
                    Text("+ Balão", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { core.exportCurrentPage() }, enabled = !core.busy, contentPadding = compact) {
                    Text("Exportar página", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { core.exportAllPages() }, enabled = !core.busy, contentPadding = compact) {
                    Text("Exportar todas", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onOpenFull, contentPadding = compact) {
                    Text("Editor completo", fontSize = 13.sp)
                }
            }
            core.message?.let { text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = { core.message = null }) { Text("OK") }
                }
            }
            if (core.busy) Text("Processando…", fontSize = 12.sp)
            if (collapsed) return@Column
            val seg = core.segments.getOrNull(core.selected)
            if (seg == null) {
                Text(
                    "Toque num balão (contorno tracejado) para editar. Arraste o corpo para mover e os cantos para redimensionar.",
                    fontSize = 12.sp,
                )
                return@Column
            }
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (seg.sourceText.isNotBlank()) {
                    Text("Original: ${seg.sourceText}", fontSize = 11.sp, maxLines = 2)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        seg.translatedText.ifBlank { "(vazio)" },
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 3,
                    )
                    M3Button(onClick = onEditText, contentPadding = compact) { Text("Editar texto", fontSize = 13.sp) }
                }
                val vertical = (seg.outputOrientation ?: seg.layoutOrientation)?.startsWith("VERTICAL") == true
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !vertical,
                        onClick = { core.updateSelected { it.copy(outputOrientation = "HORIZONTAL_LTR") } },
                        label = { Text("Horizontal", fontSize = 13.sp) },
                    )
                    FilterChip(
                        selected = vertical,
                        onClick = { core.updateSelected { it.copy(outputOrientation = "VERTICAL_RTL") } },
                        label = { Text("Vertical", fontSize = 13.sp) },
                    )
                    FilterChip(
                        selected = seg.keepOriginal,
                        onClick = { core.updateSelected { it.copy(keepOriginal = !it.keepOriginal) } },
                        label = { Text("Manter original", fontSize = 13.sp) },
                    )
                    OutlinedButton(
                        onClick = { core.updateSelected { it.copy(renderBox = null) } },
                        enabled = seg.renderBox != null,
                        contentPadding = compact,
                    ) { Text("Caixa automática", fontSize = 13.sp) }
                    OutlinedButton(onClick = { core.deleteSelected() }, contentPadding = compact) {
                        Text("Excluir balão", fontSize = 13.sp)
                    }
                }
                LabeledSlider(
                    label = "Fonte ×${"%.1f".format(Locale.ROOT, seg.fontScale)}",
                    value = seg.fontScale,
                    range = 0.4f..2.0f,
                ) { v -> core.updateSelected { it.copy(fontScale = (v * 10).roundToInt() / 10f) } }
                val letter = seg.letterSpacingEm ?: 0f
                LabeledSlider(
                    label = "Letras ${"%+.2f".format(Locale.ROOT, letter)}",
                    value = letter,
                    range = -0.1f..0.5f,
                ) { v -> core.updateSelected { it.copy(letterSpacingEm = (v * 100).roundToInt() / 100f) } }
                val line = seg.lineSpacing ?: 1f
                LabeledSlider(
                    label = "Linhas ×${"%.2f".format(Locale.ROOT, line)}",
                    value = line,
                    range = 0.7f..2.0f,
                ) { v -> core.updateSelected { it.copy(lineSpacing = (v * 20).roundToInt() / 20f) } }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(end = 2.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}
