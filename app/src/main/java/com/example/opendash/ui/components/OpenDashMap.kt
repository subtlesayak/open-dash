package com.example.opendash.ui.components

import com.example.opendash.data.DashDisplayMode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.opendash.BuildConfig
import com.example.opendash.data.ExperimentalNavigationSettings
import com.example.opendash.dash.nav.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

// Default phone-preview basemap. The experimental Mapbox flag swaps this to detailed Mapbox tiles.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private fun mapboxNavigationStyleId(night: Boolean): String =
    if (night) "dark-v11" else "streets-v12"
private const val FOLLOW_ZOOM = 15.5
private const val NAV_ZOOM = 18.0
private const val NAV_TILT = 30.0
private const val RIDER_ICON = "rider-chevron"
private const val DEST_ICON = "dest-pin"

/**
 * In-app phone map. Defaults to OpenFreeMap, and uses Mapbox raster tiles when the
 * experimental Mapbox navigation flag and access token are both present.
 *
 * Modes: [fitRoute] frames the whole route; [navMode] tilts/zooms/rotates to heading with a
 * rider chevron; default follows the rider north-up.
 */
@Composable
fun OpenDashMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    routePoints: List<GeoPoint>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    fitRoute: Boolean = false,
    navMode: Boolean = false,
    riderBearing: Float = 0f,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    remember(context) {
        ExperimentalNavigationSettings.init(context.applicationContext)
        true
    }
    val mapboxNavigationEnabled by ExperimentalNavigationSettings.mapboxNavigationEnabled.collectAsState()
    val useMapboxPreview =
        BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL &&
            mapboxNavigationEnabled &&
            BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()
    val dashNightModeState = DashDisplayMode.nightMode.collectAsState()
    val mapboxStyleId = mapboxNavigationStyleId(dashNightModeState.value)
    val styleKey = if (useMapboxPreview) "mapbox-$mapboxStyleId" else "openfreemap"
    val mapView = remember { MapView(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }

    // Bind the MapView to the composition lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE  -> mapView.onCreate(null)
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            destroyed = true
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true
                isLogoEnabled = false
            }
        }
    }

    LaunchedEffect(map, styleKey) {
        if (destroyed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        styleReady = false
        lineMgr = null
        symbolMgr = null
        val builder = if (useMapboxPreview) {
            Style.Builder().fromJson(mapboxRasterStyleJson(BuildConfig.MAPBOX_ACCESS_TOKEN, mapboxStyleId))
        } else {
            Style.Builder().fromUri(STYLE_URL)
        }
        m.setStyle(builder) { style ->
            if (destroyed) return@setStyle
            style.addImage(RIDER_ICON, chevronBitmap())
            style.addImage(DEST_ICON, destPinBitmap())
            lineMgr = LineManager(mapView, m, style)
            symbolMgr = SymbolManager(mapView, m, style).apply {
                iconAllowOverlap = true
                iconIgnorePlacement = true
            }
            styleReady = true
        }
    }

    // Redraw route + markers whenever the data or mode changes.
    LaunchedEffect(styleReady, routePoints.size, dest, riderLat, riderLng, riderBearing, navMode) {
        if (destroyed) return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        lm.deleteAll(); sm.deleteAll()
        if (routePoints.size >= 2) {
            lm.create(
                LineOptions().withLatLngs(routePoints.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#4285F4").withLineWidth(5.5f)
            )
        }
        dest?.let { sm.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f)) }
        if (navMode && riderLat != null && riderLng != null) {
            sm.create(
                SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                    .withIconImage(RIDER_ICON).withIconRotate(riderBearing).withIconSize(1.0f)
            )
        }
    }

    // Camera control.
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, navMode, fitRoute, routePoints.size) {
        if (destroyed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        when {
            fitRoute && routePoints.size >= 2 -> {
                val b = LatLngBounds.Builder()
                routePoints.forEach { b.include(LatLng(it.lat, it.lng)) }
                runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 110)) }
            }
            riderLat != null && riderLng != null -> {
                val target = LatLng(riderLat, riderLng)
                val pos = if (navMode)
                    CameraPosition.Builder().target(target).zoom(NAV_ZOOM).tilt(NAV_TILT).bearing(riderBearing.toDouble()).build()
                else
                    CameraPosition.Builder().target(target).zoom(FOLLOW_ZOOM).tilt(0.0).bearing(0.0).build()
                runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600) }
            }
            dest != null -> runCatching {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0))
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun mapboxRasterStyleJson(token: String, styleId: String): String {
    val safeToken = token.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        {
          "version": 8,
          "name": "OpenDash Mapbox Navigation",
          "sources": {
            "mapbox-navigation": {
              "type": "raster",
              "tiles": [
                "https://api.mapbox.com/styles/v1/mapbox/$styleId/tiles/512/{z}/{x}/{y}?access_token=$safeToken"
              ],
              "tileSize": 512,
              "attribution": "Map data © OpenStreetMap contributors, Imagery © Mapbox"
            }
          },
          "layers": [
            {
              "id": "mapbox-navigation",
              "type": "raster",
              "source": "mapbox-navigation"
            }
          ]
        }
    """.trimIndent()
}

/** Blue chevron-in-a-circle, pointing "up" (rotated to heading by the symbol). */
private fun chevronBitmap(): Bitmap {
    val s = 84
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.34f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.WHITE
    val cx = s / 2f
    c.drawPath(Path().apply {
        moveTo(cx, s * 0.24f); lineTo(s * 0.72f, s * 0.70f); lineTo(cx, s * 0.58f); lineTo(s * 0.28f, s * 0.70f); close()
    }, p)
    return bmp
}

/** Simple red destination pin (white ring + red fill). */
private fun destPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}
