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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.DashWallpaperKind
import com.example.opendash.data.DashWallpaperPaths
import com.example.opendash.viewmodel.AuthViewModel
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.DashViewModel

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
    val selectedTheme by OpenDashThemeController.variant.collectAsState()
    var pendingWallpaperUri by remember { mutableStateOf<Uri?>(null) }
    var pendingWallpaperPreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var cropX by remember { mutableFloatStateOf(0f) }
    var cropY by remember { mutableFloatStateOf(0f) }
    var fitMode by remember { mutableStateOf(DashWallpaperFit.CROP) }
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
                pendingWallpaperPreview = wallpaperPreviewFromUri(ctx, uri)
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
                DashWallpaperKind.VIDEO -> wallpaperPreviewFromVideo(path)
                else -> BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }

    // Real voice setting, shared with RouteScreen via the VoiceManager singleton.
    val voiceManager = remember { com.example.opendash.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        com.example.opendash.dash.nav.VoiceMode.OFF   -> "Off"
        com.example.opendash.dash.nav.VoiceMode.CHIME -> "Chime"
        com.example.opendash.dash.nav.VoiceMode.FULL  -> "Full TTS"
    }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp, top = 10.dp),
        ) {
            Text(
                "Settings",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistFamily,
                modifier = Modifier.weight(1f),
            )
            Icon(OpenDashIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }

        SettingsGroup(padding = 6.dp) {
            MoreRow(
                OpenDashIcons.Sync,
                "Update from GitHub",
                updateMessage ?: "Check the latest OpenDash release",
                last = true,
                control = {
                    OpenDashBtn(
                        "Check",
                        onClick = { updateMessage = "OpenDash 1.3 is installed. Check GitHub Releases for newer builds." },
                        variant = BtnVariant.Secondary,
                        size = BtnSize.Sm,
                    )
                },
            )
        }

        SectionLabel("General")
        SettingsGroup(padding = 6.dp) {
            MoreRow(OpenDashIcons.Gear, "Settings", "Connection, ride, wallpaper, voice, units")
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Dash, "About", "OpenDash v1.3")
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Bell, "Help", "Connection and dash wallpaper guidance")
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Lock, "Terms & Conditions", "Usage terms")
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Flag, "License", "Open source notices")
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Cal, "Changelog", "Version history", last = true)
        }

        // Account card
        SectionLabel("Account")
        SettingsGroup(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBubble(OpenDashIcons.Person)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    auth.displayName?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    }
                    Text(email, color = if (auth.displayName.isNullOrBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = if (auth.displayName.isNullOrBlank()) 15.5.sp else 12.5.sp, fontWeight = if (auth.displayName.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal, fontFamily = GeistFamily, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(OpenDashIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }

        SectionLabel("Connection")
        SettingsGroup(padding = 6.dp) {
            SettingRow(OpenDashIcons.Bt, "Tripper Dash",
                sub = when (conn) { ConnectionState.Connected -> "Connected"; ConnectionState.Searching -> "Connecting…"; ConnectionState.Offline -> "Not connected" },
                control = { OpenDashChip(if (conn == ConnectionState.Connected) "Linked" else "Off", if (conn == ConnectionState.Connected) ChipTone.Gold else ChipTone.Off, dot = true) })
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(OpenDashIcons.Sync, "Auto-connect on start", "Link when the bike is near",
                control = { SettingsToggle(autoConnect) { autoConnect = it } })
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(OpenDashIcons.Zap, "Stream quality", "Balanced · saves battery",
                control = { Icon(OpenDashIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }, last = true)
        }

        SectionLabel("During a ride")
        SettingsGroup(padding = 6.dp) {
            SettingRow(OpenDashIcons.Power, "Turn phone screen off", "Map keeps streaming to the dash",
                control = { SettingsToggle(screenOff) { screenOff = it } })
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(OpenDashIcons.Dash, "Keep dash awake", "Prevent Tripper sleep",
                control = { SettingsToggle(keepAwake) { keepAwake = it } }, last = true)
        }

        SectionLabel("Theming")
        SettingsGroup(padding = 14.dp) {
            SettingRow(
                icon = OpenDashIcons.Palette,
                title = "App-wide theme",
                sub = selectedTheme.theme,
                control = { OpenDashChip("Live", ChipTone.Gold) },
                last = true,
            )
            Spacer(Modifier.height(10.dp))
            OpenDashThemeVariants.forEach { variant ->
                ThemePaletteRow(
                    variant = variant,
                    selected = selectedTheme.name == variant.name,
                    onClick = {
                        OpenDashThemeController.select(ctx, variant)
                    },
                )
            }
        }

        SectionLabel("Dash Wallpaper")
        SettingsGroup(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBubble(OpenDashIcons.Moon)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            pendingWallpaperPreview != null -> if (pendingWallpaperUri == null) "Edit selected media" else "Adjust dash crop"
                            dashUi.wallpaperSaving -> "Saving wallpaper…"
                            dashUi.wallpaperPath == null -> "Default idle screen"
                            else -> "Gallery ${dashUi.wallpaperGalleryIndex + 1} of ${dashUi.wallpaperGalleryCount}"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        "Up to 5 images, GIFs, or videos. Use joystick left/right while idle.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    fit = fitMode,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OpenDashSegmented(
                    options = listOf("Crop", "Fit height", "Fit width"),
                    selected = fitMode.label(),
                    onSelect = { fitMode = it.toWallpaperFit() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
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
                    fit = dashUi.wallpaperFit,
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
                    OpenDashBtn(
                        if (pendingWallpaperUri == null) "Save edit" else "Save crop",
                        onClick = {
                            val uri = pendingWallpaperUri
                            if (uri != null) {
                                dashViewModel.setWallpaperFromUri(uri, cropX, cropY, fitMode)
                            } else {
                                dashViewModel.updateCurrentWallpaperOptions(cropX, cropY, fitMode)
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
                } else if (pendingWallpaperPreview != null) {
                    OpenDashBtn(
                        "Save edit",
                        onClick = {
                            dashViewModel.updateCurrentWallpaperOptions(cropX, cropY, fitMode)
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
                        OpenDashIconBtn(
                            icon = OpenDashIcons.ChevronLeft,
                            onClick = { dashViewModel.cycleWallpaperFromSettings(-1) },
                            size = 40.dp,
                        )
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
                        OpenDashIconBtn(
                            icon = OpenDashIcons.ChevronRight,
                            onClick = { dashViewModel.cycleWallpaperFromSettings(1) },
                            size = 40.dp,
                        )
                    }
                    if (dashUi.wallpaperPath != null) {
                        OpenDashBtn(
                            "Edit current",
                            onClick = {
                                pendingWallpaperUri = null
                                pendingWallpaperPreview = wallpaperPreview
                                cropX = dashUi.wallpaperCropX
                                cropY = dashUi.wallpaperCropY
                                fitMode = dashUi.wallpaperFit
                            },
                            icon = OpenDashIcons.Edit,
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f),
                        )
                        OpenDashBtn(
                            "Remove",
                            onClick = { dashViewModel.clearWallpaper() },
                            icon = OpenDashIcons.X,
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        SectionLabel("Voice & guidance")
        SettingsGroup(padding = 14.dp) {
            OpenDashSegmented(listOf("Off", "Chime", "Full TTS"), voice, {
                voiceManager.setMode(when (it) {
                    "Off"      -> com.example.opendash.dash.nav.VoiceMode.OFF
                    "Full TTS" -> com.example.opendash.dash.nav.VoiceMode.FULL
                    else       -> com.example.opendash.dash.nav.VoiceMode.CHIME
                })
            }, Modifier.fillMaxWidth())
        }

        SectionLabel("Units")
        SettingsGroup(padding = 14.dp) {
            OpenDashSegmented(listOf("Kilometres", "Miles"), units, { units = it }, Modifier.fillMaxWidth())
        }

        SectionLabel("Sync")
        SettingsGroup(padding = 6.dp) {
            val (syncTitle, syncSub) = when {
                !auth.syncAvailable -> "Local only" to "Add your own Firebase project to sync across devices"
                auth.isSignedIn     -> "Synced" to (auth.email ?: "Signed in")
                else                -> "Not signed in" to "Sign in to sync across devices · data stays local until then"
            }
            SettingRow(OpenDashIcons.Sync, syncTitle, syncSub,
                control = {
                    OpenDashChip(
                        if (auth.isSignedIn) "On" else "Off",
                        if (auth.isSignedIn) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                }, last = true)
        }

        Spacer(Modifier.height(22.dp))

        if (auth.isSignedIn) {
            OpenDashBtn(
                "Sign out",
                onClick = { authViewModel.signOut(); onSignedOut() },
                icon = OpenDashIcons.Power,
                variant = BtnVariant.Danger,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        Text(
            "OpenDash v1.3 · ${if (!auth.syncAvailable) "local only" else if (auth.isSignedIn) "sync on" else "sync off"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

@Composable
private fun SettingsGroup(
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
private fun SettingsIconBubble(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun SettingsToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun ThemePaletteRow(
    variant: OpenDashThemeVariant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(variant.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            Text(variant.theme, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                variant.colors.forEach { color ->
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, Color.White.copy(alpha = 0.75f), CircleShape),
                    )
                }
            }
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp).clip(CircleShape).background(variant.accentStrong),
            ) {
                Icon(OpenDashIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    sub: String,
    last: Boolean = false,
    control: @Composable () -> Unit = {
        Icon(OpenDashIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    },
) {
    SettingRow(icon = icon, title = title, sub = sub, control = control, last = last)
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = GeistFamily,
        modifier = Modifier.padding(top = 22.dp, bottom = 9.dp, start = 2.dp),
    )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
    ) {
        SettingsIconBubble(icon, modifier = Modifier.size(42.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (sub != null) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    fit: DashWallpaperFit,
    modifier: Modifier = Modifier,
    showGuide: Boolean = true,
) {
    Canvas(
        modifier = modifier
            .aspectRatio(526f / 300f)
            .clip(RoundedCornerShape(8.dp))
            .background(Bg0),
    ) {
        if (fit == DashWallpaperFit.CROP) {
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
        } else {
            val scale = if (fit == DashWallpaperFit.FIT_HEIGHT) {
                size.height / image.height.toFloat()
            } else {
                size.width / image.width.toFloat()
            }
            val drawW = (image.width * scale).toInt().coerceAtLeast(1)
            val drawH = (image.height * scale).toInt().coerceAtLeast(1)
            val left = ((size.width.toInt() - drawW) / 2)
            val top = ((size.height.toInt() - drawH) / 2)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(drawW, drawH),
            )
        }
        if (showGuide) {
            drawDashVisibilityGuide()
        }
    }
}

private fun DashWallpaperFit.label(): String = when (this) {
    DashWallpaperFit.CROP -> "Crop"
    DashWallpaperFit.FIT_HEIGHT -> "Fit height"
    DashWallpaperFit.FIT_WIDTH -> "Fit width"
}

private fun String.toWallpaperFit(): DashWallpaperFit = when (this) {
    "Fit height" -> DashWallpaperFit.FIT_HEIGHT
    "Fit width" -> DashWallpaperFit.FIT_WIDTH
    else -> DashWallpaperFit.CROP
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
