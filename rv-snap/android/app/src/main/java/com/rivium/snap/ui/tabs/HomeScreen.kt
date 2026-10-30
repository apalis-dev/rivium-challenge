package com.rivium.snap.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    metrics: QueueMetrics,
    works: List<CompletedWorkItem>,
    activeBatch: ActiveBatchTracker,
    selectedIds: List<String>,
    onToggleSelect: (String) -> Unit,
    onImportClick: () -> Unit,
    onExportBatch: (List<String>) -> Unit,
    onExportItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val batches = remember(works) { groupIntoBatches(works) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Column {
            Text(
                text = "RiviumSnap",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )
            Text(
                text = "Pocket Batch Processor",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
            )
        }

        // Live Queue Metrics Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "Pending",
                count = metrics.pending,
                icon = Icons.Default.HourglassTop,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Processing",
                count = metrics.running,
                icon = Icons.Default.Sync,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Completed",
                count = if (works.isNotEmpty()) works.size.toLong() else metrics.completed,
                icon = Icons.Default.CheckCircle,
                containerColor = Color(0xFF33B074),
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(visible = activeBatch.totalSubmitted > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isFinished = activeBatch.isFinished
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isFinished) "Batch #${activeBatch.batchNumber} Finished" else "Batch #${activeBatch.batchNumber} Processing",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isFinished) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = "${(activeBatch.progress * 100).toInt()}% (${activeBatch.processedInBatch}/${activeBatch.totalSubmitted})",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    LinearProgressIndicator(
                        progress = { activeBatch.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        // All processed images divided by batches shown below Native Engine Architecture text
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            batches.forEachIndexed { batchIndex, batch ->
                val batchIds = batch.map { it.id }
                val batchLabel = formatBatchLabel(batch)
                val batchNumber = batches.size - batchIndex

                item(key = "home_header_$batchIndex") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Batch #$batchNumber",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = "$batchLabel · ${batch.size} images",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.outline
                                )
                            )
                        }
                        TextButton(
                            onClick = { onExportBatch(batchIds) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Download batch",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Download All",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                val rows = batch.chunked(2)
                items(rows, key = { "home_row_${batchIndex}_${it.first().id}" }) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { work ->
                            val isSelected = selectedIds.contains(work.id)
                            WorkItemCard(
                                work = work,
                                isSelected = isSelected,
                                onDownload = { onExportItem(work.id) },
                                onClick = { onToggleSelect(work.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Primary Import Action Button
        Button(
            onClick = onImportClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Icon(
                Icons.Default.AddPhotoAlternate,
                contentDescription = "Import",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Import Images (Bulk Select)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    count: Long,
    icon: ImageVector,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                icon,
                contentDescription = title,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
            )
        }
    }
}
