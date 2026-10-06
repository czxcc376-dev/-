package me.rerere.rikkahub.ui.components.message.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanView
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStep
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStepStatus
import me.rerere.rikkahub.data.ai.tools.local.plan.unmetDependencies

private const val TIMELINE_TAB = 0
private const val GRAPH_TAB = 1

/**
 * 双栏工作台的右栏：计划工作台。
 *
 * 用玻璃拟态（hazeGlass）+ 一圈静止的流动氛围光（复用 [PlanAmbientGlow]）包裹，
 * 内部提供「时间轴」与「依赖图」两个视图，把计划的进度、依赖、负责人可视化成
 * 一个真正的开发向工作台。
 */
@Composable
fun PlanWorkbenchSidePanel(
    activePlan: ActivePlan?,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(TIMELINE_TAB) }
    val view = activePlan?.view
    val active = activePlan != null && !activePlan.isPending

    PlanAmbientGlow(
        modifier = modifier,
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary,
        ),
        active = active,
        cornerRadius = 20.dp,
        strokeWidth = 1.5.dp,
    ) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .hazeGlass(
                    input = HazeInput.Sources(hazeState),
                    style = GlassStyle.Material3(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tint = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.35f),
                    ) {
                        optics(GlassDefaults.optics.copy(
                            blurRadius = OpticalSizeValue.Fixed(18.dp),
                            depth = OpticalSizeValue.Fixed(0.5f),
                        ))
                        shape(RoundedCornerShape(20.dp))
                    },
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.plan_workbench_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (view != null) {
                        val done = view.doneCount
                        val total = view.totalCount
                        Text(
                            text = "$done / $total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SecondaryTabRow(
                    selectedTabIndex = tab,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Tab(
                        selected = tab == TIMELINE_TAB,
                        onClick = { tab = TIMELINE_TAB },
                        text = { Text(stringResource(R.string.plan_workbench_timeline)) },
                    )
                    Tab(
                        selected = tab == GRAPH_TAB,
                        onClick = { tab = GRAPH_TAB },
                        text = { Text(stringResource(R.string.plan_workbench_graph)) },
                    )
                }

                if (view == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.plan_workbench_empty) + "\n" + stringResource(R.string.plan_workbench_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    when (tab) {
                        TIMELINE_TAB -> PlanTimelineView(view)
                        GRAPH_TAB -> PlanDependencyGraph(view)
                    }
                }
            }
        }
    }
}

/**
 * 时间轴视图：按顺序纵向展开所有步骤，左侧状态点 + 连接线，右侧标题 / 负责人 / 耗时 / 依赖。
 */
@Composable
private fun PlanTimelineView(view: InteractivePlanView) {
    val steps = view.steps
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        steps.forEachIndexed { index, step ->
            val depth = if (step.parentId.isNullOrBlank()) 0 else 1
            TimelineStepRow(
                step = step,
                allSteps = steps,
                depth = depth,
                isLast = index == steps.lastIndex,
            )
        }
    }
}

@Composable
private fun TimelineStepRow(
    step: PlanStep,
    allSteps: List<PlanStep>,
    depth: Int,
    isLast: Boolean,
) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp),
    ) {
        // 左侧时间轴：状态点 + 连接线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(22.dp),
        ) {
            PlanStatusGlyph(status = step.status, size = 14.dp)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(52.dp)
                        .background(lineColor),
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 16.dp),
        ) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = timelineMeta(step, allSteps)
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun timelineMeta(step: PlanStep, allSteps: List<PlanStep>): String? {
    val parts = buildList {
        if (step.owner.isNotBlank()) add(stringResource(R.string.plan_workbench_owner) + " · ${step.owner}")
        if (step.labels.isNotEmpty()) add(step.labels.joinToString("/"))
        val elapsed = step.elapsedMillis()
        if (elapsed != null && elapsed > 0) {
            val s = elapsed / 1000
            add(if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s")
        }
        val unmet = step.unmetDependencies(allSteps)
        if (unmet.isNotEmpty() && !step.isDone) {
            add(stringResource(R.string.plan_workbench_waiting) + " · ${unmet.joinToString(", ") { it.title }}")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
}

/**
 * 依赖连线图：把步骤按父子层级横向展开，纵向按顺序排列，并用曲线连接
 * 「父 → 子」与「步骤 → 依赖」，颜色随状态变化。
 */
@Composable
private fun PlanDependencyGraph(view: InteractivePlanView) {
    val steps = view.steps
    if (steps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.plan_workbench_no_steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val nodes = remember(steps) { computeGraphNodes(steps) }
    val rowHeight = 64f
    val topPad = 28f
    val leftPad = 24f
    val levelGap = 88f
    val canvasWidth = leftPad * 2 + 3 * levelGap
    val canvasHeight = topPad * 2 + steps.size * rowHeight

    val statusColor: (PlanStepStatus) -> Color = { status ->
        when (status) {
            PlanStepStatus.COMPLETED, PlanStepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
            PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
            PlanStepStatus.BLOCKED -> MaterialTheme.colorScheme.error
            PlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    // 在 Composable 作用域预先求出每个节点的颜色，避免在 Canvas 绘制 lambda（非 Composable）里读取 MaterialTheme。
    val nodeColors = nodes.associate { it.step.id to statusColor(it.step.status) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight.dp)
                .padding(8.dp),
        ) {
            val byId = nodes.associateBy { it.step.id }

            // 先画边：父 → 子
            nodes.forEach { node ->
                val parent = node.step.parentId?.let { byId[it] }
                if (parent != null) {
                    drawLine(
                        color = edgeColor,
                        start = Offset(parent.x, parent.y),
                        end = Offset(node.x, node.y),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            // 再画边：步骤 → 依赖
            nodes.forEach { node ->
                node.step.dependsOn.forEach { depId ->
                    val dep = byId[depId] ?: return@forEach
                    val path = Path().apply {
                        moveTo(node.x, node.y)
                        val midX = (node.x + dep.x) / 2f
                        cubicTo(midX, node.y, midX, dep.y, dep.x, dep.y)
                    }
                    drawPath(
                        path = path,
                        color = edgeColor,
                        style = Stroke(width = 1.25.dp.toPx()),
                    )
                }
            }
            // 最后画节点
            nodes.forEach { node ->
                val c = nodeColors[node.step.id] ?: Color.Gray
                drawCircle(
                    color = c,
                    radius = 5.dp.toPx(),
                    center = Offset(node.x, node.y),
                )
                drawCircle(
                    color = c.copy(alpha = 0.18f),
                    radius = 11.dp.toPx(),
                    center = Offset(node.x, node.y),
                )
            }
        }

        // 图例 + 步骤标签列表
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            nodes.forEach { node ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(nodeColors[node.step.id] ?: Color.Gray, CircleShape),
                    )
                    Text(
                        text = node.step.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (node.step.owner.isNotBlank()) {
                        Text(
                            text = node.step.owner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private data class GraphNode(
    val step: PlanStep,
    val depth: Int,
    val x: Float,
    val y: Float,
)

private fun computeGraphNodes(steps: List<PlanStep>): List<GraphNode> {
    val byId = steps.associateBy { it.id }

    fun depthOf(step: PlanStep): Int =
        if (step.parentId.isNullOrBlank()) 0
        else 1 + (byId[step.parentId]?.let { depthOf(it) } ?: 0)

    val rowHeight = 64f
    val topPad = 28f
    val leftPad = 24f
    val levelGap = 88f

    return steps.mapIndexed { index, step ->
        GraphNode(
            step = step,
            depth = depthOf(step).coerceAtMost(3),
            x = leftPad + depthOf(step).coerceAtMost(3) * levelGap,
            y = topPad + index * rowHeight,
        )
    }
}
