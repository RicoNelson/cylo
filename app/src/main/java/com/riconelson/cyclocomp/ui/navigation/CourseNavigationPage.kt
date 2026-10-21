package com.riconelson.cyclocomp.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.core.navigation.NavigationStatus
import com.riconelson.cyclocomp.core.navigation.TurnCue
import com.riconelson.cyclocomp.core.navigation.TurnType

/**
 * Full Course Navigation Page displaying the active course overview,
 * the featured active next turn step with fluid auto-changing animations,
 * and the complete upcoming active course list cue sheet.
 */
@Composable
fun CourseNavigationPage(
    activeRoute: GpxRoute?,
    navigationStatus: NavigationStatus?,
    modifier: Modifier = Modifier,
    onOpenMap: () -> Unit = {},
) {
    if (activeRoute == null) {
        EmptyCourseState(modifier = modifier)
        return
    }

    val allCues = activeRoute.turnCues
    if (allCues.isEmpty()) {
        NoCuesState(route = activeRoute, modifier = modifier, onOpenMap = onOpenMap)
        return
    }

    // Determine active turn index based on live navigation status or default to 0
    val nextTurnCue = navigationStatus?.nextTurnCue
    val activeTurnIndex = remember(nextTurnCue, allCues) {
        if (nextTurnCue != null) {
            val matchedIndex = allCues.indexOfFirst { it.pointIndex == nextTurnCue.pointIndex }
            if (matchedIndex >= 0) matchedIndex else 0
        } else {
            0
        }
    }

    val activeCue = allCues.getOrNull(activeTurnIndex) ?: allCues.first()
    val upcomingCues = if (activeTurnIndex + 1 < allCues.size) {
        allCues.subList(activeTurnIndex + 1, allCues.size)
    } else {
        emptyList()
    }
    val completedCues = if (activeTurnIndex > 0) {
        allCues.take(activeTurnIndex)
    } else {
        emptyList()
    }

    var showCompletedTurns by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Off-course Alert Banner (if applicable)
        if (navigationStatus?.isOffCourse == true) {
            item(key = "off_course_alert") {
                OffCourseAlertCard(navigationStatus = navigationStatus)
            }
        }

        // 2. Course Header & Overview Stats
        item(key = "course_header") {
            CourseOverviewHeader(
                route = activeRoute,
                navigationStatus = navigationStatus,
                activeTurnIndex = activeTurnIndex,
                totalCuesCount = allCues.size,
                onOpenMap = onOpenMap,
            )
        }

        // 3. Featured Active Next Course Step Card with Fluid Auto-Changing Animation
        item(key = "active_course_step_section") {
            Text(
                text = "ACTIVE NEXT STEP",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
            )

            ActiveCourseStepCard(
                activeCue = activeCue,
                activeTurnIndex = activeTurnIndex,
                totalCuesCount = allCues.size,
                previousCue = if (activeTurnIndex > 0) allCues[activeTurnIndex - 1] else null,
                navigationStatus = navigationStatus,
            )
        }

        // 4. Upcoming Active Course List Header
        item(key = "upcoming_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "UPCOMING COURSE STEPS (${upcomingCues.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )

                if (completedCues.isNotEmpty()) {
                    Surface(
                        onClick = { showCompletedTurns = !showCompletedTurns },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = if (showCompletedTurns) "Hide past (${completedCues.size})" else "Past turns (${completedCues.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 5. Completed Turns (Collapsible)
        if (showCompletedTurns && completedCues.isNotEmpty()) {
            itemsIndexed(
                items = completedCues,
                key = { idx, cue -> "completed_${cue.pointIndex}_$idx" },
            ) { idx, cue ->
                CompletedTurnItem(
                    stepNumber = idx + 1,
                    cue = cue,
                )
            }
        }

        // 6. Upcoming Active Course Steps List
        if (upcomingCues.isEmpty()) {
            item(key = "no_more_upcoming") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("🏁", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "This is the final turn! Destination ahead.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                items = upcomingCues,
                key = { _, cue -> "upcoming_${cue.pointIndex}" },
            ) { idx, cue ->
                val upcomingStepNum = activeTurnIndex + 2 + idx
                val prevCue = if (idx == 0) activeCue else upcomingCues[idx - 1]
                val legDistanceMeters = (cue.distanceToTurnMeters - prevCue.distanceToTurnMeters).coerceAtLeast(0.0)

                val activeDist = navigationStatus?.distanceToNextTurnMeters ?: activeCue.distanceToTurnMeters
                val cumulativeFromNow = activeDist + (cue.distanceToTurnMeters - activeCue.distanceToTurnMeters)

                UpcomingTurnItem(
                    stepNumber = upcomingStepNum,
                    cue = cue,
                    legDistanceMeters = legDistanceMeters,
                    cumulativeDistanceMeters = cumulativeFromNow,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Featured Active Next Course Step Card with fluid AnimatedContent transitions
 * whenever the active turn auto-advances.
 */
@Composable
private fun ActiveCourseStepCard(
    activeCue: TurnCue,
    activeTurnIndex: Int,
    totalCuesCount: Int,
    previousCue: TurnCue?,
    navigationStatus: NavigationStatus?,
    modifier: Modifier = Modifier,
) {
    val liveDistanceMeters = navigationStatus?.distanceToNextTurnMeters
    val isOffCourse = navigationStatus?.isOffCourse == true

    val containerColor by animateColorAsState(
        targetValue = when {
            isOffCourse -> MaterialTheme.colorScheme.errorContainer
            activeCue.turnType == TurnType.FINISH -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(500),
        label = "ContainerColorAnimation",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(300)),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        // Fluid transition when active turn index changes
        AnimatedContent(
            targetState = activeTurnIndex to activeCue,
            transitionSpec = {
                if (targetState.first > initialState.first) {
                    (slideInVertically(animationSpec = tween(400, easing = FastOutSlowInEasing)) { height -> height } +
                        fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(350)) { height -> -height } + fadeOut(animationSpec = tween(300))
                        )
                } else {
                    (slideInVertically(animationSpec = tween(400, easing = FastOutSlowInEasing)) { height -> -height } +
                        fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(350)) { height -> height } + fadeOut(animationSpec = tween(300))
                        )
                }
            },
            label = "ActiveStepCardTransition",
        ) { (_, cue) ->
            val symbolColor = getTurnSymbolColor(cue.turnType)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Top row: Step badge + Turn indicator tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = "STEP ${activeTurnIndex + 1} OF $totalCuesCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    // Live GPS / Navigation Status Pill
                    PulseStatusBadge(navigationStatus = navigationStatus)
                }

                // Main turn layout: Symbol circle + Distance & Instruction
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Turn Symbol Circle
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(symbolColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = cue.turnType.symbol,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        val distanceText = formatDistance(liveDistanceMeters?.toInt() ?: cue.distanceToTurnMeters.toInt())
                        Text(
                            text = "In $distanceText",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        Text(
                            text = cue.description,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.95f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Proximity Progress Indicator to Turn
                val totalLegMeters = if (previousCue != null) {
                    (cue.distanceToTurnMeters - previousCue.distanceToTurnMeters).coerceAtLeast(50.0)
                } else {
                    cue.distanceToTurnMeters.coerceAtLeast(50.0)
                }

                val currentRemaining = liveDistanceMeters ?: cue.distanceToTurnMeters
                val progress = (1f - (currentRemaining / totalLegMeters).toFloat()).coerceIn(0f, 1f)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Turn proximity",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = getTurnSymbolColor(cue.turnType),
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}

/**
 * Single item in the upcoming active course steps list.
 */
@Composable
private fun UpcomingTurnItem(
    stepNumber: Int,
    cue: TurnCue,
    legDistanceMeters: Double,
    cumulativeDistanceMeters: Double,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Step Number Badge
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
            ) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "#$stepNumber",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Turn Icon Badge
            val turnColor = getTurnSymbolColor(cue.turnType)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(turnColor.copy(alpha = 0.15f))
                    .border(1.5.dp, turnColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cue.turnType.symbol,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = turnColor,
                )
            }

            Spacer(Modifier.width(12.dp))

            // Description + Leg Distance
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cue.description,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = "+${formatDistance(legDistanceMeters.toInt())} from prev turn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Cumulative distance tag
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "in ${formatDistance(cumulativeDistanceMeters.toInt())}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "ahead",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * Item representing a completed past turn.
 */
@Composable
private fun CompletedTurnItem(
    stepNumber: Int,
    cue: TurnCue,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(0.6f),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "✓ Step $stepNumber",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = cue.turnType.symbol,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = cue.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Course Overview Header displaying Course name, remaining distance, and map shortcut button.
 */
@Composable
private fun CourseOverviewHeader(
    route: GpxRoute,
    navigationStatus: NavigationStatus?,
    activeTurnIndex: Int,
    totalCuesCount: Int,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = route.name ?: "Active Course",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Following active route navigation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                Button(
                    onClick = onOpenMap,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text("Map ⤢", fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val totalKm = route.totalDistanceMeters / 1000.0
                val remainingMeters = navigationStatus?.totalRemainingDistanceMeters

                HeaderStatChip(
                    label = "Total Length",
                    value = "%.1f km".format(totalKm),
                    modifier = Modifier.weight(1f),
                )

                HeaderStatChip(
                    label = "Remaining",
                    value = if (remainingMeters != null) "%.1f km".format(remainingMeters / 1000.0) else "%.1f km".format(totalKm),
                    modifier = Modifier.weight(1f),
                )

                HeaderStatChip(
                    label = "Progress",
                    value = "${activeTurnIndex + 1}/$totalCuesCount",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HeaderStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Animated Pulse Status Badge for GPS activity.
 */
@Composable
private fun PulseStatusBadge(
    navigationStatus: NavigationStatus?,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "BadgePulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "AlphaPulse",
    )

    Surface(
        color = if (navigationStatus != null) Color(0xFF2E7D32).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (navigationStatus != null) alpha else 1f)
                    .background(
                        if (navigationStatus != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (navigationStatus != null) "LIVE GPS" else "ACTIVE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (navigationStatus != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Off-course Warning Alert Banner.
 */
@Composable
private fun OffCourseAlertCard(
    navigationStatus: NavigationStatus,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠️", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Off Course · ${navigationStatus.distanceToRouteMeters.toInt()} m from route",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Head back towards the course line to resume guidance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * Empty state when no course is currently loaded.
 */
@Composable
private fun EmptyCourseState(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🚴", fontSize = 48.sp)
            Text(
                text = "No Active Course Loaded",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Select or import a GPX route from the Routes tab to see live turn guidance and the full upcoming course step list.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * State when course is loaded but has no turn cues.
 */
@Composable
private fun NoCuesState(
    route: GpxRoute,
    modifier: Modifier = Modifier,
    onOpenMap: () -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("🧭", fontSize = 40.sp)
            Text(
                text = route.name ?: "Course Loaded",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Course is active with ${"%.1f".format(route.totalDistanceMeters / 1000.0)} km line geometry.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenMap) {
                Text("View Course Map")
            }
        }
    }
}

private fun getTurnSymbolColor(turnType: TurnType): Color {
    return when (turnType) {
        TurnType.RIGHT, TurnType.SLIGHT_RIGHT, TurnType.SHARP_RIGHT -> Color(0xFF00897B)
        TurnType.LEFT, TurnType.SLIGHT_LEFT, TurnType.SHARP_LEFT -> Color(0xFF1976D2)
        TurnType.U_TURN -> Color(0xFFE65100)
        TurnType.FINISH -> Color(0xFF2E7D32)
        TurnType.STRAIGHT -> Color(0xFF512DA8)
    }
}

private fun formatDistance(meters: Int): String {
    return if (meters >= 1000) {
        "%.1f km".format(meters / 1000.0)
    } else {
        "$meters m"
    }
}
