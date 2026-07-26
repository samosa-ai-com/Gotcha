package com.gotcha.tools

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSelectionTest {

    private val spotify = SessionInfo("com.spotify.music", "Spotify", PlaybackState.STATE_PAUSED)
    private val ytMusic = SessionInfo(
        "com.google.android.apps.youtube.music",
        "YT Music",
        PlaybackState.STATE_PLAYING
    )
    private val podcast = SessionInfo("fm.player", "Podcasts", PlaybackState.STATE_STOPPED)

    // ---- action parsing ----

    @Test
    fun `action synonyms map to the same action`() {
        assertEquals(MediaAction.PLAY, MediaSelection.parseAction("play"))
        assertEquals(MediaAction.PLAY, MediaSelection.parseAction("resume"))
        assertEquals(MediaAction.NEXT, MediaSelection.parseAction("skip"))
        assertEquals(MediaAction.NEXT, MediaSelection.parseAction("forward"))
        assertEquals(MediaAction.PREVIOUS, MediaSelection.parseAction("prev"))
        assertEquals(MediaAction.TOGGLE, MediaSelection.parseAction("playpause"))
        assertEquals(MediaAction.FAST_FORWARD, MediaSelection.parseAction("ff"))
    }

    @Test
    fun `play and pause are now distinct rather than both toggling`() {
        assertEquals(MediaAction.PLAY, MediaSelection.parseAction("play"))
        assertEquals(MediaAction.PAUSE, MediaSelection.parseAction("pause"))
    }

    @Test
    fun `action parsing ignores case and surrounding space`() {
        assertEquals(MediaAction.PAUSE, MediaSelection.parseAction("  PAUSE "))
    }

    @Test
    fun `unknown actions are rejected`() {
        assertNull(MediaSelection.parseAction("explode"))
        assertNull(MediaSelection.parseAction(""))
        // Removed deliberately: the framework TransportControls cannot do these.
        assertNull(MediaSelection.parseAction("shuffle"))
        assertNull(MediaSelection.parseAction("repeat"))
    }

    // ---- session picking ----

    @Test
    fun `a playing session wins over a merely open one`() {
        assertEquals(ytMusic, MediaSelection.pick(listOf(spotify, podcast, ytMusic), null))
    }

    @Test
    fun `with nothing playing the first session is used`() {
        assertEquals(spotify, MediaSelection.pick(listOf(spotify, podcast), null))
    }

    @Test
    fun `a buffering session is preferred over a stopped one`() {
        val buffering = SessionInfo("a.b", "Buffering app", PlaybackState.STATE_BUFFERING)
        assertEquals(buffering, MediaSelection.pick(listOf(podcast, buffering), null))
    }

    @Test
    fun `an app hint matches the package name`() {
        assertEquals(spotify, MediaSelection.pick(listOf(spotify, ytMusic), "com.spotify"))
    }

    @Test
    fun `an app hint matches the app label case-insensitively`() {
        assertEquals(spotify, MediaSelection.pick(listOf(spotify, ytMusic), "SPOTIFY"))
    }

    @Test
    fun `an app hint beats the playing-session preference`() {
        // Spotify is only paused, but the user named it explicitly.
        assertEquals(spotify, MediaSelection.pick(listOf(ytMusic, spotify), "spotify"))
    }

    @Test
    fun `an app hint that matches nothing returns null rather than the wrong app`() {
        assertNull(MediaSelection.pick(listOf(spotify, ytMusic), "vlc"))
    }

    @Test
    fun `no sessions yields null`() {
        assertNull(MediaSelection.pick(emptyList(), null))
        assertNull(MediaSelection.pick(emptyList(), "spotify"))
    }

    // ---- formatting ----

    @Test
    fun `playback states are described in plain words`() {
        assertEquals("playing", MediaSelection.describeState(PlaybackState.STATE_PLAYING))
        assertEquals("paused", MediaSelection.describeState(PlaybackState.STATE_PAUSED))
        assertEquals("idle", MediaSelection.describeState(PlaybackState.STATE_NONE))
        assertEquals("unknown", MediaSelection.describeState(-99))
    }

    @Test
    fun `positions render as minutes and seconds`() {
        assertEquals("0:00", MediaSelection.formatPosition(0))
        assertEquals("3:07", MediaSelection.formatPosition(187_000))
        assertEquals("1:02:33", MediaSelection.formatPosition(3_753_000))
    }

    @Test
    fun `an unknown position renders as dashes rather than a negative time`() {
        assertEquals("--:--", MediaSelection.formatPosition(-1))
    }
}
