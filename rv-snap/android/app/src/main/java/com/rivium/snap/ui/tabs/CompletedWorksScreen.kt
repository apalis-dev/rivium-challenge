package com.rivium.snap.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

@Composable
fun CompletedWorksScreen(
    works: List<CompletedWorkItem>,
    selectedIds: List<String>,
    onToggleSelect: (String) -> Unit,
    onExportAllClick: () -> Unit,
    onExportSelectedClick: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val batches = remember(works) { groupIntoBatches(works) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Completed Works",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                Text(
                    text = "${works.size} images processed",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                )
            }

            if (selectedIds.isNotEmpty()) {
                Button(
                    onClick = { onExportSelectedClick(selectedIds) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "Download Selected",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download (${selectedIds.size})", style = MaterialTheme.typography.labelMedium)
                }
            } else if (works.isNotEmpty()) {
                FilledTonalButton(
                    onClick = onExportAllClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "Download All",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download All", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (works.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "No images",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No processed images yet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Import photos from the Home tab to start batch compression.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                    )
                }
            }
        } else {
            // Summary Stats (cached on works change to avoid heavy reduction during recomposition)
            val (totalSaved, avgDuration) = remember(works) {
                val totalOrig = works.sumOf { it.originalSize }
                val totalComp = works.sumOf { it.compressedSize }
                val saved = if (totalOrig > 0)
                    (((totalOrig - totalComp).toFloat() / totalOrig.toFloat()) * 100).toInt()
                else 0
                val avg = if (works.isNotEmpty()) works.sumOf { it.durationMs } / works.size else 0L
                saved to avg
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatSummaryItem(label = "Space Saved", value = "$totalSaved%")
                    StatSummaryItem(label = "Avg Time", value = "${avgDuration}ms")
                    StatSummaryItem(label = "Total", value = "${works.size}")
                }
            }

            // Batches list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                batches.forEachIndexed { batchIndex, batch ->
                    val batchIds = batch.map { it.id }
                    val batchLabel = formatBatchLabel(batch)
                    val batchNumber = batches.size - batchIndex

                    item(key = "cw_header_$batchIndex") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                onClick = { onExportSelectedClick(batchIds) },
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
                    items(rows, key = { "cw_row_${batchIndex}_${it.first().id}" }) { rowItems ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { work ->
                                val isSelected = selectedIds.contains(work.id)
                                WorkItemCard(
                                    work = work,
                                    isSelected = isSelected,
                                    onDownload = { onExportSelectedClick(listOf(work.id)) },
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
        }
    }
}

@Composable
private fun StatSummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        )
    }
}

@Composable
fun WorkItemCard(
    work: CompletedWorkItem,
    isSelected: Boolean,
    onDownload: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Image preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {}

                // Attempt to display image via Coil if local file exists, avoiding decoding raw 100MB+ files
                val localFile = File(work.outputPath)
                val modelToLoad: Any? = when {
                    localFile.exists() -> localFile
                    work.originalSize < 10_000_000L -> work.originalUri
                    else -> null // Do not decode massive 100MB+ original raw images into preview before compression
                }

                if (modelToLoad != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(modelToLoad)
                            .size(300, 300)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Image preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Image preview",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(36.dp)
                    )
                }

                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(22.dp)
                    )
                }

                // Compression badge
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2E7D32)
                ) {
                    Text(
                        text = "-${work.reductionPercentage}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Size comparison
            Text(
                text = "${work.formattedSize(work.originalSize)} → ${work.formattedSize(work.compressedSize)}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )

            // Blake3 Hash
            Text(
                text = "Blake3: #${work.blake3Hash.take(10)}...",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
            )

            // Duration + per-card download button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = "Speed",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "${work.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.outline
                        )
                    )
                }
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
