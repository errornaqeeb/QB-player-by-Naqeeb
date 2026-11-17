/**
 * QB Player - Core Media & Audio Engine
 * Features: Zero-ad playback, HW+ 200% Audio Boost, Equalizer, Dual Audio selection, PiP, Aspect Ratios
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

  // Equalizer Frequencies: 60Hz, 250Hz, 1kHz, 4kHz, 12kHz
  const EQ_FREQUENCIES = [60, 250, 1000, 4000, 12000];
  const EQ_PRESETS = {
    normal: [0, 0, 0, 0, 0],
    bass: [7, 5, 1, -1, -2],
    vocal: [-3, 2, 6, 3, 0],
    treble: [-2, -1, 1, 5, 7],
    movie: [6, 2, -1, 3, 5]
  };

  function init(videoElement) {
    videoEl = videoElement;
    externalAudioEl = new Audio();

    // Sync external audio with video events
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

    videoEl.addEventListener('ratechange', () => {
      if (externalAudioEl.src) {
        externalAudioEl.playbackRate = videoEl.playbackRate;
      }
    });
  }

  /**
   * Initialize Web Audio API graph for Audio Booster & Equalizer
   * Safely called on user interaction
   */
  function setupWebAudio() {
    if (audioContext || !videoEl) return;
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      audioContext = new AudioCtx();
      audioSource = audioContext.createMediaElementSource(videoEl);

      // Create Gain node for volume boost (up to 200%)
      gainNode = audioContext.createGain();
      gainNode.gain.value = volumeLevel;

      // Create Compressor to prevent harsh audio distortion when boosted
      compressorNode = audioContext.createDynamicsCompressor();
      compressorNode.threshold.setValueAtTime(-12, audioContext.currentTime);
      compressorNode.knee.setValueAtTime(30, audioContext.currentTime);
      compressorNode.ratio.setValueAtTime(12, audioContext.currentTime);
      compressorNode.attack.setValueAtTime(0.003, audioContext.currentTime);
      compressorNode.release.setValueAtTime(0.25, audioContext.currentTime);

      // Create 5-band Equalizer
      eqFilters = EQ_FREQUENCIES.map((freq, i) => {
        const filter = audioContext.createBiquadFilter();
        if (i === 0) {
          filter.type = 'lowshelf';
        } else if (i === EQ_FREQUENCIES.length - 1) {
          filter.type = 'highshelf';
        } else {
          filter.type = 'peaking';
          filter.Q.value = 1.0;
        }
        filter.frequency.value = freq;
        filter.gain.value = 0;
        return filter;
      });

      // Connect graph: Source -> EQ Filters -> Compressor -> Gain -> Destination
      let lastNode = audioSource;
      for (const filter of eqFilters) {
        lastNode.connect(filter);
        lastNode = filter;
      }
      lastNode.connect(compressorNode);
      compressorNode.connect(gainNode);
      gainNode.connect(audioContext.destination);

      console.log('[PlayerCore] Web Audio API graph connected with 200% Audio Booster.');
    } catch (e) {
      console.warn('[PlayerCore] Web Audio API initialization skipped or already connected:', e);
    }
  }

  /**
   * Play / Pause toggle with zero ads guarantee
   */
  async function togglePlay() {
    if (!videoEl) return false;
    setupWebAudio();
    if (audioContext && audioContext.state === 'suspended') {
      audioContext.resume();
    }

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
    if (!videoEl) return;
    setupWebAudio();
    if (audioContext && audioContext.state === 'suspended') audioContext.resume();
    videoEl.play().catch(() => {});
  }

  function pause() {
    if (videoEl && !videoEl.paused) {
      videoEl.pause();
    }
  }

  function seek(seconds) {
    if (!videoEl) return;
    const target = Math.max(0, Math.min(seconds, videoEl.duration || 0));
    videoEl.currentTime = target;
  }

  function seekDelta(deltaSeconds) {
    if (!videoEl) return 0;
    const target = Math.max(0, Math.min(videoEl.currentTime + deltaSeconds, videoEl.duration || 0));
    videoEl.currentTime = target;
    return target;
  }

  /**
   * Volume Control with MX Player HW+ Audio Boost up to 200%
   * level: 0.0 to 2.0 (e.g. 1.0 = 100%, 1.5 = 150%, 2.0 = 200%)
   */
  function setVolume(level) {
    setupWebAudio();
    volumeLevel = Math.max(0, Math.min(2.0, parseFloat(level) || 0));

    if (videoEl) {
      // Normal HTML5 video volume only accepts 0.0 - 1.0
      videoEl.volume = Math.min(1.0, volumeLevel);
      if (volumeLevel > 0) videoEl.muted = false;
    }

    // Boost beyond 100% is handled through Web Audio GainNode
    if (gainNode) {
      gainNode.gain.setValueAtTime(volumeLevel, audioContext ? audioContext.currentTime : 0);
    }

    if (externalAudioEl) {
      externalAudioEl.volume = Math.min(1.0, volumeLevel);
    }

    return volumeLevel;
  }

  function getVolume() {
    return volumeLevel;
  }

  function toggleMute() {
    if (!videoEl) return false;
    isMuted = !isMuted;
    videoEl.muted = isMuted;
    if (externalAudioEl) externalAudioEl.muted = isMuted;
    return isMuted;
  }

  /**
   * Set Playback Rate (Speed) with pitch preservation
   * rates: 0.25x to 4.0x
   */
  function setPlaybackRate(rate) {
    if (!videoEl) return 1.0;
    const cleanRate = Math.max(0.25, Math.min(4.0, parseFloat(rate) || 1.0));
    videoEl.playbackRate = cleanRate;
    videoEl.preservesPitch = true;
    if (externalAudioEl) {
      externalAudioEl.playbackRate = cleanRate;
      externalAudioEl.preservesPitch = true;
    }
    return cleanRate;
  }

  /**
   * Equalizer Preset application
   */
  function setEqualizerPreset(presetName) {
    const gains = EQ_PRESETS[presetName] || EQ_PRESETS.normal;
    if (eqFilters && eqFilters.length === gains.length) {
      eqFilters.forEach((filter, index) => {
        filter.gain.setValueAtTime(gains[index], audioContext ? audioContext.currentTime : 0);
      });
    }
    return presetName;
  }

  /**
   * Dual Audio & Multi-Audio Track Detection & Selection
   */
  function getAudioTracks() {
    const list = [];
    // Check HTML5 video audioTracks API (available in Safari, Edge, Chrome with flag)
    if (videoEl && videoEl.audioTracks && videoEl.audioTracks.length > 0) {
      for (let i = 0; i < videoEl.audioTracks.length; i++) {
        const track = videoEl.audioTracks[i];
        list.push({
          id: i,
          type: 'embedded',
          label: track.label || track.language || `Audio Track ${i + 1}`,
          language: track.language || 'Unknown',
          enabled: track.enabled
        });
      }
    } else {
      // Default primary video audio
      list.push({
        id: 'primary',
        type: 'primary',
        label: 'Default Audio (Track 1)',
        language: 'Stereo',
        enabled: !externalAudioEl.src || externalAudioEl.paused
      });
    }

    // Add external audio if loaded
    if (externalAudioEl && externalAudioEl.src) {
      list.push({
        id: 'external',
        type: 'external',
        label: externalAudioEl.dataset.title || 'External Audio Dub',
        language: 'Custom Track',
        enabled: videoEl.muted && !externalAudioEl.paused
      });
    }

    return list;
  }

  function selectAudioTrack(trackId) {
    if (trackId === 'primary') {
      if (videoEl) videoEl.muted = false;
      if (externalAudioEl) {
        externalAudioEl.pause();
      }
    } else if (trackId === 'external') {
      if (videoEl) videoEl.muted = true; // mute video, play external track
      if (externalAudioEl && externalAudioEl.src) {
        externalAudioEl.currentTime = Math.max(0, videoEl.currentTime + externalAudioOffset);
        if (!videoEl.paused) externalAudioEl.play().catch(() => {});
      }
    } else if (typeof trackId === 'number' && videoEl && videoEl.audioTracks) {
      for (let i = 0; i < videoEl.audioTracks.length; i++) {
        videoEl.audioTracks[i].enabled = (i === trackId);
      }
    }
  }

  /**
   * Load external audio file (e.g. Hindi/Spanish/English dub MP3/AAC)
   */
  function loadExternalAudioFile(file) {
    try {
      const url = URL.createObjectURL(file);
      externalAudioEl.src = url;
      externalAudioEl.dataset.title = file.name;
      // Switch to external audio
      selectAudioTrack('external');
      return { success: true, name: file.name };
    } catch (e) {
      return { success: false, error: e.message };
    }
  }

  function setExternalAudioOffset(offsetSeconds) {
    externalAudioOffset = parseFloat(offsetSeconds) || 0;
    if (externalAudioEl && videoEl) {
      externalAudioEl.currentTime = Math.max(0, videoEl.currentTime + externalAudioOffset);
    }
  }

  /**
   * Aspect Ratio Switcher
   */
  function setAspectRatio(mode) {
    if (!videoEl) return;
    videoEl.classList.remove('aspect-fit', 'aspect-stretch', 'aspect-crop', 'aspect-100', 'aspect-16-9', 'aspect-4-3');
    videoEl.classList.add(`aspect-${mode}`);
    return mode;
  }

  /**
   * Picture-in-Picture Mode
   */
  async function togglePiP() {
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

  /**
   * Fullscreen Toggle
   */
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

  /**
   * Format Seconds into HH:MM:SS or MM:SS
   */
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

  /**
   * Load video from File or URL
   */
  function loadSource(fileOrUrl, title = '') {
    if (!videoEl) return;
    currentVideoFile = fileOrUrl;
    currentFileKey = StorageManager.generateFileKey(fileOrUrl);

    let src = '';
    let videoTitle = title;

    if (typeof fileOrUrl === 'string') {
      src = fileOrUrl;
      if (!videoTitle) {
        videoTitle = fileOrUrl.split('/').pop().split('?')[0] || 'Web Stream';
      }
    } else if (fileOrUrl instanceof File || fileOrUrl instanceof Blob) {
      src = URL.createObjectURL(fileOrUrl);
      if (!videoTitle) videoTitle = fileOrUrl.name;
    }

    videoEl.src = src;
    videoEl.load();

    return {
      fileKey: currentFileKey,
      title: videoTitle
    };
  }

  function getCurrentFileKey() {
    return currentFileKey;
  }

  function getCurrentVideoFile() {
    return currentVideoFile;
  }

  return {
    init,
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
    loadExternalAudioFile,
    setExternalAudioOffset,
    formatTime,
    getCurrentFileKey,
    getCurrentVideoFile
  };
})();
