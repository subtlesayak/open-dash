package com.example.opendash.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.example.opendash.BuildConfig
import com.example.opendash.data.CurrencySettings
import com.example.opendash.data.MapboxNavigationSettings
import com.example.opendash.data.OpenDashCurrency
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.AuthViewModel
import com.example.opendash.viewmodel.ConnectionState
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

private enum class MorePage(val title: String) {
    ROOT("More"),
    SETTINGS("Settings"),
    APPEARANCE("Appearance"),
    ABOUT("About"),
    HELP("Help"),
    TERMS("Terms & Conditions"),
    LICENSE("License"),
    CHANGELOG("Changelog"),
}

private data class WallpaperAsset(
    val title: String,
    val fileName: String,
    val outputName: String,
)

private val downloadableWallpapers = listOf(
    WallpaperAsset("Wallpaper 01", "wallpapers/opendash_wallpaper_01.png", "OpenDash-Wallpaper-01.png"),
    WallpaperAsset("Wallpaper 02", "wallpapers/opendash_wallpaper_02.png", "OpenDash-Wallpaper-02.png"),
    WallpaperAsset("Wallpaper 03", "wallpapers/opendash_wallpaper_03.png", "OpenDash-Wallpaper-03.png"),
    WallpaperAsset("Wallpaper 04", "wallpapers/opendash_wallpaper_04.png", "OpenDash-Wallpaper-04.png"),
    WallpaperAsset("Wallpaper 05", "wallpapers/opendash_wallpaper_05.png", "OpenDash-Wallpaper-05.png"),
    WallpaperAsset("Wallpaper 06", "wallpapers/opendash_wallpaper_06.png", "OpenDash-Wallpaper-06.png"),
    WallpaperAsset("Wallpaper 07", "wallpapers/opendash_wallpaper_07.png", "OpenDash-Wallpaper-07.png"),
    WallpaperAsset("Wallpaper 08", "wallpapers/opendash_wallpaper_08.png", "OpenDash-Wallpaper-08.png"),
    WallpaperAsset("Wallpaper 09", "wallpapers/opendash_wallpaper_09.png", "OpenDash-Wallpaper-09.png"),
    WallpaperAsset("Wallpaper 10", "wallpapers/opendash_wallpaper_10.png", "OpenDash-Wallpaper-10.png"),
    WallpaperAsset("Wallpaper 11", "wallpapers/opendash_wallpaper_11.png", "OpenDash-Wallpaper-11.png"),
    WallpaperAsset("Wallpaper 12", "wallpapers/opendash_wallpaper_12.png", "OpenDash-Wallpaper-12.png"),
)

@Composable
fun SettingsScreen(
    conn: ConnectionState,
    onConnChange: (ConnectionState) -> Unit,
    authViewModel: AuthViewModel,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
    onOpenMapboxDebug: (() -> Unit)? = null,
) {
    val auth by authViewModel.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedTheme by OpenDashThemeController.variant.collectAsState()
    val oledDark by OpenDashThemeController.oledDark.collectAsState()
    remember(ctx) {
        CurrencySettings.init(ctx)
        MapboxNavigationSettings.init(ctx)
        true
    }
    val selectedCurrency by CurrencySettings.currency.collectAsState()
    val mapboxNavigationEnabled by MapboxNavigationSettings.mapboxNavigationEnabled.collectAsState()
    var currencyMenuExpanded by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(MorePage.ROOT) }
    var units by remember { mutableStateOf("Kilometres") }
    var wallpaperStatus by remember { mutableStateOf<String?>(null) }
    var pendingWallpaper by remember { mutableStateOf<WallpaperAsset?>(null) }

    val wallpaperSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri: Uri? ->
        val asset = pendingWallpaper
        pendingWallpaper = null
        if (uri == null || asset == null) return@rememberLauncherForActivityResult
        wallpaperStatus = if (copyWallpaperAsset(ctx, asset, uri)) {
            "Saved ${asset.title}"
        } else {
            "Could not save ${asset.title}"
        }
    }

    fun launchGoogleSignIn() {
        accountError = null
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            accountError = "Google sign-in is not configured for this build"
            return
        }
        scope.launch {
            try {
                val credentialManager = CredentialManager.create(ctx)
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build())
                    .build()
                val result = credentialManager.getCredential(ctx, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authViewModel.signInWithGoogle(googleCredential.idToken)
                } else {
                    accountError = "Google sign-in returned an unsupported credential"
                }
            } catch (e: GetCredentialException) {
                accountError = e.message ?: "Google sign-in failed"
            }
        }
    }

    BackHandler(enabled = page != MorePage.ROOT) { page = MorePage.ROOT }

    if (page == MorePage.APPEARANCE) {
        AppearancePage(
            selectedTheme = selectedTheme,
            oledDark = oledDark,
            onSelect = { variant -> OpenDashThemeController.select(ctx, variant) },
            onOledDarkChange = { enabled -> OpenDashThemeController.setOledDark(ctx, enabled) },
            onBack = { page = MorePage.ROOT },
        )
        return
    }

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
                MoreRow(OpenDashIcons.Gear, "Settings", "Account, navigation, wallpapers, units", onClick = { page = MorePage.SETTINGS })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Palette, "Appearance", "${selectedTheme.name} · ${selectedTheme.theme}", onClick = { page = MorePage.APPEARANCE })
                if (BuildConfig.DEBUG && BuildConfig.USE_MAPBOX_NAVIGATION && onOpenMapboxDebug != null) {
                    SettingsDivider(Modifier.padding(horizontal = 6.dp))
                    MoreRow(OpenDashIcons.Navi, "Mapbox debug", "Inspect the primary navigation provider", onClick = { onOpenMapboxDebug.invoke() })
                }
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Dash, "About", "OpenDash v${BuildConfig.VERSION_NAME}", onClick = { page = MorePage.ABOUT })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Bell, "Help", "Navigation, vehicles, garage, and expenses", onClick = { page = MorePage.HELP })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Lock, "Terms & Conditions", "Usage terms", onClick = { page = MorePage.TERMS })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Flag, "License", "Open source notices", onClick = { page = MorePage.LICENSE })
                SettingsDivider(Modifier.padding(horizontal = 6.dp))
                MoreRow(OpenDashIcons.Cal, "Changelog", "App-only cleanup build", last = true, onClick = { page = MorePage.CHANGELOG })
            }
            return@Column
        }

        SectionLabel("Account")
        SettingsGroup(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBubble(OpenDashIcons.Person)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(auth.email ?: "Not signed in", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(
                        auth.error ?: accountError ?: auth.syncStatus,
                        color = if (auth.error != null || accountError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = GeistFamily,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                OpenDashChip(if (auth.syncActive) "Sync on" else if (auth.syncAvailable) "Cloud" else "Local", if (auth.syncActive) ChipTone.Gold else ChipTone.Off, dot = true)
            }
            Spacer(Modifier.height(12.dp))
            if (auth.isSignedIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OpenDashBtn("Sync now", onClick = { authViewModel.syncNow() }, icon = OpenDashIcons.Sync, variant = BtnVariant.Secondary, size = BtnSize.Sm, enabled = !auth.loading, modifier = Modifier.weight(1f))
                    OpenDashBtn("Sign out", onClick = { authViewModel.signOut(); onSignedOut() }, variant = BtnVariant.Danger, size = BtnSize.Sm, enabled = !auth.loading, modifier = Modifier.weight(1f))
                }
            } else if (auth.syncAvailable) {
                OpenDashBtn(if (auth.loading) "Signing in..." else "Continue with Google", onClick = { launchGoogleSignIn() }, icon = OpenDashIcons.Sync, variant = BtnVariant.Primary, size = BtnSize.Sm, enabled = !auth.loading, modifier = Modifier.fillMaxWidth())
            }
        }

        if (BuildConfig.USE_MAPBOX_NAVIGATION) {
            SectionLabel("Navigation provider")
            SettingsGroup(padding = 6.dp) {
                SettingRow(
                    OpenDashIcons.Navi,
                    "Mapbox navigation",
                    if (BuildConfig.MAPBOX_ACCESS_TOKEN.isBlank()) "Token missing; add MAPBOX_ACCESS_TOKEN and rebuild" else "Primary route preview uses Mapbox",
                    control = {
                        SettingsToggle(mapboxNavigationEnabled && BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()) {
                            MapboxNavigationSettings.setMapboxNavigationEnabled(it)
                        }
                    },
                    last = true,
                )
            }
        }

        SectionLabel("Wallpapers")
        SettingsGroup(padding = 6.dp) {
            downloadableWallpapers.forEachIndexed { index, asset ->
                SettingRow(
                    icon = OpenDashIcons.Palette,
                    title = asset.title,
                    sub = asset.outputName,
                    control = {
                        OpenDashBtn(
                            "Download",
                            onClick = {
                                pendingWallpaper = asset
                                wallpaperSaveLauncher.launch(asset.outputName)
                            },
                            icon = OpenDashIcons.Plus,
                            variant = BtnVariant.Secondary,
                            size = BtnSize.Sm,
                        )
                    },
                    last = index == downloadableWallpapers.lastIndex,
                )
                if (index != downloadableWallpapers.lastIndex) SettingsDivider(Modifier.padding(horizontal = 6.dp))
            }
        }
        wallpaperStatus?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
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
                    control = { Icon(OpenDashIcons.ChevronRight, contentDescription = "Choose currency", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
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
                                    Text(currency.code, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                                    Text("${currency.displayName} · ${currency.symbol}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                auth.isSignedIn -> "Synced" to (auth.email ?: "Signed in")
                else -> "Not signed in" to "Sign in to sync across devices; data stays local until then"
            }
            SettingRow(OpenDashIcons.Sync, syncTitle, syncSub, control = { OpenDashChip(if (auth.isSignedIn) "On" else "Off", if (auth.isSignedIn) ChipTone.Gold else ChipTone.Off, dot = true) }, last = true)
        }

        Text(
            "OpenDash v${BuildConfig.VERSION_NAME} · ${if (!auth.syncAvailable) "local only" else if (auth.isSignedIn) "sync on" else "sync off"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp),
        )
    }
}

private fun copyWallpaperAsset(context: Context, asset: WallpaperAsset, uri: Uri): Boolean =
    runCatching {
        context.assets.open(asset.fileName).use { input ->
            context.contentResolver.openOutputStream(uri)?.use { output ->
                input.copyTo(output)
            } ?: error("No output stream")
        }
    }.isSuccess

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
private fun AppearancePage(
    selectedTheme: OpenDashThemeVariant,
    oledDark: Boolean,
    onSelect: (OpenDashThemeVariant) -> Unit,
    onOledDarkChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Appearance", onBack = onBack)

        SectionLabel("Theme")
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            OpenDashThemeVariants.forEach { variant ->
                ThemePreviewCard(
                    variant = variant,
                    selected = selectedTheme.name == variant.name,
                    onClick = { onSelect(variant) },
                )
            }
        }

        SectionLabel("Display")
        SettingsGroup(padding = 6.dp) {
            SettingRow(
                icon = OpenDashIcons.Moon,
                title = "OLED dark mode",
                sub = "Use pure black surfaces where possible",
                control = { SettingsToggle(oledDark, onOledDarkChange) },
                last = true,
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    variant: OpenDashThemeVariant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(140.dp),
    ) {
        Box(
            modifier = Modifier
                .height(190.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(variant.background)
                .border(
                    3.dp,
                    if (selected) variant.accent else variant.textMid.copy(alpha = 0.42f),
                    RoundedCornerShape(28.dp),
                )
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.72f)
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(variant.surfaceHigh),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(72.dp)
                    .height(82.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(variant.surface),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(variant.accent),
                ) {
                    if (selected) Icon(OpenDashIcons.Check, null, tint = variant.background, modifier = Modifier.size(17.dp))
                }
                Box(
                    Modifier
                        .height(24.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(variant.textMid.copy(alpha = 0.8f)),
                )
            }
        }
        Text(
            variant.name,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = GeistFamily,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun MoreInformationPage(page: MorePage, onBack: () -> Unit) {
    val sections = when (page) {
        MorePage.ABOUT -> listOf(
            "OpenDash v${BuildConfig.VERSION_NAME}" to "Open-source route preview, ride management, vehicle, garage, maintenance, expense, and wallpaper download tools.",
            "Privacy" to "OpenDash works locally by default. Your ride, vehicle, and expense data stays on this device unless sync is enabled.",
        )
        MorePage.HELP -> listOf(
            "Navigation" to "Share a destination or geo link, then review the route before you ride.",
            "Vehicles" to "Track vehicles, PUC and insurance dates, odometer, and service information.",
            "Garage" to "Log fuel, parts, maintenance, and service history.",
            "Wallpapers" to "Open Settings, choose a wallpaper, and save it to your phone with the system file picker.",
            "Expenses" to "Track ride and ownership costs with monthly and all-time views.",
        )
        MorePage.TERMS -> listOf(
            "Independent project" to "OpenDash is independent and community-built.",
            "Ride responsibly" to "Configure the app before riding. Do not interact with the phone in motion, and always follow local laws and road conditions.",
            "No warranty" to "The software is provided without warranty. Compatibility can vary by Android device and connected applications.",
        )
        MorePage.LICENSE -> listOf(
            "OpenDash" to "Distributed under the license included with the source repository.",
            "Open-source components" to "OpenDash uses Kotlin, Jetpack Compose, MapLibre, OpenFreeMap, OSRM, AndroidX, and other libraries under their respective licenses.",
            "Source" to "github.com/subtlesayak/open-dash",
        )
        MorePage.CHANGELOG -> listOf(
            "1.3.5 cleanup" to listOf(
                "Removed dash connection, projection, protocol, and dash wallpaper surfaces.",
                "Kept app-only features: vehicles, garage, maintenance, expenses, saved destinations, route preview, and sync.",
                "Added bundled wallpaper downloads in Settings.",
                "Home screen is hidden; navigation uses four tabs.",
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
            sub?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        control()
    }
    if (!last) SettingsDivider(Modifier.padding(horizontal = 12.dp))
}
