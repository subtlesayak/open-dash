package com.example.opendash.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.KeyEvent
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaInfoProvider(private val context: Context) {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, OpenDashNotificationListener::class.java)
    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = bind(null)
    }

    private val sessionsCallback = MediaSessionManager.OnActiveSessionsChangedListener(::bind)

    fun start() {
        if (!isAccessGranted(context)) {
            DebugLog.i(TAG) { "Notification access unavailable; media forwarding is disabled" }
            _nowPlaying.value = null
            return
        }
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsCallback, listenerComponent)
            refresh()
        }.onFailure { DebugLog.w(TAG) { "Media session binding failed: ${it.javaClass.simpleName}" } }
    }

    fun refresh() {
        if (!isAccessGranted(context)) {
            _nowPlaying.value = null
            return
        }
        runCatching {
            bind(sessionManager?.getActiveSessions(listenerComponent))
        }.onFailure { DebugLog.w(TAG) { "Media session refresh failed: ${it.javaClass.simpleName}" } }
    }

    fun stop() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsCallback) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        _nowPlaying.value = null
    }

    fun skipNext(): Boolean = runCatching {
        controller?.transportControls?.skipToNext() ?: dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        true
    }.getOrDefault(false)

    fun skipPrevious(): Boolean = runCatching {
        controller?.transportControls?.skipToPrevious() ?: dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        true
    }.getOrDefault(false)

    fun play(): Boolean = runCatching {
        controller?.transportControls?.play() ?: dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        true
    }.getOrDefault(false)

    fun playPause(): Boolean = runCatching {
        val state = controller?.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) {
            controller?.transportControls?.pause() ?: dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        } else {
            controller?.transportControls?.play() ?: dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        true
    }.getOrDefault(false)

    fun volumeUp(): Boolean = adjustVolume(AudioManager.ADJUST_RAISE)

    fun volumeDown(): Boolean = adjustVolume(AudioManager.ADJUST_LOWER)

    private fun adjustVolume(direction: Int): Boolean = runCatching {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
        true
    }.getOrDefault(false)

    private fun dispatchMediaKey(keyCode: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }

    private fun bind(sessions: List<MediaController>?) {
        val next = selectController(sessions)
        if (next?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        controller?.registerCallback(controllerCallback)
        publish()
    }

    private fun selectController(sessions: List<MediaController>?): MediaController? {
        val usable = sessions.orEmpty().filter { hasDisplayableMetadata(it.metadata) }
        return usable.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: usable.firstOrNull { controller ->
                controller.playbackState?.state?.let { it in ACTIVE_STATES } == true
            }
            ?: usable.firstOrNull()
    }

    private fun publish() {
        val metadata = controller?.metadata ?: run {
            _nowPlaying.value = null
            return
        }
        val title = firstMetadataString(
            metadata,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        )
        val album = firstMetadataString(
            metadata,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        )
        val artist = firstMetadataString(
            metadata,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        )
        if (title.isBlank() && artist.isBlank()) {
            _nowPlaying.value = null
            return
        }
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val packageName = controller?.packageName.orEmpty()
        DebugLog.i(TAG) { "Now playing from $packageName: '$title' · '$artist'" }
        _nowPlaying.value = NowPlaying(title, album, artist, art)
    }

    private fun hasDisplayableMetadata(metadata: MediaMetadata?): Boolean {
        metadata ?: return false
        return firstMetadataString(
            metadata,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        ).isNotBlank()
    }

    private fun firstMetadataString(metadata: MediaMetadata, vararg keys: String): String {
        for (key in keys) {
            val value = metadata.getString(key)?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    companion object {
        private const val TAG = "MediaInfoProvider"
        private val ACTIVE_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        )

        fun isAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(':').any { it.contains(context.packageName) }
        }

        fun accessSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
