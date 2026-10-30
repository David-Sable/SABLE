# M5 playback design notes (Media3 1.11.1)

Verified against the Media3 1.11.1 sources before writing any M5 code.

**Outcome (implemented):** because of the pipeline-order finding below, the gain is applied in `SableAudioSink` (an
`AudioSink` wrapper) instead of an `AudioProcessor`. The item comes from the same `configure(... mediaPeriodId)` call.

## Per-item gain is sample-accurate
- Every new media item's sample stream starts with a format read. `MediaCodecRenderer.onInputFormatChanged` sets
  `waitingForFirstSampleInFormat`, so the item's first sample queues its format at that timestamp. When output reaches
  that timestamp, `onOutputFormatChanged` runs, and `MediaCodecAudioRenderer` calls `audioSink.configure(...)` with
  `setMediaPeriodId(getMediaPeriodId())`. This happens even for gapless items in the same format.
- `DefaultAudioSink.configure` builds a new `Configuration` that carries `(timeline, periodUid)`. While output is
  running, that configuration is held as `pendingConfiguration`. `handleBuffer` drains the old pipeline to its end of
  stream, switches to the new configuration, and calls `setupAudioProcessors`. That flushes every processor with
  `AudioProcessor.StreamMetadata(timeline, periodUid, positionOffsetUs)`.
- So a custom `AudioProcessor` that looks up the gain for the `periodUid` in `flush(StreamMetadata)` (via the
  timeline window's `MediaItem` tag) applies each track's gain from its first sample. The previous track's tail has
  already been drained with its own gain.
- `onProcessedStreamChange` → `audioSink.handleDiscontinuity()` affects only position tracking, not processors.

## Pipeline order and the float path
- `availableAudioProcessors` (ours) run first. `ToFloatPcmAudioProcessor` is added only if
  `shouldUseFloatOutput(inputEncoding)`, which needs a high-res input encoding. Otherwise the int16 processors and the
  default chain run.
- So the gain processor must take both 16-bit and float input. It should request float from the decoder where
  possible, then apply gain in float and output float, so there is no clipping between the gain stage and the peak
  cap (MATH §15).

## Still to settle in M5
- M_clipTP for MediaCodec-decoded lossy formats (AAC/Opus/Vorbis) whose decoder outputs int16 (MATH §1.1).
- Seam smoothing across item boundaries: the processor is flushed at the boundary, so any ramp state must be set from
  the previous/next gains looked up in the timeline, not carried in the processor's buffers.
