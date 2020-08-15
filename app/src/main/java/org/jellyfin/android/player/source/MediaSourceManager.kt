package org.jellyfin.android.player.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.CheckResult
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import org.jellyfin.android.player.PlayerViewModel
import org.jellyfin.android.utils.Constants
import org.json.JSONException
import org.json.JSONObject

class MediaSourceManager(private val viewModel: PlayerViewModel) {
    private val _jellyfinMediaSource = MutableLiveData<JellyfinMediaSource>()

    val jellyfinMediaSource: LiveData<JellyfinMediaSource> get() = _jellyfinMediaSource

    fun handleIntent(intent: Intent, replace: Boolean = false): Boolean {
        return if (_jellyfinMediaSource.value == null || replace) {
            val newSource = createFromIntent(intent)
            if (newSource != null) {
                val oldSource = _jellyfinMediaSource.value
                _jellyfinMediaSource.value = newSource

                // Keep current selections in the new item
                if (oldSource != null) {
                    newSource.subtitleTracksGroup.selectedTrack = oldSource.subtitleTracksGroup.selectedTrack
                    newSource.audioTracksGroup.selectedTrack = oldSource.audioTracksGroup.selectedTrack
                }
                val mediaSource = prepareStreams(newSource)
                if (mediaSource != null) {
                    val startMs: Long = newSource.mediaStartMs
                    if (startMs > 0) {
                        // TODO seek to right position
                    }
                    viewModel.playMedia(mediaSource)
                }
                true
            } else false
        } else true
    }

    /**
     * Builds a media source to feed the player being loaded
     *
     * @param item ExoPlayerMediaSource object containing all necessary info about the item to be played.
     * @return a MediaSource object. This could be a result of a MergingMediaSource or a ProgressiveMediaSource, between others
     */
    private fun prepareStreams(item: JellyfinMediaSource): MediaSource? {
        val context: Context = viewModel.getApplication()
        val dataSourceFactory: DataSource.Factory = DefaultDataSourceFactory(context, Util.getUserAgent(context, "Jellyfin Android"))
        return MergingMediaSource(createVideoMediaSource(item, dataSourceFactory), *createSubtitleMediaSources(item.subtitleTracksGroup, dataSourceFactory))
    }

    companion object {
        @CheckResult
        private fun createFromIntent(intent: Intent): JellyfinMediaSource? {
            val mediaSourceItem = intent.extras?.getString(Constants.EXTRA_MEDIA_SOURCE_ITEM) ?: return null
            return try {
                JellyfinMediaSource(JSONObject(mediaSourceItem))
            } catch (e: JSONException) {
                null
            }
        }

        @CheckResult
        private fun createVideoMediaSource(item: JellyfinMediaSource, dataSourceFactory: DataSource.Factory): MediaSource {
            val uri: Uri = Uri.parse(item.url)
            return if (item.isTranscoding) {
                HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true).createMediaSource(uri)
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            }
        }

        /**
         * Creates MediaSources for all subtitle tracks in the given group
         *
         * @param subtitleTracks ExoPlayerTracksGroup object containing all subtitle tracks
         * @param dataSourceFactory [DataSource.Factory] instance
         * @return media source with parsed subtitles
         */
        @CheckResult
        private fun createSubtitleMediaSources(
            subtitleTracks: ExoPlayerTracksGroup<ExoPlayerTrack.Text>,
            dataSourceFactory: DataSource.Factory
        ): Array<MediaSource> = subtitleTracks.tracks.values.mapNotNull { track ->
            if (!track.embedded && track.url != null && track.format != null) {
                val format = Format.createTextSampleFormat(track.index.toString(), track.format, C.SELECTION_FLAG_AUTOSELECT, track.language)
                SingleSampleMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse(track.url), format, C.TIME_UNSET)
            } else null
        }.toTypedArray()
    }
}