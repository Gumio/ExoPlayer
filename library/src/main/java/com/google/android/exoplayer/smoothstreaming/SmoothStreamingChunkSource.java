/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.smoothstreaming;

import com.google.android.exoplayer.BehindLiveWindowException;
import com.google.android.exoplayer.C;
import com.google.android.exoplayer.Format;
import com.google.android.exoplayer.Format.DecreasingBandwidthComparator;
import com.google.android.exoplayer.TrackGroup;
import com.google.android.exoplayer.chunk.Chunk;
import com.google.android.exoplayer.chunk.ChunkExtractorWrapper;
import com.google.android.exoplayer.chunk.ChunkHolder;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.ContainerMediaChunk;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.chunk.FormatEvaluator.Evaluation;
import com.google.android.exoplayer.chunk.MediaChunk;
import com.google.android.exoplayer.extractor.mp4.FragmentedMp4Extractor;
import com.google.android.exoplayer.extractor.mp4.Track;
import com.google.android.exoplayer.extractor.mp4.TrackEncryptionBox;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest.StreamElement;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;

import android.net.Uri;
import android.text.TextUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * An {@link ChunkSource} for SmoothStreaming.
 */
public class SmoothStreamingChunkSource implements ChunkSource {

  private final int elementIndex;
  private final TrackGroup trackGroup;
  private final ChunkExtractorWrapper[] extractorWrappers;
  private final Format[] enabledFormats;
  private final boolean[] adaptiveFormatBlacklistFlags;
  private final DataSource dataSource;
  private final Evaluation evaluation;
  private final FormatEvaluator adaptiveFormatEvaluator;

  private SmoothStreamingManifest manifest;
  private int currentManifestChunkOffset;
  private boolean needManifestRefresh;

  private IOException fatalError;

  /**
   * @param manifest The initial manifest.
   * @param elementIndex The index of the stream element in the manifest.
   * @param trackGroup The track group corresponding to the stream element.
   * @param tracks The indices of the selected tracks within the stream element.
   * @param dataSource A {@link DataSource} suitable for loading the media data.
   * @param adaptiveFormatEvaluator For adaptive tracks, selects from the available formats.
   * @param trackEncryptionBoxes Track encryption boxes for the stream.
   */
  public SmoothStreamingChunkSource(SmoothStreamingManifest manifest, int elementIndex,
      TrackGroup trackGroup, int[] tracks, DataSource dataSource,
      FormatEvaluator adaptiveFormatEvaluator, TrackEncryptionBox[] trackEncryptionBoxes) {
    this.manifest = manifest;
    this.elementIndex = elementIndex;
    this.trackGroup = trackGroup;
    this.dataSource = dataSource;
    this.adaptiveFormatEvaluator = adaptiveFormatEvaluator;
    this.evaluation = new Evaluation();

    StreamElement streamElement = manifest.streamElements[elementIndex];
    Format[] formats = streamElement.formats;
    extractorWrappers = new ChunkExtractorWrapper[formats.length];
    for (int j = 0; j < formats.length; j++) {
      int nalUnitLengthFieldLength = streamElement.type == C.TRACK_TYPE_VIDEO ? 4 : -1;
      Track track = new Track(j, streamElement.type, streamElement.timescale, C.UNSET_TIME_US,
          manifest.durationUs, formats[j], trackEncryptionBoxes, nalUnitLengthFieldLength,
          null, null);
      FragmentedMp4Extractor extractor = new FragmentedMp4Extractor(
          FragmentedMp4Extractor.FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME
          | FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_TFDT_BOX, track);
      extractorWrappers[j] = new ChunkExtractorWrapper(extractor, formats[j].drmInitData);
    }

    enabledFormats = new Format[tracks.length];
    for (int i = 0; i < tracks.length; i++) {
      enabledFormats[i] = trackGroup.getFormat(tracks[i]);
    }
    Arrays.sort(enabledFormats, new DecreasingBandwidthComparator());
    if (adaptiveFormatEvaluator != null) {
      adaptiveFormatEvaluator.enable(enabledFormats);
      adaptiveFormatBlacklistFlags = new boolean[tracks.length];
    } else {
      adaptiveFormatBlacklistFlags = null;
    }
  }

  public void updateManifest(SmoothStreamingManifest newManifest) {
    StreamElement currentElement = manifest.streamElements[elementIndex];
    int currentElementChunkCount = currentElement.chunkCount;
    StreamElement newElement = newManifest.streamElements[elementIndex];
    if (currentElementChunkCount == 0 || newElement.chunkCount == 0) {
      // There's no overlap between the old and new elements because at least one is empty.
      currentManifestChunkOffset += currentElementChunkCount;
    } else {
      long currentElementEndTimeUs = currentElement.getStartTimeUs(currentElementChunkCount - 1)
          + currentElement.getChunkDurationUs(currentElementChunkCount - 1);
      long newElementStartTimeUs = newElement.getStartTimeUs(0);
      if (currentElementEndTimeUs <= newElementStartTimeUs) {
        // There's no overlap between the old and new elements.
        currentManifestChunkOffset += currentElementChunkCount;
      } else {
        // The new element overlaps with the old one.
        currentManifestChunkOffset += currentElement.getChunkIndex(newElementStartTimeUs);
      }
    }
    manifest = newManifest;
    needManifestRefresh = false;
  }

  public boolean needManifestRefresh() {
    return needManifestRefresh;
  }

  public void release() {
    if (adaptiveFormatEvaluator != null) {
      adaptiveFormatEvaluator.disable();
    }
  }

  // ChunkSource implementation.

  @Override
  public void maybeThrowError() throws IOException {
    if (fatalError != null) {
      throw fatalError;
    }
  }

  @Override
  public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    if (fatalError != null || enabledFormats.length < 2) {
      return queue.size();
    }
    return adaptiveFormatEvaluator.evaluateQueueSize(playbackPositionUs, queue,
        adaptiveFormatBlacklistFlags);
  }

  @Override
  public final void getNextChunk(MediaChunk previous, long playbackPositionUs, ChunkHolder out) {
    if (fatalError != null) {
      return;
    }

    if (enabledFormats.length > 1) {
      long bufferedDurationUs = previous != null ? (previous.endTimeUs - playbackPositionUs) : 0;
      adaptiveFormatEvaluator.evaluateFormat(bufferedDurationUs, adaptiveFormatBlacklistFlags,
          evaluation);
    } else {
      evaluation.format = enabledFormats[0];
      evaluation.trigger = Chunk.TRIGGER_MANUAL;
    }

    Format selectedFormat = evaluation.format;
    if (selectedFormat == null) {
      return;
    }

    StreamElement streamElement = manifest.streamElements[elementIndex];
    if (streamElement.chunkCount == 0) {
      if (manifest.isLive) {
        needManifestRefresh = true;
      } else {
        out.endOfStream = true;
      }
      return;
    }

    int chunkIndex;
    if (previous == null) {
      chunkIndex = streamElement.getChunkIndex(playbackPositionUs);
    } else {
      chunkIndex = previous.getNextChunkIndex() - currentManifestChunkOffset;
      if (chunkIndex < 0) {
        // This is before the first chunk in the current manifest.
        fatalError = new BehindLiveWindowException();
        return;
      }
    }

    needManifestRefresh = manifest.isLive && chunkIndex >= streamElement.chunkCount - 1;
    if (chunkIndex >= streamElement.chunkCount) {
      // This is beyond the last chunk in the current manifest.
      out.endOfStream = !manifest.isLive;
      return;
    }

    long chunkStartTimeUs = streamElement.getStartTimeUs(chunkIndex);
    long chunkEndTimeUs = chunkStartTimeUs + streamElement.getChunkDurationUs(chunkIndex);
    int currentAbsoluteChunkIndex = chunkIndex + currentManifestChunkOffset;

    int trackGroupTrackIndex = getTrackGroupTrackIndex(trackGroup, selectedFormat);
    ChunkExtractorWrapper extractorWrapper = extractorWrappers[trackGroupTrackIndex];

    int manifestTrackIndex = getManifestTrackIndex(streamElement, selectedFormat);
    Uri uri = streamElement.buildRequestUri(manifestTrackIndex, chunkIndex);

    out.chunk = newMediaChunk(selectedFormat, dataSource, uri, null, currentAbsoluteChunkIndex,
        chunkStartTimeUs, chunkEndTimeUs, evaluation.trigger, extractorWrapper);
  }

  @Override
  public void onChunkLoadCompleted(Chunk chunk) {
    // Do nothing.
  }

  @Override
  public boolean onChunkLoadError(Chunk chunk, boolean cancelable, Exception e) {
    // TODO: Consider implementing stream element blacklisting.
    return false;
  }

  // Private methods.

  /**
   * Gets the index of a format in a track group, using referential equality.
   */
  private static int getTrackGroupTrackIndex(TrackGroup trackGroup, Format format) {
    for (int i = 0; i < trackGroup.length; i++) {
      if (trackGroup.getFormat(i) == format) {
        return i;
      }
    }
    // Should never happen.
    throw new IllegalStateException("Invalid format: " + format);
  }

  /**
   * Gets the index of a format in an element, using format.id equality.
   * <p>
   * This method will return the same index as {@link #getTrackGroupTrackIndex(TrackGroup, Format)}
   * except in the case where a live manifest is refreshed and the ordering of the tracks in the
   * manifest has changed.
   */
  private static int getManifestTrackIndex(StreamElement element, Format format) {
    Format[] formats = element.formats;
    for (int i = 0; i < formats.length; i++) {
      if (TextUtils.equals(formats[i].id, format.id)) {
        return i;
      }
    }
    // Should never happen.
    throw new IllegalStateException("Invalid format: " + format);
  }

  private static MediaChunk newMediaChunk(Format format, DataSource dataSource, Uri uri,
      String cacheKey, int chunkIndex, long chunkStartTimeUs, long chunkEndTimeUs, int trigger,
      ChunkExtractorWrapper extractorWrapper) {
    DataSpec dataSpec = new DataSpec(uri, 0, -1, cacheKey);
    // In SmoothStreaming each chunk contains sample timestamps relative to the start of the chunk.
    // To convert them the absolute timestamps, we need to set sampleOffsetUs to chunkStartTimeUs.
    long sampleOffsetUs = chunkStartTimeUs;
    return new ContainerMediaChunk(dataSource, dataSpec, trigger, format, chunkStartTimeUs,
        chunkEndTimeUs, chunkIndex, sampleOffsetUs, extractorWrapper, format);
  }

}
