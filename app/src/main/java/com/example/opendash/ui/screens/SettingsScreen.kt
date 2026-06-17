package com.example.opendash.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.example.opendash.BuildConfig
import com.example.opendash.dash.nav.provider.NavigationProviderPrefs
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
    val navProviderPrefs = remember { NavigationProviderPrefs(ctx) }
    var experimentalGoogleNav by remember { mutableStateOf(navProviderPrefs.experimentalGoogleNavEnabled) }
    var googleNavTermsAccepted by remember { mutableStateOf(navProviderPrefs.googleNavTermsAccepted) }
    val googleNavBuildReady = BuildConfig.GOOGLE_NAV_FLAVOR && BuildConfig.MAPS_API_KEY_PRESENT
    var googleNavWarning by remember {
        mutableStateOf(
            if (!BuildConfig.GOOGLE_NAV_FLAVOR) "Install the googleNav build to test Google Navigation SDK."
            else if (!BuildConfig.MAPS_API_KEY_PRESENT) "MAPS_API_KEY is missing. Add it to secrets.properties or local.properties."
            else null
        )
    }
    val preciseLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            experimentalGoogleNav = false
            navProviderPrefs.experimentalGoogleNavEnabled = false
            googleNavWarning = "Precise location permission is required for Google Navigation guidance."
        }
    }
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
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "More")

        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            MoreRow(
                OpenDashIcons.Sync,
                "Update from GitHub",
                updateMessage ?: "Check the latest OpenDash release",
                last = true,
                control = {
                    OpenDashBtn(
                        "Check",
                        onClick = { updateMessage = "OpenDash 1.2 is installed. Check GitHub Releases for newer builds." },
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                    )
                },
            )
        }

        SectionLabel("More")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            MoreRow(OpenDashIcons.Gear, "Settings", "Connection, ride, wallpaper, voice, units")
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Dash, "About", "OpenDash v1.2")
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Bell, "Help", "Connection and dash wallpaper guidance")
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Lock, "Terms & Conditions", "Usage terms")
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Flag, "License", "Open source notices")
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            MoreRow(OpenDashIcons.Cal, "Changelog", "Version history", last = true)
        }

        // Account card
        SectionLabel("Account")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp, onClick = {}) {
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
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(OpenDashIcons.Bt, "Tripper Dash",
                sub = when (conn) { ConnectionState.Connected -> "Connected"; ConnectionState.Searching -> "Connecting…"; ConnectionState.Offline -> "Not connected" },
                control = { OpenDashChip(if (conn == ConnectionState.Connected) "Linked" else "Off", if (conn == ConnectionState.Connected) ChipTone.Gold else ChipTone.Off, dot = true) })
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(OpenDashIcons.Sync, "Auto-connect on start", "Link when the bike is near",
                control = { OpenDashToggle(autoConnect) { autoConnect = it } })
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(OpenDashIcons.Zap, "Stream quality", "Balanced · saves battery",
                control = { Icon(OpenDashIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(18.dp)) }, last = true)
        }

        SectionLabel("During a ride")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(OpenDashIcons.Power, "Turn phone screen off", "Map keeps streaming to the dash",
                control = { OpenDashToggle(screenOff) { screenOff = it } })
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(OpenDashIcons.Dash, "Keep dash awake", "Prevent Tripper sleep",
                control = { OpenDashToggle(keepAwake) { keepAwake = it } }, last = true)
        }

        SectionLabel("Experimental navigation")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(
                OpenDashIcons.Navi,
                "Experimental Google Navigation",
                "Requires googleNav build, billing-enabled key, precise location, and Google terms acceptance",
                control = {
                    OpenDashToggle(experimentalGoogleNav) { enabled ->
                        if (enabled && !googleNavBuildReady) {
                            googleNavWarning = if (!BuildConfig.GOOGLE_NAV_FLAVOR) {
                                "Google Navigation SDK is not included in this OSS build."
                            } else {
                                "MAPS_API_KEY is missing or still DEFAULT_API_KEY."
                            }
                            experimentalGoogleNav = false
                            navProviderPrefs.experimentalGoogleNavEnabled = false
                        } else {
                            experimentalGoogleNav = enabled
                            navProviderPrefs.experimentalGoogleNavEnabled = enabled
                            if (enabled && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                preciseLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    }
                },
            )
            OpenDashDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                OpenDashIcons.Flag,
                "Accept Navigation SDK terms",
                "Required before Google guidance can start",
                control = {
                    OpenDashToggle(googleNavTermsAccepted) {
                        googleNavTermsAccepted = it
                        navProviderPrefs.googleNavTermsAccepted = it
                    }
                },
                last = true,
            )
            Text(
                googleNavWarning
                    ?: "Google guidance events may be used for text/ETA only. Google route geometry is not drawn on MapLibre/OpenFreeMap.",
                color = if (googleNavWarning == null) TextMid else Alert,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        SectionLabel("Dash Wallpaper")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(OpenDashIcons.Moon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            pendingWallpaperPreview != null -> if (pendingWallpaperUri == null) "Edit selected media" else "Adjust dash crop"
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
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            OpenDashSegmented(listOf("Off", "Chime", "Full TTS"), voice, {
                voiceManager.setMode(when (it) {
                    "Off"      -> com.example.opendash.dash.nav.VoiceMode.OFF
                    "Full TTS" -> com.example.opendash.dash.nav.VoiceMode.FULL
                    else       -> com.example.opendash.dash.nav.VoiceMode.CHIME
                })
            }, Modifier.fillMaxWidth())
        }

        SectionLabel("Units")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            OpenDashSegmented(listOf("Kilometres", "Miles"), units, { units = it }, Modifier.fillMaxWidth())
        }

        SectionLabel("Sync")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
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
            "OpenDash v1.2 · ${if (!auth.syncAvailable) "local only" else if (auth.isSignedIn) "sync on" else "sync off"}",
            color = TextDis, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    sub: String,
    last: Boolean = false,
    control: @Composable () -> Unit = {
        Icon(OpenDashIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(18.dp))
    },
) {
    SettingRow(icon = icon, title = title, sub = sub, control = control, last = last)
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
