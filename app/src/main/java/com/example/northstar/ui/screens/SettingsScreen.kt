package com.example.northstar.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.data.DashWallpaperKind
import com.example.northstar.data.DashWallpaperPaths
import com.example.northstar.viewmodel.AuthViewModel
import com.example.northstar.viewmodel.ConnectionState
import com.example.northstar.viewmodel.DashViewModel

@Composable
fun SettingsScreen(
    conn: ConnectionState,
    onConnChange: (ConnectionState) -> Unit,
    authViewModel: AuthViewModel,
    dashViewModel: DashViewModel,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val auth by authViewModel.state.collectAsState()
    val dashUi by dashViewModel.ui.collectAsState()
    val email = auth.email ?: "Not signed in"
    val initials = remember(auth.email, auth.displayName) {
        val src = auth.displayName?.takeIf { it.isNotBlank() } ?: auth.email ?: "?"
        src.split(" ", ".", "@").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    }

    var autoConnect by remember { mutableStateOf(true) }
    var screenOff   by remember { mutableStateOf(true) }
    var keepAwake   by remember { mutableStateOf(true) }
    var units       by remember { mutableStateOf("Kilometres") }
    val ctx = LocalContext.current
    var pendingWallpaperUri by remember { mutableStateOf<Uri?>(null) }
    var pendingWallpaperPreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var cropX by remember { mutableFloatStateOf(0f) }
    var cropY by remember { mutableFloatStateOf(0f) }
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingWallpaperUri = uri
            cropX = 0f
            cropY = 0f
            pendingWallpaperPreview = wallpaperPreviewFromUri(ctx, uri)
        }
    }
    val wallpaperMultiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(DashWallpaperPaths.MAX_SLOTS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingWallpaperUri = null
            pendingWallpaperPreview = null
            dashViewModel.addWallpapersFromUris(uris)
        }
    }
    val wallpaperPreview = remember(dashUi.wallpaperPath, dashUi.wallpaperKind) {
        dashUi.wallpaperPath?.let { path ->
            when (dashUi.wallpaperKind) {
                DashWallpaperKind.VIDEO -> wallpaperPreviewFromVideo(path)
                else -> BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }

    // Real voice setting, shared with RouteScreen via the VoiceManager singleton.
    val voiceManager = remember { com.example.northstar.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        com.example.northstar.dash.nav.VoiceMode.OFF   -> "Off"
        com.example.northstar.dash.nav.VoiceMode.CHIME -> "Chime"
        com.example.northstar.dash.nav.VoiceMode.FULL  -> "Full TTS"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        // Account card
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp, onClick = {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Text(initials, color = MaterialTheme.colorScheme.primary, fontFamily = GeistMonoFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    auth.displayName?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextHi, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    }
                    Text(email, color = if (auth.displayName.isNullOrBlank()) TextHi else TextMid, fontSize = if (auth.displayName.isNullOrBlank()) 15.5.sp else 12.5.sp, fontWeight = if (auth.displayName.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal, fontFamily = GeistFamily, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        SectionLabel("Connection")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(NorthstarIcons.Bt, "Tripper Dash",
                sub = when (conn) { ConnectionState.Connected -> "Connected"; ConnectionState.Searching -> "Connecting…"; ConnectionState.Offline -> "Not connected" },
                control = { NorthstarChip(if (conn == ConnectionState.Connected) "Linked" else "Off", if (conn == ConnectionState.Connected) ChipTone.Gold else ChipTone.Off, dot = true) })
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NorthstarIcons.Sync, "Auto-connect on start", "Link when the bike is near",
                control = { NorthstarToggle(autoConnect) { autoConnect = it } })
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NorthstarIcons.Zap, "Stream quality", "Balanced · saves battery",
                control = { Icon(NorthstarIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(18.dp)) }, last = true)
        }

        SectionLabel("During a ride")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(NorthstarIcons.Power, "Turn phone screen off", "Map keeps streaming to the dash",
                control = { NorthstarToggle(screenOff) { screenOff = it } })
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NorthstarIcons.Dash, "Keep dash awake", "Prevent Tripper sleep",
                control = { NorthstarToggle(keepAwake) { keepAwake = it } }, last = true)
        }

        SectionLabel("Dash Wallpaper")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(NorthstarIcons.Moon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            pendingWallpaperPreview != null -> "Adjust dash crop"
                            dashUi.wallpaperSaving -> "Saving wallpaper…"
                            dashUi.wallpaperPath == null -> "Default idle screen"
                            else -> "Gallery ${dashUi.wallpaperGalleryIndex + 1} of ${dashUi.wallpaperGalleryCount}"
                        },
                        color = TextHi,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        "Up to 5 images, GIFs, or videos. Use joystick left/right while idle.",
                        color = TextLo,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            dashUi.wallpaperError?.let { error ->
                Text(
                    error,
                    color = Alert,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (pendingWallpaperPreview != null) {
                Spacer(Modifier.height(14.dp))
                DashCropPreview(
                    image = pendingWallpaperPreview!!,
                    horizontalBias = cropX,
                    verticalBias = cropY,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Eyebrow("Horizontal position")
                Slider(value = cropX, onValueChange = { cropX = it }, valueRange = -1f..1f)
                Eyebrow("Vertical position")
                Slider(value = cropY, onValueChange = { cropY = it }, valueRange = -1f..1f)
            } else if (wallpaperPreview != null) {
                Spacer(Modifier.height(14.dp))
                DashCropPreview(
                    image = wallpaperPreview,
                    horizontalBias = dashUi.wallpaperCropX,
                    verticalBias = dashUi.wallpaperCropY,
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
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pendingWallpaperPreview != null && pendingWallpaperUri != null) {
                    NorthstarBtn(
                        "Save crop",
                        onClick = {
                            dashViewModel.setWallpaperFromUri(pendingWallpaperUri!!, cropX, cropY)
                            pendingWallpaperUri = null
                            pendingWallpaperPreview = null
                        },
                        icon = NorthstarIcons.Check,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                    NorthstarBtn(
                        "Cancel",
                        onClick = {
                            pendingWallpaperUri = null
                            pendingWallpaperPreview = null
                        },
                        icon = NorthstarIcons.X,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    if (dashUi.wallpaperGalleryCount > 1) {
                        NorthstarIconBtn(
                            icon = NorthstarIcons.ChevronLeft,
                            onClick = { dashViewModel.cycleWallpaperFromSettings(-1) },
                            size = 40.dp,
                        )
                    }
                    NorthstarBtn(
                        if (dashUi.wallpaperGalleryCount >= 5) "Replace current" else "Add media",
                        onClick = {
                            wallpaperPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        icon = NorthstarIcons.Edit,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                    NorthstarBtn(
                        "Add many",
                        onClick = {
                            wallpaperMultiPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        icon = NorthstarIcons.Edit,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                    if (dashUi.wallpaperGalleryCount > 1) {
                        NorthstarIconBtn(
                            icon = NorthstarIcons.ChevronRight,
                            onClick = { dashViewModel.cycleWallpaperFromSettings(1) },
                            size = 40.dp,
                        )
                    }
                    if (dashUi.wallpaperPath != null) {
                        NorthstarBtn(
                            "Remove",
                            onClick = { dashViewModel.clearWallpaper() },
                            icon = NorthstarIcons.X,
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        SectionLabel("Voice & guidance")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(listOf("Off", "Chime", "Full TTS"), voice, {
                voiceManager.setMode(when (it) {
                    "Off"      -> com.example.northstar.dash.nav.VoiceMode.OFF
                    "Full TTS" -> com.example.northstar.dash.nav.VoiceMode.FULL
                    else       -> com.example.northstar.dash.nav.VoiceMode.CHIME
                })
            }, Modifier.fillMaxWidth())
        }

        SectionLabel("Units")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(listOf("Kilometres", "Miles"), units, { units = it }, Modifier.fillMaxWidth())
        }

        SectionLabel("Sync")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            val (syncTitle, syncSub) = when {
                !auth.syncAvailable -> "Local only" to "Add your own Firebase project to sync across devices"
                auth.isSignedIn     -> "Synced" to (auth.email ?: "Signed in")
                else                -> "Not signed in" to "Sign in to sync across devices · data stays local until then"
            }
            SettingRow(NorthstarIcons.Sync, syncTitle, syncSub,
                control = {
                    NorthstarChip(
                        if (auth.isSignedIn) "On" else "Off",
                        if (auth.isSignedIn) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                }, last = true)
        }

        Spacer(Modifier.height(22.dp))

        if (auth.isSignedIn) {
            NorthstarBtn(
                "Sign out",
                onClick = { authViewModel.signOut(); onSignedOut() },
                icon = NorthstarIcons.Power,
                variant = BtnVariant.Danger,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        Text(
            "NORTHSTAR v1.0 · ${if (!auth.syncAvailable) "local only" else if (auth.isSignedIn) "sync on" else "sync off"}",
            color = TextDis, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Eyebrow(label, Modifier.padding(top = 22.dp, bottom = 9.dp, start = 4.dp))
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    control: @Composable () -> Unit,
    last: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 13.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(38.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (sub != null) Text(sub, color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
}

private fun wallpaperPreviewFromUri(
    context: android.content.Context,
    uri: Uri,
): androidx.compose.ui.graphics.ImageBitmap? {
    val mime = context.contentResolver.getType(uri).orEmpty()
    return if (mime.startsWith("video/")) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.asImageBitmap()
            } finally {
                retriever.release()
            }
        }.getOrNull()
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        } else {
            context.contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }
    }
}

private fun wallpaperPreviewFromVideo(path: String): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?.asImageBitmap()
        } finally {
            retriever.release()
        }
    }.getOrNull()

@Composable
private fun DashCropPreview(
    image: androidx.compose.ui.graphics.ImageBitmap,
    horizontalBias: Float,
    verticalBias: Float,
    modifier: Modifier = Modifier,
    showGuide: Boolean = true,
) {
    Canvas(
        modifier = modifier
            .aspectRatio(526f / 300f)
            .clip(RoundedCornerShape(8.dp))
            .background(Bg0),
    ) {
        val srcRatio = image.width.toFloat() / image.height.toFloat()
        val dstRatio = size.width / size.height
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
            image = image,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
        if (showGuide) {
            drawDashVisibilityGuide()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashVisibilityGuide() {
    val visibleRect = androidx.compose.ui.geometry.Rect(
        left = size.width * 0.02f,
        top = 0f,
        right = size.width * 0.98f,
        bottom = size.height * 1.78f,
    )
    drawArc(
        color = Gold.copy(alpha = 0.95f),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(visibleRect.left, visibleRect.top),
        size = Size(visibleRect.width, visibleRect.height),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawLine(
        color = Gold.copy(alpha = 0.7f),
        start = Offset(size.width * 0.02f, size.height - 1f),
        end = Offset(size.width * 0.98f, size.height - 1f),
        strokeWidth = 2.dp.toPx(),
    )
}
