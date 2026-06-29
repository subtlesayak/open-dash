package com.example.opendash.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.DashWallpaperKind
import com.example.opendash.data.DashWallpaperPaths
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.Eyebrow
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.ui.components.OpenDashIconBtn
import com.example.opendash.ui.components.OpenDashSegmented
import com.example.opendash.ui.components.OpenDashToggle
import com.example.opendash.ui.theme.Alert
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.viewmodel.DashViewModel

@Composable
fun DashWallpaperHomeSection(
    dashViewModel: DashViewModel,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val dashUi by dashViewModel.ui.collectAsState()
    var pendingWallpaperUri by remember { mutableStateOf<Uri?>(null) }
    var pendingWallpaperPreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var cropX by remember { mutableFloatStateOf(0f) }
    var cropY by remember { mutableFloatStateOf(0f) }
    var fitMode by remember { mutableStateOf(DashWallpaperFit.CROP) }
    var preserveRpmArc by remember { mutableStateOf(false) }
    val wallpaperMultiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(DashWallpaperPaths.MAX_SLOTS)
    ) { uris ->
        when (uris.size) {
            0 -> Unit
            1 -> {
                val uri = uris.first()
                pendingWallpaperUri = uri
                cropX = 0f
                cropY = 0f
                fitMode = DashWallpaperFit.CROP
                preserveRpmArc = false
                pendingWallpaperPreview = dashWallpaperPreviewFromUri(ctx, uri)
            }
            else -> {
                pendingWallpaperUri = null
                pendingWallpaperPreview = null
                dashViewModel.addWallpapersFromUris(uris)
            }
        }
    }
    val wallpaperPreview = remember(dashUi.wallpaperPath, dashUi.wallpaperKind) {
        dashUi.wallpaperPath?.let { path ->
            when (dashUi.wallpaperKind) {
                DashWallpaperKind.VIDEO -> dashWallpaperPreviewFromVideo(path)
                else -> BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }

    Column(modifier) {
        Eyebrow("Dash wallpaper", Modifier.padding(bottom = 6.dp, start = 4.dp))
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WallpaperIconBubble(OpenDashIcons.Moon)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            pendingWallpaperPreview != null -> if (pendingWallpaperUri == null) "Edit selected media" else "Adjust dash crop"
                            dashUi.wallpaperSaving -> "Saving wallpaper..."
                            dashUi.wallpaperPath == null -> "Default idle screen"
                            else -> "Gallery ${dashUi.wallpaperGalleryIndex + 1} of ${dashUi.wallpaperGalleryCount}"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        "Up to 5 images, GIFs, or videos. Videos are capped at 8 fps. Use joystick left/right while idle.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            dashUi.wallpaperError?.let { error ->
                Text(error, color = Alert, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
            if (pendingWallpaperPreview != null) {
                Spacer(Modifier.height(14.dp))
                DashWallpaperCropPreview(
                    image = pendingWallpaperPreview!!,
                    horizontalBias = cropX,
                    verticalBias = cropY,
                    fit = fitMode,
                    preserveRpmArc = preserveRpmArc,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OpenDashSegmented(
                    options = listOf("Crop", "Fit height", "Fit width"),
                    selected = fitMode.wallpaperLabel(),
                    onSelect = { fitMode = it.toDashWallpaperFit() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Eyebrow("Horizontal position")
                Slider(value = cropX, onValueChange = { cropX = it }, valueRange = -1f..1f)
                Eyebrow("Vertical position")
                Slider(value = cropY, onValueChange = { cropY = it }, valueRange = -1f..1f)
                AnalogLowerDisplayToggle(
                    checked = preserveRpmArc,
                    onCheckedChange = { preserveRpmArc = it },
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (wallpaperPreview != null) {
                Spacer(Modifier.height(14.dp))
                DashWallpaperCropPreview(
                    image = wallpaperPreview,
                    horizontalBias = dashUi.wallpaperCropX,
                    verticalBias = dashUi.wallpaperCropY,
                    fit = dashUi.wallpaperFit,
                    preserveRpmArc = dashUi.wallpaperPreserveRpmArc,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(dashUi.wallpaperGalleryCount) {
                            var dragX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragX = 0f },
                                onHorizontalDrag = { _, amount -> dragX += amount },
                                onDragEnd = {
                                    if (dashUi.wallpaperGalleryCount > 1 && kotlin.math.abs(dragX) > 60f) {
                                        dashViewModel.cycleWallpaperFromSettings(if (dragX < 0) 1 else -1)
                                    }
                                },
                            )
                        },
                    showGuide = true,
                )
                AnalogLowerDisplayToggle(
                    checked = dashUi.wallpaperPreserveRpmArc,
                    onCheckedChange = {
                        dashViewModel.updateCurrentWallpaperOptions(
                            dashUi.wallpaperCropX,
                            dashUi.wallpaperCropY,
                            dashUi.wallpaperFit,
                            it,
                        )
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pendingWallpaperPreview != null) {
                    OpenDashBtn(
                        if (pendingWallpaperUri == null) "Save edit" else "Save crop",
                        onClick = {
                            val uri = pendingWallpaperUri
                            if (uri != null) {
                                dashViewModel.setWallpaperFromUri(uri, cropX, cropY, fitMode, preserveRpmArc)
                            } else {
                                dashViewModel.updateCurrentWallpaperOptions(cropX, cropY, fitMode, preserveRpmArc)
                            }
                            pendingWallpaperUri = null
                            pendingWallpaperPreview = null
                        },
                        icon = OpenDashIcons.Check,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                    OpenDashBtn(
                        "Cancel",
                        onClick = {
                            pendingWallpaperUri = null
                            pendingWallpaperPreview = null
                        },
                        icon = OpenDashIcons.X,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    if (dashUi.wallpaperGalleryCount > 1) {
                        OpenDashIconBtn(OpenDashIcons.ChevronLeft, onClick = { dashViewModel.cycleWallpaperFromSettings(-1) }, size = 40.dp)
                    }
                    OpenDashBtn(
                        "Add media",
                        onClick = {
                            wallpaperMultiPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        icon = OpenDashIcons.Plus,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                    if (dashUi.wallpaperGalleryCount > 1) {
                        OpenDashIconBtn(OpenDashIcons.ChevronRight, onClick = { dashViewModel.cycleWallpaperFromSettings(1) }, size = 40.dp)
                    }
                    if (dashUi.wallpaperPath != null) {
                        OpenDashBtn(
                            "Edit",
                            onClick = {
                                pendingWallpaperUri = null
                                pendingWallpaperPreview = wallpaperPreview
                                cropX = dashUi.wallpaperCropX
                                cropY = dashUi.wallpaperCropY
                                fitMode = dashUi.wallpaperFit
                                preserveRpmArc = dashUi.wallpaperPreserveRpmArc
                            },
                            icon = OpenDashIcons.Edit,
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f),
                        )
                        OpenDashBtn(
                            "Remove",
                            onClick = {
                                pendingWallpaperUri = null
                                pendingWallpaperPreview = null
                                dashViewModel.clearWallpaper()
                            },
                            icon = OpenDashIcons.X,
                            variant = BtnVariant.Danger,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalogLowerDisplayToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "RPM-safe crop",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily,
            )
            Text(
                "Keeps media away from the tachometer arc. Native analog route text is controlled by dash firmware.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        OpenDashToggle(on = checked, onChange = onCheckedChange)
    }
}

@Composable
private fun WallpaperIconBubble(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

private fun dashWallpaperPreviewFromUri(
    context: android.content.Context,
    uri: Uri,
): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        } else {
            context.contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }
    }.getOrNull()

private fun dashWallpaperPreviewFromVideo(path: String): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)?.asImageBitmap()
        } finally {
            retriever.release()
        }
    }.getOrNull()

@Composable
private fun DashWallpaperCropPreview(
    image: androidx.compose.ui.graphics.ImageBitmap,
    horizontalBias: Float,
    verticalBias: Float,
    fit: DashWallpaperFit,
    preserveRpmArc: Boolean = false,
    modifier: Modifier = Modifier,
    showGuide: Boolean = true,
) {
    val guideColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .aspectRatio(526f / 300f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val targetLeft = if (preserveRpmArc) size.width * 0.09f else 0f
        val targetTop = if (preserveRpmArc) size.height * 0.64f else 0f
        val targetRight = if (preserveRpmArc) size.width * 0.96f else size.width
        val targetBottom = if (preserveRpmArc) size.height * 0.98f else size.height
        val targetWidth = targetRight - targetLeft
        val targetHeight = targetBottom - targetTop
        clipRect(targetLeft, targetTop, targetRight, targetBottom) {
            if (fit == DashWallpaperFit.CROP) {
                val srcRatio = image.width.toFloat() / image.height.toFloat()
                val dstRatio = targetWidth / targetHeight
                val (srcOffset, srcSize) = if (srcRatio > dstRatio) {
                    val cropW = (image.height * dstRatio).toInt().coerceAtLeast(1)
                    val extra = (image.width - cropW).coerceAtLeast(0)
                    val left = ((extra / 2f) + (extra / 2f) * horizontalBias.coerceIn(-1f, 1f)).toInt()
                    IntOffset(left, 0) to IntSize(cropW, image.height)
                } else {
                    val cropH = (image.width / dstRatio).toInt().coerceAtLeast(1)
                    val extra = (image.height - cropH).coerceAtLeast(0)
                    val top = ((extra / 2f) + (extra / 2f) * verticalBias.coerceIn(-1f, 1f)).toInt()
                    IntOffset(0, top) to IntSize(image.width, cropH)
                }
                drawImage(
                    image,
                    srcOffset,
                    srcSize,
                    IntOffset(targetLeft.toInt(), targetTop.toInt()),
                    IntSize(targetWidth.toInt(), targetHeight.toInt()),
                )
            } else {
                val scale = if (fit == DashWallpaperFit.FIT_HEIGHT) {
                    targetHeight / image.height.toFloat()
                } else {
                    targetWidth / image.width.toFloat()
                }
                val drawW = (image.width * scale).toInt().coerceAtLeast(1)
                val drawH = (image.height * scale).toInt().coerceAtLeast(1)
                drawImage(
                    image,
                    IntOffset.Zero,
                    IntSize(image.width, image.height),
                    IntOffset(
                        (targetLeft + (targetWidth - drawW) / 2f).toInt(),
                        (targetTop + (targetHeight - drawH) / 2f).toInt(),
                    ),
                    IntSize(drawW, drawH),
                )
            }
        }
        if (preserveRpmArc) drawDashWallpaperLowerDisplayGuide(guideColor)
        if (showGuide) drawDashWallpaperVisibilityGuide(guideColor)
    }
}

private fun DashWallpaperFit.wallpaperLabel(): String = when (this) {
    DashWallpaperFit.CROP -> "Crop"
    DashWallpaperFit.FIT_HEIGHT -> "Fit height"
    DashWallpaperFit.FIT_WIDTH -> "Fit width"
}

private fun String.toDashWallpaperFit(): DashWallpaperFit = when (this) {
    "Fit height" -> DashWallpaperFit.FIT_HEIGHT
    "Fit width" -> DashWallpaperFit.FIT_WIDTH
    else -> DashWallpaperFit.CROP
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashWallpaperVisibilityGuide(color: Color) {
    val visibleRect = androidx.compose.ui.geometry.Rect(
        left = size.width * 0.02f,
        top = 0f,
        right = size.width * 0.98f,
        bottom = size.height * 1.78f,
    )
    drawArc(
        color = color.copy(alpha = 0.95f),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = visibleRect.topLeft,
        size = androidx.compose.ui.geometry.Size(visibleRect.width, visibleRect.height),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashWallpaperLowerDisplayGuide(color: Color) {
    drawRect(
        color = color.copy(alpha = 0.95f),
        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.09f, size.height * 0.64f),
        size = androidx.compose.ui.geometry.Size(size.width * 0.87f, size.height * 0.34f),
        style = Stroke(width = 2.dp.toPx()),
    )
}
