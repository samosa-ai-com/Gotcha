package com.gotcha.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gotcha.R
import com.gotcha.data.MemoryStore
import com.gotcha.tools.FileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Second Brain viewer: read-only view of Gotcha's persistent memory
 * (issue #156) so the user never needs a file explorer to see it.
 *
 * Two views: a list of sections with in-place Markdown previews, and a node
 * graph drawn from the [[links]] between files. Non-text files (audio,
 * images, documents) show file info with an Open button instead of a preview.
 * Editing stays in the agent and the file system for now.
 */
@Composable
fun SecondBrainScreen(onBack: () -> Unit) {
    val overlay = rememberSettingsOverlayState()
    var snapshot by remember { mutableStateOf<MemoryStore.SecondBrainSnapshot?>(null) }
    var showGraph by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { MemoryStore.loadSnapshot() }
    }

    SettingsScaffold(
        title = stringResource(SettingsPage.SECOND_BRAIN.title),
        onBack = onBack,
        overlay = overlay
    ) {
        Text(
            stringResource(R.string.second_brain_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_second_brain_intro")
        )

        val current = snapshot
        if (current == null) {
            Text(
                stringResource(R.string.second_brain_loading),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("settings_second_brain_loading")
            )
            return@SettingsScaffold
        }
        if (!current.available) {
            Text(
                stringResource(R.string.second_brain_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("settings_second_brain_unavailable")
            )
            return@SettingsScaffold
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!showGraph) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("settings_second_brain_tab_list")
                ) { Text(stringResource(R.string.second_brain_tab_list)) }
                OutlinedButton(
                    onClick = { showGraph = true },
                    modifier = Modifier.testTag("settings_second_brain_tab_graph")
                ) { Text(stringResource(R.string.second_brain_tab_graph)) }
            } else {
                OutlinedButton(
                    onClick = { showGraph = false },
                    modifier = Modifier.testTag("settings_second_brain_tab_list")
                ) { Text(stringResource(R.string.second_brain_tab_list)) }
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("settings_second_brain_tab_graph")
                ) { Text(stringResource(R.string.second_brain_tab_graph)) }
            }
        }

        if (showGraph) {
            SecondBrainGraphView(snapshot = current)
        } else {
            current.soul?.let {
                BrainSection(
                    heading = stringResource(R.string.second_brain_identity),
                    file = it,
                    tag = "identity"
                )
            }
            current.memoryIndex?.let {
                BrainSection(
                    heading = stringResource(R.string.second_brain_map),
                    file = it,
                    tag = "map"
                )
            }
            current.userCard?.let {
                BrainSection(
                    heading = stringResource(R.string.second_brain_user_card),
                    file = it,
                    tag = "user"
                )
            }
            BrainFileList(
                heading = stringResource(R.string.second_brain_areas),
                files = current.areas,
                tag = "areas"
            )
            BrainFileList(
                heading = stringResource(R.string.second_brain_people),
                files = current.people,
                tag = "people"
            )
            BrainFileList(
                heading = stringResource(R.string.second_brain_notes),
                files = current.notes,
                tag = "notes"
            )
            BrainFileList(
                heading = stringResource(R.string.second_brain_raw),
                files = current.raw,
                tag = "raw"
            )
        }
    }
}

/**
 * Neuron-style brain graph: nodes render as shaded spheres at varying depths
 * across two hemispheres, links as curved synapses. Nodes drift gently so the
 * structure feels alive. Tap a neuron to preview its file below.
 */
@Composable
private fun SecondBrainGraphView(snapshot: MemoryStore.SecondBrainSnapshot) {
    val graph = remember(snapshot) { MemoryStore.buildGraph(snapshot) }
    var selectedId by remember(graph) { mutableStateOf<String?>(null) }
    var zoom by remember(graph) { mutableStateOf(1f) }
    // Starting view is a three-quarter turn: cerebellum and brainstem sit at
    // the right end, length reading horizontally like a side-view diagram.
    var yawDeg by remember(graph) { mutableStateOf(-55f) }
    var pitchDeg by remember(graph) { mutableStateOf(20f) }
    var pan by remember(graph) { mutableStateOf(Offset.Zero) }
    var rotationDeg by remember(graph) { mutableStateOf(0f) }
    // True while a double-tap-drag is spinning the globe; the transform
    // detector ignores slide input meanwhile so one motion never does both.
    val yawLock = remember(graph) { mutableStateOf(false) }
    val placed = remember(graph) {
        val indexById = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val pairs = graph.edges.mapNotNull { edge ->
            val a = indexById[edge.fromId]
            val b = indexById[edge.toId]
            if (a != null && b != null) a to b else null
        }
        val points = MemoryStore.forceLayout(graph.nodes.size, pairs)
        val depths = List(graph.nodes.size) { nodeZ(it) }
        MemoryStore.confineToBrain(points, depths)
    }
    val layout: List<Offset> = placed.first
    val layoutZ: List<Float> = placed.second
    val degree = remember(graph) {
        graph.nodes.associate { node ->
            node.id to graph.edges.count { it.fromId == node.id || it.toId == node.id }
        }
    }
    // Last moment (ms) the user touched the graph. The brain auto-rotates
    // only after 4 idle seconds; any gesture marks interaction and pauses it.
    var lastTouchMs by remember(graph) { mutableStateOf(0L) }
    fun markTouched() {
        lastTouchMs = System.currentTimeMillis()
    }
    LaunchedEffect(graph) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dtSec = (now - last) / 1_000_000_000f
            last = now
            if (System.currentTimeMillis() - lastTouchMs > 4000L) {
                yawDeg = (yawDeg + dtSec * 9f) % 360f
            }
        }
    }
    // 1-hop neighborhood of the focused neuron, or everything when unfocused.
    val visible = remember(graph, selectedId) {
        if (selectedId == null) {
            graph.nodes.map { it.id }.toSet()
        } else {
            buildSet {
                add(selectedId!!)
                graph.edges.forEach { edge ->
                    if (edge.fromId == selectedId) add(edge.toId)
                    if (edge.toId == selectedId) add(edge.fromId)
                }
            }
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val nodeFill = remember(scheme) {
        mapOf(
            "soul" to scheme.primary,
            "map" to scheme.secondary,
            "user" to scheme.tertiary,
            "area" to scheme.primaryContainer,
            "person" to scheme.secondaryContainer,
            "note" to scheme.surfaceVariant,
            "raw" to scheme.errorContainer
        )
    }

    // Viewport math shared by drawing and tap detection. Single-finger drags
    // slide the brain, two fingers pinch-zoom and rotate it on screen, and a
    // double-tap-drag spins it around the vertical axis like a globe.
    fun mapper(width: Float, height: Float): GraphMapper {
        val fit = (min(width, height) / 1000f).coerceAtLeast(0.1f)
        return GraphMapper(
            center = Offset(width / 2f, height / 2f) + pan,
            scale = fit * zoom,
            yawRad = Math.toRadians(yawDeg.toDouble()).toFloat(),
            pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat(),
            inPlaneRad = Math.toRadians(rotationDeg.toDouble()).toFloat()
        )
    }

    if (graph.nodes.isEmpty()) {
        Text(
            stringResource(R.string.second_brain_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val labelStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurface)
    var canvasPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .testTag("settings_second_brain_graph")
            .onSizeChanged { canvasPx = it }
            .pointerInput(graph) {
                detectTapGestures(
                    onTap = { tap ->
                        val width = canvasPx.width.toFloat()
                        val height = canvasPx.height.toFloat()
                        if (width <= 0f || height <= 0f) return@detectTapGestures
                        val map = mapper(width, height)
                        var best: String? = null
                        var bestDist = Float.MAX_VALUE
                        val tapRadius = neuronScreenRadius(width, height) * 2.6f
                        graph.nodes.forEachIndexed { index, node ->
                            val dist = (map.project(layout[index], layoutZ[index]).screen - tap).getDistance()
                            if (dist < bestDist) {
                                bestDist = dist
                                best = node.id
                            }
                        }
                        if (best != selectedId) markTouched()
                        selectedId = if (bestDist <= tapRadius && best != selectedId) best else null
                    },
                    onDoubleTap = {
                        markTouched()
                        zoom = 1f
                        pan = Offset.Zero
                        rotationDeg = 0f
                        yawDeg = -55f
                        pitchDeg = 20f
                        selectedId = null
                    }
                )
            }
            .pointerInput(graph) {
                // Double-tap-drag spins the globe horizontally. Only true taps
                // are recorded, so plain drags keep sliding via the detector below.
                val timeout = viewConfiguration.doubleTapTimeoutMillis
                val slop = viewConfiguration.touchSlop * 2
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < timeout && (down.position - lastTapPos).getDistance() < slop) {
                        yawLock.value = true
                        markTouched()
                        try {
                            var last = down.position
                            val id = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.find { it.id == id }
                                if (change == null || !change.pressed) break
                                yawDeg = (yawDeg + (change.position.x - last.x) * 0.35f) % 360f
                                last = change.position
                                markTouched()
                                if (event.changes.all { !it.pressed }) break
                            }
                        } finally {
                            yawLock.value = false
                        }
                        lastTapTime = 0L
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            lastTapTime = System.currentTimeMillis()
                            lastTapPos = up.position
                        } else {
                            lastTapTime = 0L
                        }
                    }
                }
            }
            .pointerInput(graph) {
                detectTransformGestures { _, panChange, zoomChange, rotationChange ->
                    if (!yawLock.value) {
                        markTouched()
                        pan += panChange
                    }
                    zoom = (zoom * zoomChange).coerceIn(0.4f, 4f)
                    rotationDeg = (rotationDeg + rotationChange) % 360f
                }
            }
    ) {
        val map = mapper(size.width, size.height)
        val byId = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val projected = graph.nodes.indices.map { map.project(layout[it], layoutZ[it]) }
        drawBrainShell(map)
        graph.edges.forEach { edge ->
            val fromIdx = byId[edge.fromId]
            val toIdx = byId[edge.toId]
            if (fromIdx != null && toIdx != null) {
                val bothVisible = edge.fromId in visible && edge.toId in visible
                val from = projected[fromIdx].screen
                val to = projected[toIdx].screen
                val fromDepth = projected[fromIdx].depth01
                val toDepth = projected[toIdx].depth01
                // 3D synapse: segments fade from source depth to target depth.
                val mid = (from + to) / 2f
                val normal = Offset(-(to.y - from.y), to.x - from.x)
                val normalLen = normal.getDistance().coerceAtLeast(1f)
                val control = mid + normal / normalLen * (from.getDistance() * 0.12f + 18f)
                val segments = 10
                val baseAlpha = if (bothVisible) 0.65f else 0.08f
                for (s in 0 until segments) {
                    val t0 = s / segments.toFloat()
                    val t1 = (s + 1) / segments.toFloat()
                    val depth = fromDepth + (toDepth - fromDepth) * ((t0 + t1) / 2f)
                    drawLine(
                        color = scheme.outline.copy(alpha = baseAlpha * (1f - 0.5f * depth)),
                        start = quadPoint(from, control, to, t0),
                        end = quadPoint(from, control, to, t1),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        // Back-to-front by projected depth.
        val order = graph.nodes.indices.sortedByDescending { projected[it].depth01 }
        order.forEach { index ->
            val node = graph.nodes[index]
            val focused = node.id in visible
            val depth = projected[index].depth01
            val alpha = (if (focused) 1f else 0.12f) * (1f - 0.4f * depth)
            val center = projected[index].screen
            // Graph-unit radius: grows and shrinks with zoom, always fits the brain.
            val radius = NEURON_GRAPH_RADIUS * map.scale * projected[index].persp *
                (1f + 0.15f * (degree[node.id] ?: 0)) * (if (node.id == selectedId) 1.25f else 1f)
            val base = nodeFill[node.kind] ?: scheme.surfaceVariant
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f * alpha),
                        base.copy(alpha = alpha),
                        base.copy(alpha = 0.55f * alpha)
                    ),
                    center = center + Offset(-radius * 0.3f, -radius * 0.3f),
                    radius = radius * 1.6f
                ),
                radius = radius,
                center = center
            )
            if (node.id == selectedId) {
                drawCircle(
                    color = scheme.onSurface,
                    radius = radius + 5f,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )
            }
            val showLabel = zoom >= 0.8f || node.id == selectedId || (selectedId != null && node.id in visible)
            if (showLabel) {
                val short = node.label.take(10)
                val measured = textMeasurer.measure(
                    short,
                    labelStyle.copy(color = labelStyle.color.copy(alpha = alpha))
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(center.x - measured.size.width / 2f, center.y + radius + 4f)
                )
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        OutlinedButton(
            onClick = {
                zoom = 1f
                pan = Offset.Zero
                rotationDeg = 0f
                yawDeg = -55f
                pitchDeg = 20f
                selectedId = null
            },
            modifier = Modifier.testTag("settings_second_brain_graph_reset")
        ) { Text(stringResource(R.string.second_brain_graph_reset)) }
    }

    val selected = selectedId?.let { id -> graph.nodes.find { it.id == id } }
    if (selected != null) {
        val file = findBrainFile(snapshot, selected.id)
        val neighborCount = (visible.size - 1).coerceAtLeast(0)
        Text(
            text = selected.label + stringResource(R.string.second_brain_graph_neighbors, neighborCount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (file != null) {
            BrainFileBody(file = file, tag = "graph_selected")
        }
    } else {
        Text(
            text = stringResource(R.string.second_brain_graph_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Viewport math shared by drawing and tap detection: yaw spins around the
 * vertical axis (globe-style, perpendicular to the screen), pitch tilts,
 * in-plane rotation turns the finished picture. */
private data class GraphMapper(
    val center: Offset,
    val scale: Float,
    val yawRad: Float,
    val pitchRad: Float,
    val inPlaneRad: Float
) {
    /** Projects a force-layout point plus depth offset into screen space. */
    fun project(p: Offset, z: Float): Projected {
        val s = project3(p.x, p.y, z)
        return Projected(screen = s.screen, depth01 = s.depth01, persp = s.persp)
    }

    /** Projects raw 3D coordinates (used by the brain shell). */
    fun project3(x: Float, y: Float, z: Float): Projected {
        val cosY = cos(yawRad)
        val sinY = sin(yawRad)
        val x1 = x * cosY + z * sinY
        val z1 = -x * sinY + z * cosY
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)
        val y2 = y * cosP - z1 * sinP
        val z2 = y * sinP + z1 * cosP
        val focal = 1600f
        val persp = focal / (focal + z2)
        val flat = Offset(x1, y2) * scale * persp
        val cosI = cos(inPlaneRad)
        val sinI = sin(inPlaneRad)
        return Projected(
            screen = center + Offset(
                flat.x * cosI - flat.y * sinI,
                flat.x * sinI + flat.y * cosI
            ),
            depth01 = (((z2 / 600f) + 1f) / 2f).coerceIn(0f, 1f),
            persp = persp
        )
    }
}

private data class Projected(val screen: Offset, val depth01: Float, val persp: Float)

/** Deterministic depth spread per node, inside the shell's z-radius. */
private fun nodeZ(index: Int): Float = ((index * 53 % 100) / 100f - 0.5f) * 520f

/** Neuron radius in graph units: scales with zoom through the viewport. */
private const val NEURON_GRAPH_RADIUS = 30f

/** Approximate screen radius for tap hit-testing at the given canvas size. */
private fun neuronScreenRadius(width: Float, height: Float): Float {
    val fit = (min(width, height) / 1000f).coerceAtLeast(0.1f)
    return 30f * fit * 1.6f
}

/** Point on a quadratic bezier at t, for segmented gradient edges. */
private fun quadPoint(p0: Offset, c: Offset, p1: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * p0.x + 2f * u * t * c.x + t * t * p1.x,
        u * u * p0.y + 2f * u * t * c.y + t * t * p1.y
    )
}

/**
 * The translucent 3D brain shell holding every neuron: a bumpy two-lobed
 * ellipsoid sampled as real 3D points and projected through the current
 * yaw/pitch, so the shell itself turns in 3D with the memories inside it.
 * Sulci ride on the surface (dimmer on the far side); the longitudinal
 * fissure splits the hemispheres.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBrainShell(map: GraphMapper) {
    val shellColor = Color(0xFF7FB3D5)

    // Parametric bumpy cerebrum. Frontal taper narrows the front so the
    // silhouette reads as a brain, not a rugby ball. Confined layout content
    // (±520/±360/±280) fits inside the 600/420/340 radii.
    fun surface(lon: Float, lat: Float): OffsetTriple {
        val bump = 1f + 0.05f * sin(3f * lon + 0.8f) + 0.035f * sin(2f * lat + 1.7f) + 0.02f * sin(5f * lon - lat)
        // Slight center groove between the hemispheres.
        val groove = 1f - 0.10f * (cos(lon) * cos(lon)) * cos(lat) * cos(lat)
        // Frontal taper: narrower toward the front (+z). Long in x, lower in y,
        // like a side-view diagram rather than a ball.
        val frontness = (sin(lon) * 0.5f + 0.5f)
        val taper = 1f - 0.10f * frontness
        return OffsetTriple(
            630f * bump * groove * taper * cos(lat) * cos(lon),
            390f * bump * taper * sin(lat),
            340f * bump * cos(lat) * sin(lon)
        )
    }
    fun sampleSphere(
        center: OffsetTriple,
        radius: Float,
        lonSteps: Int = 16,
        latSteps: Int = 5
    ): List<OffsetTriple> {
        val out = mutableListOf<OffsetTriple>()
        for (li in 0 until lonSteps) {
            val lon = 2f * Math.PI.toFloat() * li / lonSteps
            for (si in 0..latSteps) {
                val lat = -1.2f + 2.4f * si / latSteps
                out.add(
                    OffsetTriple(
                        center.x + radius * cos(lat) * cos(lon),
                        center.y + radius * sin(lat),
                        center.z + radius * cos(lat) * sin(lon)
                    )
                )
            }
        }
        return out
    }
    // Sample the surface, project, silhouette via convex hull.
    val samples = mutableListOf<Offset>()
    val lonSteps = 28
    val latSteps = 7
    for (li in 0 until lonSteps) {
        val lon = 2f * Math.PI.toFloat() * li / lonSteps
        for (si in 0..latSteps) {
            val lat = -1.25f + 2.5f * si / latSteps
            val s = surface(lon, lat)
            samples.add(map.project3(s.x, s.y, s.z).screen)
        }
    }
    val hull = convexHull(samples)
    if (hull.size >= 3) {
        val path = Path().apply {
            moveTo(hull[0].x, hull[0].y)
            hull.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path = path, color = shellColor.copy(alpha = 0.10f))
        drawPath(path = path, color = shellColor.copy(alpha = 0.45f), style = Stroke(width = 3f))
    }
    // Cerebellum and brainstem below; see drawHindbrain.
    drawHindbrain(map)
    // Longitudinal fissure: surface line over the top center.
    var prev: Offset? = null
    for (si in 0..24) {
        val lat = -1.2f + 2.4f * si / 24f
        val s = surface(Math.PI.toFloat() / 2f, lat)
        val p = map.project3(s.x, s.y, s.z)
        prev?.let {
            drawLine(
                color = shellColor.copy(alpha = 0.5f * (1f - 0.6f * p.depth01)),
                start = it,
                end = p.screen,
                strokeWidth = 3f
            )
        }
        prev = p.screen
    }
    // A few sulci per hemisphere, fading on the far side.
    val pi = Math.PI.toFloat()
    val sulci = listOf(
        Triple(0.5f to -0.5f, 0.9f to -0.1f, 1.2f to -0.5f),
        Triple(0.4f to 0.2f, 0.9f to 0.4f, 1.3f to 0.1f),
        Triple(0.6f to 0.7f, 1.0f to 0.9f, 1.4f to 0.6f),
        Triple((pi - 0.5f) to -0.5f, (pi - 0.9f) to -0.1f, (pi - 1.2f) to -0.5f),
        Triple((pi - 0.4f) to 0.2f, (pi - 0.9f) to 0.4f, (pi - 1.3f) to 0.1f),
        Triple((pi - 0.6f) to 0.7f, (pi - 1.0f) to 0.9f, (pi - 1.4f) to 0.6f)
    )
    sulci.forEach { (p0, c, p1) ->
        val a = surface(p0.first, p0.second)
        val b = surface(c.first, c.second)
        val d = surface(p1.first, p1.second)
        var last: Offset? = null
        var lastDepth = 0f
        for (s in 0..10) {
            val t = s / 10f
            val gx = quad1(a.x, b.x, d.x, t)
            val gy = quad1(a.y, b.y, d.y, t)
            val gz = quad1(a.z, b.z, d.z, t)
            val proj = map.project3(gx, gy, gz)
            last?.let {
                drawLine(
                    color = shellColor.copy(alpha = 0.35f * (1f - 0.7f * (lastDepth + proj.depth01) / 2f)),
                    start = it,
                    end = proj.screen,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }
            last = proj.screen
            lastDepth = proj.depth01
        }
    }
}

private data class OffsetTriple(val x: Float, val y: Float, val z: Float)

/** Cerebellum lobes plus tapering brainstem, turning with the same viewport. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHindbrain(map: GraphMapper) {
    val shellColor = Color(0xFF7FB3D5)

    fun blobPath(points: List<OffsetTriple>): Path? {
        val hull = convexHull(points.map { map.project3(it.x, it.y, it.z).screen })
        if (hull.size < 3) return null
        return Path().apply {
            moveTo(hull[0].x, hull[0].y)
            hull.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
    }
    fun sampleSphere(
        center: OffsetTriple,
        radius: Float,
        lonSteps: Int = 16,
        latSteps: Int = 5
    ): List<OffsetTriple> {
        val out = mutableListOf<OffsetTriple>()
        for (li in 0 until lonSteps) {
            val lon = 2f * Math.PI.toFloat() * li / lonSteps
            for (si in 0..latSteps) {
                val lat = -1.2f + 2.4f * si / latSteps
                out.add(
                    OffsetTriple(
                        center.x + radius * cos(lat) * cos(lon),
                        center.y + radius * sin(lat),
                        center.z + radius * cos(lat) * sin(lon)
                    )
                )
            }
        }
        return out
    }
    // Cerebellum: two small lobes tucked under the back-bottom, with folia stripes.
    listOf(OffsetTriple(-150f, 330f, -140f), OffsetTriple(150f, 330f, -140f)).forEach { lobe ->
        blobPath(sampleSphere(lobe, 120f))?.let { path ->
            drawPath(path = path, color = shellColor.copy(alpha = 0.12f))
            drawPath(path = path, color = shellColor.copy(alpha = 0.4f), style = Stroke(width = 2f))
        }
        for (stripe in -1..1) {
            var striped: Offset? = null
            for (i in 0..16) {
                val a = 2f * Math.PI.toFloat() * i / 16f
                val p = map.project3(
                    lobe.x + 115f * cos(a),
                    lobe.y + stripe * 38f,
                    lobe.z + 115f * sin(a)
                ).screen
                striped?.let {
                    drawLine(color = shellColor.copy(alpha = 0.25f), start = it, end = p, strokeWidth = 1.5f)
                }
                striped = p
            }
        }
    }
    // Brainstem: tapering stalk down the back-center.
    listOf(
        OffsetTriple(0f, 400f, -110f) to 62f,
        OffsetTriple(0f, 460f, -90f) to 44f
    ).forEach { (center, radius) ->
        blobPath(sampleSphere(center, radius, 12, 4))?.let { path ->
            drawPath(path = path, color = shellColor.copy(alpha = 0.12f))
            drawPath(path = path, color = shellColor.copy(alpha = 0.4f), style = Stroke(width = 2f))
        }
    }
}

private fun quad1(p0: Float, c: Float, p1: Float, t: Float): Float {
    val u = 1f - t
    return u * u * p0 + 2f * u * t * c + t * t * p1
}

/** Monotone-chain convex hull for the shell silhouette. */
private fun convexHull(points: List<Offset>): List<Offset> {
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y })).distinct()
    if (sorted.size < 3) return sorted
    fun cross(o: Offset, a: Offset, b: Offset): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = mutableListOf<Offset>()
    sorted.forEach { p ->
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) lower.removeLast()
        lower.add(p)
    }
    val upper = mutableListOf<Offset>()
    sorted.asReversed().forEach { p ->
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) upper.removeLast()
        upper.add(p)
    }
    lower.removeLast()
    upper.removeLast()
    return lower + upper
}

private fun findBrainFile(snapshot: MemoryStore.SecondBrainSnapshot, id: String): MemoryStore.BrainFile? {
    val all = snapshot.allMarkdown() + snapshot.raw
    return all.find { file ->
        val abs = file.absolutePath
        if (abs.isNotBlank()) {
            val root = runCatching { com.gotcha.data.GotchaStorage.memoryRoot().absolutePath }.getOrNull()
            if (root != null && abs.startsWith(root)) {
                return@find abs.removePrefix(root).trimStart('/') == id
            }
        }
        file.name == id
    }
}

@Composable
private fun BrainSection(heading: String, file: MemoryStore.BrainFile, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_second_brain_section_$tag")
    ) {
        Text(
            text = (if (expanded) "▼ " else "▶ ") + heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
        )
        if (expanded) {
            BrainFileBody(file = file, tag = tag)
        }
    }
    HorizontalDivider(thickness = 1.dp)
}

@Composable
private fun BrainFileList(heading: String, files: List<MemoryStore.BrainFile>, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_second_brain_list_$tag")
    ) {
        Text(
            text = (if (expanded) "▼ " else "▶ ") + "$heading (${files.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
        )
        if (expanded) {
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.second_brain_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                files.forEachIndexed { index, file ->
                    var fileExpanded by remember { mutableStateOf(false) }
                    Text(
                        text = (if (fileExpanded) "▼ " else "▶ ") + file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fileExpanded = !fileExpanded }
                            .padding(vertical = 2.dp)
                            .testTag("settings_second_brain_${tag}_$index")
                    )
                    if (fileExpanded) {
                        BrainFileBody(file = file, tag = "${tag}_$index")
                    }
                }
            }
        }
    }
    HorizontalDivider(thickness = 1.dp)
}

/**
 * Per-file preview: Markdown renders as formatted text, text files as plain
 * text, and media/binary files (audio, images, video, documents) show file
 * info with an Open button that hands them to a system viewer.
 */
@Composable
private fun BrainFileBody(file: MemoryStore.BrainFile, tag: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (file.isMarkdown && file.content.isNotBlank()) {
            SelectionContainer {
                RichMarkdown(
                    markdown = file.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("settings_second_brain_body_$tag")
                )
            }
        } else if (file.content.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = file.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("settings_second_brain_body_$tag")
                )
            }
        } else {
            BrainBinaryRow(file = file, tag = tag)
        }
        if (file.truncated) {
            Text(
                text = stringResource(R.string.second_brain_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BrainBinaryRow(file: MemoryStore.BrainFile, tag: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            .testTag("settings_second_brain_binary_$tag"),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = "${file.extension.uppercase()} · ${FileResolver.formatSizeStatic(file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.TextButton(
            onClick = {
                val opened = runCatching {
                    val target = File(file.absolutePath)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target
                    )
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (!opened) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.second_brain_open_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        ) {
            Text(stringResource(R.string.second_brain_open))
        }
    }
}
