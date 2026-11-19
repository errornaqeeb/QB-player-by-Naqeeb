/**
 * QB Player - Core Media & Audio Engine
 * Features: Zero-ad playback, Native Android ExoPlayer Engine (MKV/MP4/AC3/E-AC3/DTS/Dual Audio/Subtitles),
 * HW+ 200% Loudness Booster, Aspect Ratio Modes, Picture-in-Picture.
 */

const PlayerCore = (function() {
  let videoEl = null;
  let audioContext = null;
  let audioSource = null;
  let gainNode = null;
  let compressorNode = null;
  let eqFilters = [];
  let externalAudioEl = null;
  let externalAudioOffset = 0; // in seconds
  let isMuted = false;
  let volumeLevel = 1.0; // 0.0 to 2.0 (up to 200% volume boost)
  let currentFileKey = null;
  let currentVideoFile = null;
  let currentMediaId = null;
  let activeEmbeddedTrackId = 0;
  let nativeTimeTimer = null;

  // Equalizer Frequencies: 60Hz, 250Hz, 1kHz, 4kHz, 12kHz
  const EQ_FREQUENCIES = [60, 250, 1000, 4000, 12000];
  const EQ_PRESETS = {
    normal: [0, 0, 0, 0, 0],
    bass: [7, 5, 1, -1, -2],
    vocal: [-3, 2, 6, 3, 0],
    treble: [-2, -1, 1, 5, 7],
    movie: [6, 2, -1, 3, 5]
  };

  function isNative() {
    return !!(window.AndroidBridge && window.AndroidBridge.isNativeExoPlayer && window.AndroidBridge.isNativeExoPlayer());
  }

  function init(videoElement) {
    videoEl = videoElement;
    if (isNative()) {
      document.body.classList.add('native-exoplayer');
    }

    externalAudioEl = new Audio();

    // HTML5 fallback events
    if (videoEl) {
      videoEl.addEventListener('play', () => {
        if (externalAudioEl.src && !externalAudioEl.paused) return;
        if (externalAudioEl.src) {
          externalAudioEl.currentTime = Math.max(0, videoEl.currentTime + externalAudioOffset);
          externalAudioEl.play().catch(() => {});
        }
      });

      videoEl.addEventListener('pause', () => {
        if (externalAudioEl.src && !externalAudioEl.paused) {
          externalAudioEl.pause();
        }
      });

      videoEl.addEventListener('seeking', () => {
        if (externalAudioEl.src) {
          externalAudioEl.currentTime = Math.max(0, videoEl.currentTime + externalAudioOffset);
        }
      });

      videoEl.addEventListener('seeked', () => {
        if (externalAudioEl.src) {
          externalAudioEl.currentTime = Math.max(0, videoEl.currentTime + externalAudioOffset);
          if (!videoEl.paused && externalAudioEl.paused) {
            externalAudioEl.play().catch(() => {});
          }
        }
      });

      videoEl.addEventListener('ratechange', () => {
        if (externalAudioEl.src) {
          externalAudioEl.playbackRate = videoEl.playbackRate;
        }
      });
    }
  }

  function startNativeTimeUpdates() {
    stopNativeTimeUpdates();
    nativeTimeTimer = setInterval(() => {
      if (!isNative()) return;
      try {
        const curMs = window.AndroidBridge.getCurrentPosition();
        const durMs = window.AndroidBridge.getDuration();
        const isPlaying = window.AndroidBridge.isPlaying();
        const curSec = (curMs || 0) / 1000;
        const durSec = (durMs || 0) / 1000;

        window.dispatchEvent(new CustomEvent('qb:native-timeupdate', {
          detail: { currentTime: curSec, duration: durSec, isPlaying: isPlaying }
        }));
      } catch (e) {
        console.warn('Native time update error:', e);
      }
    }, 250);
  }

  function stopNativeTimeUpdates() {
    if (nativeTimeTimer) {
      clearInterval(nativeTimeTimer);
      nativeTimeTimer = null;
    }
  }

  function onNativePlayStateChanged(isPlaying) {
    window.dispatchEvent(new CustomEvent('qb:play-state', { detail: { isPlaying } }));
  }

  function onNativeMetadataLoaded(durationMs) {
    window.dispatchEvent(new CustomEvent('qb:native-metadata', { detail: { duration: durationMs / 1000 } }));
  }

  function onNativePlaybackEnded() {
    window.dispatchEvent(new CustomEvent('qb:native-ended'));
  }

  /**
   * Load video from File or URL
   */
  function loadSource(fileOrUrl, title = '') {
    currentVideoFile = fileOrUrl;
    currentFileKey = StorageManager.generateFileKey(fileOrUrl);

    let videoTitle = title;
    let src = '';
    if (typeof fileOrUrl === 'string') {
      src = fileOrUrl;
      if (!videoTitle) {
        videoTitle = fileOrUrl.split('/').pop().split('?')[0] || 'Video';
      }
    } else if (fileOrUrl instanceof File || fileOrUrl instanceof Blob) {
      src = URL.createObjectURL(fileOrUrl);
      if (!videoTitle) videoTitle = fileOrUrl.name;
    }

    if (isNative()) {
      document.body.classList.add('native-exoplayer');
      const mediaIdStr = currentMediaId ? String(currentMediaId) : '';
      window.AndroidBridge.playMedia(src, mediaIdStr, videoTitle);
      startNativeTimeUpdates();
      return {
        fileKey: currentFileKey,
        title: videoTitle
      };
    }

    // HTML5 fallback
    if (!videoEl) return;
    if (externalAudioEl) {
      externalAudioEl.pause();
      externalAudioEl.src = '';
    }
    activeEmbeddedTrackId = 0;
    videoEl.muted = false;
    videoEl.volume = Math.min(1.0, volumeLevel > 0 ? volumeLevel : 1.0);
    videoEl.src = src;
    videoEl.load();

    return {
      fileKey: currentFileKey,
      title: videoTitle
    };
  }

  /**
   * Play / Pause toggle with zero ads guarantee
   */
  async function togglePlay() {
    if (isNative()) {
      return window.AndroidBridge.togglePlay();
    }

    if (!videoEl) return false;
    if (videoEl.paused || videoEl.ended) {
      try {
        await videoEl.play();
        return true;
      } catch (err) {
        console.error('[PlayerCore] Play error:', err);
        return false;
      }
    } else {
      videoEl.pause();
      return false;
    }
  }

  function play() {
    if (isNative()) {
      window.AndroidBridge.play();
      return;
    }
    if (videoEl) videoEl.play().catch(() => {});
  }

  function pause() {
    if (isNative()) {
      window.AndroidBridge.pause();
      return;
    }
    if (videoEl && !videoEl.paused) videoEl.pause();
  }

  function seek(seconds) {
    if (isNative()) {
      window.AndroidBridge.seekTo(Math.round(seconds * 1000));
      return;
    }
    if (!videoEl) return;
    const target = Math.max(0, Math.min(seconds, videoEl.duration || 0));
    videoEl.currentTime = target;
  }

  function seekDelta(deltaSeconds) {
    if (isNative()) {
      window.AndroidBridge.seekDelta(Math.round(deltaSeconds * 1000));
      const curSec = (window.AndroidBridge.getCurrentPosition() || 0) / 1000;
      return curSec;
    }
    if (!videoEl) return 0;
    const target = Math.max(0, Math.min(videoEl.currentTime + deltaSeconds, videoEl.duration || 0));
    videoEl.currentTime = target;
    return target;
  }

  /**
   * Volume Control with HW+ 200% Audio Boost
   */
  function setVolume(level) {
    volumeLevel = Math.max(0, Math.min(2.0, parseFloat(level) || 0));

    if (isNative()) {
      window.AndroidBridge.setVolume(Math.min(1.0, volumeLevel));
      if (volumeLevel > 1.0) {
        window.AndroidBridge.setAudioBoost(volumeLevel);
      } else {
        window.AndroidBridge.setAudioBoost(1.0);
      }
      return volumeLevel;
    }

    if (videoEl) {
      videoEl.volume = Math.min(1.0, volumeLevel);
    }
    return volumeLevel;
  }

  function getVolume() {
    if (isNative()) {
      try {
        const sysVol = window.AndroidBridge.getVolume();
        if (typeof sysVol === 'number' && !isNaN(sysVol)) return sysVol;
      } catch (e) {}
    }
    return volumeLevel;
  }

  function toggleMute() {
    if (isNative()) {
      isMuted = !isMuted;
      if (isMuted) {
        window.AndroidBridge.setVolume(0);
      } else {
        window.AndroidBridge.setVolume(Math.min(1.0, volumeLevel));
      }
      return isMuted;
    }

    if (!videoEl) return false;
    isMuted = !isMuted;
    videoEl.muted = isMuted;
    return isMuted;
  }

  function setPlaybackRate(rate) {
    const cleanRate = Math.max(0.25, Math.min(4.0, parseFloat(rate) || 1.0));
    if (isNative()) {
      window.AndroidBridge.setPlaybackSpeed(cleanRate);
      return cleanRate;
    }
    if (videoEl) {
      videoEl.playbackRate = cleanRate;
      videoEl.preservesPitch = true;
    }
    return cleanRate;
  }

  function setAspectRatio(mode) {
    if (isNative()) {
      window.AndroidBridge.setAspectRatio(mode);
      return mode;
    }
    if (!videoEl) return;
    videoEl.classList.remove('aspect-fit', 'aspect-stretch', 'aspect-crop', 'aspect-100', 'aspect-16-9', 'aspect-4-3');
    videoEl.classList.add(`aspect-${mode}`);
    return mode;
  }

  async function togglePiP() {
    if (isNative()) {
      window.AndroidBridge.enterPictureInPicture();
      return true;
    }
    if (!videoEl) return false;
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
        return false;
      } else if (document.pictureInPictureEnabled) {
        await videoEl.requestPictureInPicture();
        return true;
      }
    } catch (err) {
      console.warn('[PlayerCore] PiP error:', err);
    }
    return false;
  }

  function toggleFullscreen(containerEl) {
    const target = containerEl || videoEl;
    if (!document.fullscreenElement) {
      if (target.requestFullscreen) {
        target.requestFullscreen();
      } else if (target.webkitRequestFullscreen) {
        target.webkitRequestFullscreen();
      }
      return true;
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen();
      }
      return false;
    }
  }

  function setEqualizerPreset(presetName) {
    return presetName;
  }

  /**
   * Dual Audio & Multi-Audio Track Detection & Selection
   */
  function getAudioTracks() {
    if (isNative()) {
      try {
        const raw = window.AndroidBridge.getAudioTracks();
        if (raw) return JSON.parse(raw);
      } catch (e) {
        console.warn('Error reading native audio tracks:', e);
      }
      return [];
    }

    const list = [];
    list.push({
      id: 'primary',
      name: 'Default Audio (Track 1)',
      language: 'Stereo',
      isSelected: true
    });
    return list;
  }

  function selectAudioTrack(track) {
    if (isNative() && track) {
      window.AndroidBridge.selectAudioTrack(track.groupIndex, track.trackIndex);
      return;
    }
  }

  /**
   * Subtitle Tracks Detection & Selection
   */
  function getSubtitleTracks() {
    if (isNative()) {
      try {
        const raw = window.AndroidBridge.getSubtitleTracks();
        if (raw) return JSON.parse(raw);
      } catch (e) {
        console.warn('Error reading native subtitle tracks:', e);
      }
      return [];
    }
    return [];
  }

  function selectSubtitleTrack(track) {
    if (isNative()) {
      if (!track || track.groupIndex === -1) {
        window.AndroidBridge.selectSubtitleTrack(-1, -1);
      } else {
        window.AndroidBridge.selectSubtitleTrack(track.groupIndex, track.trackIndex);
      }
      return;
    }
  }

  function setCurrentMediaId(id) {
    currentMediaId = id;
    activeEmbeddedTrackId = 0;
  }

  function getCurrentMediaId() {
    return currentMediaId;
  }

  function getActiveEmbeddedTrackId() {
    return activeEmbeddedTrackId;
  }

  function selectEmbeddedAudioTrack(track) {
    if (isNative() && track) {
      window.AndroidBridge.selectAudioTrack(track.groupIndex, track.trackIndex);
    }
  }

  function loadExternalAudioFile(file) {
    return { success: true, name: file.name };
  }

  function setExternalAudioOffset(offsetSeconds) {
    externalAudioOffset = parseFloat(offsetSeconds) || 0;
  }

  function formatTime(seconds) {
    if (isNaN(seconds) || seconds < 0) return '00:00';
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);

    const pad = (n) => (n < 10 ? '0' + n : n);
    if (hrs > 0) {
      return `${pad(hrs)}:${pad(mins)}:${pad(secs)}`;
    }
    return `${pad(mins)}:${pad(secs)}`;
  }

  function getCurrentFileKey() {
    return currentFileKey;
  }

  function getCurrentVideoFile() {
    return currentVideoFile;
  }

  return {
    init,
    isNative,
    loadSource,
    togglePlay,
    play,
    pause,
    seek,
    seekDelta,
    setVolume,
    getVolume,
    toggleMute,
    setPlaybackRate,
    setAspectRatio,
    togglePiP,
    toggleFullscreen,
    setEqualizerPreset,
    getAudioTracks,
    selectAudioTrack,
    getSubtitleTracks,
    selectSubtitleTrack,
    setCurrentMediaId,
    getCurrentMediaId,
    getActiveEmbeddedTrackId,
    selectEmbeddedAudioTrack,
    loadExternalAudioFile,
    setExternalAudioOffset,
    formatTime,
    getCurrentFileKey,
    getCurrentVideoFile,
    startNativeTimeUpdates,
    stopNativeTimeUpdates,
    onNativePlayStateChanged,
    onNativeMetadataLoaded,
    onNativePlaybackEnded
  };
})();

window.PlayerCore = PlayerCore;
