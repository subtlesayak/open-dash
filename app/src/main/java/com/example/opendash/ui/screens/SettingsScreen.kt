package com.example.opendash.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.opendash.BuildConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.DashWallpaperKind
import com.example.opendash.data.DashWallpaperPaths
import com.example.opendash.data.CurrencySettings
import com.example.opendash.data.OpenDashCurrency
import com.example.opendash.viewmodel.AuthViewModel
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.DashViewModel

private enum class MorePage(val title: String) {
    ROOT("More"),
    SETTINGS("Settings"),
    ABOUT("About"),
    HELP("Help"),
    TERMS("Terms & Conditions"),
    LICENSE("License"),
    CHANGELOG("Changelog"),
}

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
    val lifecycleOwner = LocalLifecycleOwner.current
    fun callPermissionGranted(): Boolean = ContextCompat.checkSelfPermission(
        ctx,
        android.Manifest.permission.ANSWER_PHONE_CALLS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var mediaAccessGranted by remember {
        mutableStateOf(com.example.opendash.media.MediaInfoProvider.isAccessGranted(ctx))
    }
    var callAccessGranted by remember { mutableStateOf(callPermissionGranted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mediaAccessGranted = com.example.opendash.media.MediaInfoProvider.isAccessGranted(ctx)
                callAccessGranted = callPermissionGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { callAccessGranted = it }
    val selectedTheme by OpenDashThemeController.variant.collectAsState()
    remember(ctx) {
        CurrencySettings.init(ctx)
        true
    }
    val selectedCurrency by CurrencySettings.currency.collectAsState()
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var currencyMenuExpanded by remember { mutableStateOf(false) }
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
    var page by remember { mutableStateOf(MorePage.ROOT) }
    BackHandler(enabled = page != MorePage.ROOT) { page = MorePage.ROOT }

    if (page != MorePage.ROOT && page != MorePage.SETTINGS) {
        MoreInformationPage(page = page, onBack = { page = MorePage.ROOT })
        return
    }

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
            if (page == MorePage.SETTINGS) {
                OpenDashIconBtn(
                    icon = OpenDashIcons.ChevronLeft,
                    onClick = { page = MorePage.ROOT },
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Text(
                page.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistFamily,
                modifier = Modifier.weight(1f),
            )
            if (page == MorePage.ROOT) {
                Icon(OpenDashIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }
        }

        if (page == MorePage.ROOT) {
            SettingsGroup(padding = 6.dp) {
                MoreRow(
                    OpenDashIcons.Sync,
                    "Update from GitHub",
                    updateMessage ?: "Check the latest OpenDash release",
                    last = true,
                    control = {
                        OpenDashBtn(
                            "Check",
                            onClick = { updateMessage = "OpenDash ${BuildConfig.VERSION_NAME} is installed. Check GitHub Releases for newer builds." },
                            variant = BtnVariant.Secondary,
                            size = BtnSize.Sm,
                        )
                    },
                )
            }

            SectionLabel("General")
            SettingsGroup(padding = 6.dp) {
                MoreRow(OpenDashIcons.Gear, "Settings", "Connection, ride, wallpaper, voice, units", onClick = { page = MorePage.SETTINGS })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Dash, "About", "OpenDash v${BuildConfig.VERSION_NAME}", onClick = { page = MorePage.ABOUT })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Bell, "Help", "Connection and dash wallpaper guidance", onClick = { page = MorePage.HELP })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Lock, "Terms & Conditions", "Usage terms", onClick = { page = MorePage.TERMS })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Flag, "License", "Open source notices", onClick = { page = MorePage.LICENSE })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Cal, "Changelog", "1.3 stable: expressive UI, themes, vehicles, garage", last = true, onClick = { page = MorePage.CHANGELOG })
            }
            return@Column
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
            SettingRow(OpenDashIcons.Bt, "Bike dash",
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
            SettingRow(OpenDashIcons.Dash, "Keep dash awake", "Prevent dash sleep",
                control = { SettingsToggle(keepAwake) { keepAwake = it } }, last = true)
        }

        SectionLabel("Media & calls on dash")
        SettingsGroup(padding = 6.dp) {
            SettingRow(
                OpenDashIcons.Bell,
                "Now playing & caller cards",
                if (mediaAccessGranted) "Enabled for active media and calls" else "Allow notification access",
                control = {
                    OpenDashChip(
                        if (mediaAccessGranted) "On" else "Enable",
                        if (mediaAccessGranted) ChipTone.Gold else ChipTone.Off,
                        dot = true,
                    )
                },
                onClick = if (mediaAccessGranted) null else {
                    {
                        runCatching {
                            ctx.startActivity(
                                com.example.opendash.media.MediaInfoProvider.accessSettingsIntent(),
                            )
                        }
                    }
                },
            )
            SettingsDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                OpenDashIcons.Bt,
                "Answer calls from joystick",
                if (callAccessGranted) "UP answers; DOWN rejects or ends" else "Allow call controls",
                control = {
                    OpenDashChip(
                        if (callAccessGranted) "On" else "Enable",
                        if (callAccessGranted) ChipTone.Gold else ChipTone.Off,
                        dot = true,
                    )
                },
                onClick = if (callAccessGranted) null else {
                    { callPermissionLauncher.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }
                },
                last = true,
            )
        }

        SectionLabel("Theming")
        SettingsGroup(padding = 6.dp) {
            Box(Modifier.fillMaxWidth()) {
                SettingRow(
                    icon = OpenDashIcons.Palette,
                    title = selectedTheme.name,
                    sub = selectedTheme.theme,
                    control = {
                        Icon(
                            OpenDashIcons.ChevronRight,
                            contentDescription = "Choose theme",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    last = true,
                    onClick = { themeMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = themeMenuExpanded,
                    onDismissRequest = { themeMenuExpanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    OpenDashThemeVariants.forEach { variant ->
                        DropdownMenuItem(
                            modifier = Modifier
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .border(
                                    1.dp,
                                    if (selectedTheme.name == variant.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(16.dp),
                                ),
                            text = {
                                Column {
                                    Text(
                                        variant.name,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GeistFamily,
                                    )
                                    Text(
                                        variant.theme,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.padding(top = 6.dp),
                                    ) {
                                        variant.colors.forEach { color ->
                                            Box(
                                                Modifier
                                                    .size(15.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                                    .border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                                        CircleShape,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            },
                            trailingIcon = {
                                if (selectedTheme.name == variant.name) {
                                    Icon(
                                        OpenDashIcons.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = {
                                OpenDashThemeController.select(ctx, variant)
                                themeMenuExpanded = false
                            },
                        )
                    }
                }
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
                        "Up to 5 images, GIFs, or videos. Videos are capped at 8 fps. Use joystick left/right while idle.",
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

        SectionLabel("Currency")
        SettingsGroup(padding = 6.dp) {
            Box(Modifier.fillMaxWidth()) {
                SettingRow(
                    icon = OpenDashIcons.Chart,
                    title = selectedCurrency.code,
                    sub = "${selectedCurrency.displayName} · ${selectedCurrency.symbol}",
                    control = {
                        Icon(
                            OpenDashIcons.ChevronRight,
                            contentDescription = "Choose currency",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    last = true,
                    onClick = { currencyMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = currencyMenuExpanded,
                    onDismissRequest = { currencyMenuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    OpenDashCurrency.entries.forEach { currency ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        currency.code,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GeistFamily,
                                    )
                                    Text(
                                        "${currency.displayName} · ${currency.symbol}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                            },
                            trailingIcon = if (currency == selectedCurrency) {
                                { Icon(OpenDashIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                CurrencySettings.select(ctx, currency)
                                currencyMenuExpanded = false
                            },
                        )
                    }
                }
            }
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
            "OpenDash v${BuildConfig.VERSION_NAME} · ${if (!auth.syncAvailable) "local only" else if (auth.isSignedIn) "sync on" else "sync off"}",
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
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
private fun MoreRow(
    icon: ImageVector,
    title: String,
    sub: String,
    last: Boolean = false,
    control: @Composable () -> Unit = {
        Icon(OpenDashIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    },
    onClick: (() -> Unit)? = null,
) {
    SettingRow(icon = icon, title = title, sub = sub, control = control, last = last, onClick = onClick)
}

@Composable
private fun MoreInformationPage(page: MorePage, onBack: () -> Unit) {
    val sections = when (page) {
        MorePage.ABOUT -> listOf(
            "OpenDash v${BuildConfig.VERSION_NAME}" to "Open-source navigation, ride management, dash wallpapers, media cards, and call controls for compatible bike displays.",
            "Privacy" to "OpenDash works locally by default. Dash credentials use encrypted preferences and wallpaper media stays in app-private storage.",
        )
        MorePage.HELP -> listOf(
            "Connect to dash" to "Turn on the bike, choose Connect to dash, select the RE_* network, and confirm the exact SSID before it is saved.",
            "Navigation" to "Share a destination from Google Maps, review the route, start navigation, and connect the dash. Active navigation keeps its existing projection behavior.",
            "Dash wallpaper" to "Add up to five images, GIFs, or MP4 videos. Video playback is capped at 8 fps. Crop with the display guide, then use joystick left/right while the dash is idle.",
            "Media and calls" to "Grant notification access for now-playing and caller cards. Grant call-control permission separately to answer with UP and reject or end with DOWN.",
        )
        MorePage.TERMS -> listOf(
            "Independent project" to "OpenDash is independent and community-built. The dash protocol is unofficial and reverse-engineered.",
            "Ride responsibly" to "Configure the app before riding. Do not interact with the phone in motion, and always follow local laws and road conditions.",
            "No warranty" to "The software is provided without warranty. Compatibility can vary by Android device, vehicle firmware, and connected applications.",
        )
        MorePage.LICENSE -> listOf(
            "OpenDash" to "Distributed under the license included with the source repository.",
            "Open-source components" to "OpenDash uses Kotlin, Jetpack Compose, MapLibre, OpenFreeMap, OSRM, AndroidX, and other libraries under their respective licenses.",
            "Source" to "github.com/subtlesayak/open-dash",
        )
        MorePage.CHANGELOG -> listOf(
            "1.3 stable" to listOf(
                "Material 3 Expressive UI refresh across the app.",
                "Added Dynamic Wallpaper theming as the first theme option.",
                "Added Auto Day/Night black-and-white theme as the second theme option.",
                "Kept motorcycle theme palettes available after the dynamic and auto modes.",
                "Improved app-wide theme contrast so Garage, dialogs, cards, buttons, and spare-part rows stay readable across light, dynamic, and motorcycle themes.",
                "Active vehicle selection with vehicle-specific garage and expense data.",
                "New expenses now default to the current selected vehicle without an editable vehicle field.",
                "Redesigned Garage with editable odometer and average mileage from the latest five fill-ups.",
                "Spare-part details, interval editing, history, and service logging.",
                "Fuel entries no longer delete when tapped.",
                "Monthly and all-time expense filtering and sharing.",
                "Dash wallpaper video decoding capped at 8 FPS.",
                "Improved navigation transitions and general UI fixes.",
            ).joinToString("\n") { "- $it" },
        )
        else -> emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = page.title, onBack = onBack)
        sections.forEach { (title, body) ->
            SettingsGroup(modifier = Modifier.padding(bottom = 12.dp), padding = 16.dp) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
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
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 13.dp),
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
    val guideColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .aspectRatio(526f / 300f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
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
            drawDashVisibilityGuide(guideColor)
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashVisibilityGuide(color: Color) {
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
        topLeft = Offset(visibleRect.left, visibleRect.top),
        size = Size(visibleRect.width, visibleRect.height),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawLine(
        color = color.copy(alpha = 0.7f),
        start = Offset(size.width * 0.02f, size.height - 1f),
        end = Offset(size.width * 0.98f, size.height - 1f),
        strokeWidth = 2.dp.toPx(),
    )
}
