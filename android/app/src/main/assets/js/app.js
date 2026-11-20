/**
 * QB Player by Naqeeb - Main Application Controller
 * Connects UI elements, audio settings, subtitles, resume prompts, and event listeners.
 */

window.onerror = function(message, source, lineno, colno, error) {
  console.error('[QB Player Global Error]', message, 'at', source, lineno + ':' + colno, error);
};
window.addEventListener('unhandledrejection', function(event) {
  console.error('[QB Player Unhandled Rejection]', event.reason);
});

document.addEventListener('DOMContentLoaded', () => {
  // DOM Elements
  const container = document.getElementById('player-container');
  const video = document.getElementById('video-element');
  const welcomeScreen = document.getElementById('welcome-screen');
  const playerControls = document.getElementById('player-controls');
  const brightnessOverlay = document.getElementById('brightness-overlay');
  const topbarTitle = document.getElementById('topbar-video-title');
  const topbarMeta = document.getElementById('topbar-video-meta');

  // OSD Elements
  const osdVolume = document.getElementById('osd-volume');
  const osdBrightness = document.getElementById('osd-brightness');
  const osdSeek = document.getElementById('osd-seek');
  const rippleLeft = document.getElementById('ripple-left');
  const rippleRight = document.getElementById('ripple-right');
  const flashPlayPause = document.getElementById('flash-play-pause');

  // Subtitle elements
  const subContainer = document.getElementById('subtitles-container');
  const subText = document.getElementById('subtitles-text');

  // Controls Elements
  const btnPlayPause = document.getElementById('btn-play-pause');
  const btnPrev = document.getElementById('btn-prev');
  const btnNext = document.getElementById('btn-next');
  const btnRewind10 = document.getElementById('btn-rewind-10');
  const btnForward10 = document.getElementById('btn-forward-10');
  const btnMute = document.getElementById('btn-mute');
  const volumeSlider = document.getElementById('volume-slider');
  const currentTimeEl = document.getElementById('current-time');
  const totalDurationEl = document.getElementById('total-duration');
  const seekbarContainer = document.getElementById('seekbar-container');
  const seekbarProgress = document.getElementById('seekbar-progress');
  const seekbarBuffer = document.getElementById('seekbar-buffer');
  const seekbarHandle = document.getElementById('seekbar-handle');
  const seekbarTooltip = document.getElementById('seekbar-tooltip');
  const btnSpeed = document.getElementById('btn-speed');
  const btnAspect = document.getElementById('btn-aspect');
  const btnAudio = document.getElementById('btn-audio');
  const btnSubtitles = document.getElementById('btn-subtitles');
  const btnLock = document.getElementById('btn-lock');
  const btnUnlock = document.getElementById('btn-unlock');
  const btnPlaylist = document.getElementById('btn-playlist');
  const btnPiP = document.getElementById('btn-pip');
  const btnFullscreen = document.getElementById('btn-fullscreen');
  const btnBack = document.getElementById('btn-back');

  // Modals & Drawer
  const resumeToast = document.getElementById('resume-toast');
  const playlistDrawer = document.getElementById('playlist-drawer');
  const audioModal = document.getElementById('modal-audio');
  const subtitleModal = document.getElementById('modal-subtitles');
  const speedModal = document.getElementById('modal-speed');
  const urlModal = document.getElementById('modal-url');
  const shortcutsModal = document.getElementById('modal-shortcuts');

  // File Inputs
  const inputVideoFile = document.getElementById('input-video-file');
  const inputFolder = document.getElementById('input-folder');
  const inputSubFile = document.getElementById('input-sub-file');
  const inputAudioFile = document.getElementById('input-audio-file');

  // State
  let controlsTimeout = null;
  let isSeeking = false;
  let currentVideoDuration = 0;

  function getVideoDuration() {
    if (window.PlayerCore && typeof PlayerCore.getDuration === 'function') {
      const d = PlayerCore.getDuration();
      if (d > 0) {
        currentVideoDuration = d;
        return d;
      }
    }
    if (window.AndroidBridge && typeof window.AndroidBridge.getDuration === 'function') {
      try {
        const d = window.AndroidBridge.getDuration() / 1000;
        if (d > 0) {
          currentVideoDuration = d;
          return d;
        }
      } catch (e) {}
    }
    if (video && video.duration && !isNaN(video.duration) && video.duration > 0) {
      currentVideoDuration = video.duration;
      return video.duration;
    }
    return currentVideoDuration || 0;
  }

  let currentAspectIndex = 0;
  const aspectModes = ['fit', 'crop', 'stretch', '16-9', '4-3', '100'];
  const aspectLabels = ['Fit', 'Crop', 'Stretch', '16:9', '4:3', '100%'];

  // Initialize Modules
  PlayerCore.init(video);
  SubtitlesManager.init(subContainer, subText);
  GesturesManager.init({
    container,
    brightnessOverlay,
    osdVolume,
    osdBrightness,
    osdSeek,
    rippleLeft,
    rippleRight,
    flashPlayPause
  });
  PlaylistManager.init(document.getElementById('drawer-playlist-items'));
  MediaBrowser.init();

  // Load Saved Settings
  const userSettings = StorageManager.loadSettings();
  if (userSettings) {
    if (userSettings.volume) {
      PlayerCore.setVolume(userSettings.volume * (userSettings.audioBoost || 1.0));
      volumeSlider.value = userSettings.volume * 100;
    }
    if (userSettings.subtitleSize) SubtitlesManager.setFontSize(userSettings.subtitleSize);
    if (userSettings.subtitleColor) SubtitlesManager.setColor(userSettings.subtitleColor);
    if (userSettings.subtitleBg) SubtitlesManager.setBackgroundStyle(userSettings.subtitleBg);
    if (userSettings.subtitleOffset) SubtitlesManager.setSyncOffset(userSettings.subtitleOffset);
  }

  // ================= Control Bar Visibility Management =================
  function showControls() {
    if (GesturesManager.getIsLocked()) return;
    playerControls.classList.add('visible');
    container.classList.add('controls-visible');
    scheduleHideControls();
  }

  function hideControls(force = false) {
    if (video.paused && !force) return; // Keep visible indefinitely while paused unless forced by user tap
    playerControls.classList.remove('visible');
    container.classList.remove('controls-visible');
  }

  function scheduleHideControls() {
    if (controlsTimeout) clearTimeout(controlsTimeout);
    if (video.paused) return; // Stay visible while paused!
    controlsTimeout = setTimeout(() => {
      hideControls();
    }, 4500); // 4.5 seconds
  }

  // Prevent controls from disappearing when interacting with controls
  playerControls.addEventListener('pointerdown', (e) => {
    scheduleHideControls();
  });

  window.addEventListener('qb:show-controls', showControls);
  window.addEventListener('qb:hide-controls', () => hideControls(true));
  window.addEventListener('qb:toggle-controls', () => {
    if (playerControls.classList.contains('visible')) {
      hideControls(true);
    } else {
      showControls();
    }
  });

  // ================= Video Playback Events =================
  video.addEventListener('play', () => {
    updatePlayPauseButton(true);
    scheduleHideControls();
  });

  video.addEventListener('pause', () => {
    updatePlayPauseButton(false);
    showControls();
  });

  video.addEventListener('timeupdate', () => {
    if (!isSeeking && video.duration) {
      const current = video.currentTime;
      const duration = video.duration;
      currentTimeEl.textContent = PlayerCore.formatTime(current);
      const pct = (current / duration) * 100;
      seekbarProgress.style.width = `${pct}%`;
      seekbarHandle.style.left = `${pct}%`;

      // Save position periodically (every ~3s) for "Play Where Left"
      if (Math.floor(current) % 3 === 0) {
        StorageManager.savePlaybackPosition(PlayerCore.getCurrentFileKey(), {
          title: topbarTitle.textContent,
          currentTime: current,
          duration: duration
        });
      }

      // Update Subtitles
      SubtitlesManager.update(current);
    }
  });

  video.addEventListener('durationchange', () => {
    totalDurationEl.textContent = PlayerCore.formatTime(video.duration);
  });

  video.addEventListener('progress', () => {
    if (video.buffered.length > 0 && video.duration) {
      const bufferedEnd = video.buffered.end(video.buffered.length - 1);
      const pct = (bufferedEnd / video.duration) * 100;
      seekbarBuffer.style.width = `${pct}%`;
    }
  });

  video.addEventListener('loadedmetadata', () => {
    totalDurationEl.textContent = PlayerCore.formatTime(video.duration);
    if (topbarMeta && video.videoWidth) {
      topbarMeta.textContent = `${video.videoWidth}x${video.videoHeight} • Zero Ads`;
    }

    // Auto rotate landscape movies on Android / Oppo Pad
    if (video.videoWidth && video.videoHeight && video.videoWidth >= video.videoHeight) {
      if (window.AndroidBridge && window.AndroidBridge.setOrientation) {
        window.AndroidBridge.setOrientation('landscape');
      }
    }

    // Check for saved resume position ("Play Where Left")
    checkAndPromptResume();
  });

  // Native ExoPlayer Event Listeners
  window.addEventListener('qb:play-state', (e) => {
    updatePlayPauseButton(e.detail.isPlaying);
  });

  window.addEventListener('qb:native-metadata', (e) => {
    if (e.detail && e.detail.duration > 0) {
      currentVideoDuration = e.detail.duration;
    }
    const dur = getVideoDuration();
    if (totalDurationEl && dur > 0) totalDurationEl.textContent = PlayerCore.formatTime(dur);
    checkAndPromptResume();
  });

  window.addEventListener('qb:native-ended', () => {
    PlaylistManager.playNext();
  });

  window.addEventListener('qb:native-tracks', () => {
    if (audioModal && audioModal.classList.contains('open')) populateAudioModal();
    if (subtitleModal && subtitleModal.classList.contains('open')) populateSubtitleModal();
  });

  window.addEventListener('qb:native-timeupdate', (e) => {
    const { currentTime, duration, isPlaying } = e.detail;
    if (duration > 0) {
      currentVideoDuration = duration;
    }
    const dur = duration > 0 ? duration : getVideoDuration();
    if (!isSeeking) {
      if (currentTimeEl) currentTimeEl.textContent = PlayerCore.formatTime(currentTime);
      if (dur > 0) {
        if (totalDurationEl) totalDurationEl.textContent = PlayerCore.formatTime(dur);
        const pct = (currentTime / dur) * 100;
        if (seekbarProgress) seekbarProgress.style.width = `${pct}%`;
        if (seekbarHandle) seekbarHandle.style.left = `${pct}%`;
      }
    }
    updatePlayPauseButton(isPlaying);

    const fileKey = PlayerCore.getCurrentFileKey();
    if (fileKey && dur > 0) {
      StorageManager.savePlaybackPosition(fileKey, currentTime, dur);
    }
  });

  video.addEventListener('ended', () => {
    PlaylistManager.playNext();
  });

  function updatePlayPauseButton(isPlaying) {
    const playSvg = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
    const pauseSvg = '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';
    btnPlayPause.innerHTML = isPlaying ? pauseSvg : playSvg;

    const btnCenter = document.getElementById('btn-center-play');
    if (btnCenter) {
      btnCenter.innerHTML = isPlaying ? pauseSvg : playSvg;
    }
  }

  // ================= "Play Where Left" Resume Handling =================
  let resumeTimer = null;

  function checkAndPromptResume() {
    const fileKey = PlayerCore.getCurrentFileKey();
    if (!fileKey) return;

    const saved = StorageManager.getPlaybackPosition(fileKey);
    const dur = getVideoDuration();
    // Only prompt if saved time is > 5s and video is not completed
    if (saved && saved.timestamp > 5 && (!saved.completed || (dur > 0 && saved.timestamp < (dur - 15)))) {
      const timeStr = PlayerCore.formatTime(saved.timestamp);
      document.getElementById('resume-time-str').textContent = timeStr;
      resumeToast.classList.add('active');

      const btnResume = document.getElementById('btn-confirm-resume');
      const btnRestart = document.getElementById('btn-cancel-resume');

      btnResume.onclick = () => {
        PlayerCore.seek(saved.timestamp);
        PlayerCore.play();
        dismissResumeToast();
      };

      btnRestart.onclick = () => {
        PlayerCore.seek(0);
        PlayerCore.play();
        dismissResumeToast();
      };

      // Auto-dismiss after 8s
      if (resumeTimer) clearTimeout(resumeTimer);
      resumeTimer = setTimeout(() => {
        dismissResumeToast();
      }, 8000);
    } else {
      PlayerCore.play();
    }
  }

  function dismissResumeToast() {
    resumeToast.classList.remove('active');
    if (resumeTimer) clearTimeout(resumeTimer);
  }

  // ================= Seekbar Scrubber Handling =================
  let seekDragTarget = 0;
  let lastSeekThrottleTime = 0;

  function updateSeekbarFromEvent(e) {
    const rect = seekbarContainer.getBoundingClientRect();
    if (rect.width <= 0) return 0;
    const clientX = e.clientX !== undefined ? e.clientX : (e.touches && e.touches[0] ? e.touches[0].clientX : 0);
    const pos = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    const duration = getVideoDuration();
    const targetTime = pos * duration;

    seekbarProgress.style.width = `${pos * 100}%`;
    seekbarHandle.style.left = `${pos * 100}%`;
    if (currentTimeEl) {
      currentTimeEl.textContent = PlayerCore.formatTime(targetTime);
    }

    return targetTime;
  }

  seekbarContainer.addEventListener('pointerdown', (e) => {
    isSeeking = true;
    seekbarContainer.classList.add('dragging');
    try {
      seekbarContainer.setPointerCapture(e.pointerId);
    } catch (ignored) {}
    seekDragTarget = updateSeekbarFromEvent(e);
    PlayerCore.seek(seekDragTarget);
    lastSeekThrottleTime = Date.now();
  });

  window.addEventListener('pointermove', (e) => {
    if (isSeeking) {
      seekDragTarget = updateSeekbarFromEvent(e);
      const now = Date.now();
      if (now - lastSeekThrottleTime > 100) {
        lastSeekThrottleTime = now;
        PlayerCore.seek(seekDragTarget);
      }
    }
    // Hover tooltip
    const rect = seekbarContainer.getBoundingClientRect();
    if (rect.width > 0 && e.clientY >= rect.top - 20 && e.clientY <= rect.bottom + 20) {
      const clientX = e.clientX !== undefined ? e.clientX : 0;
      const pos = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
      const hoverSec = pos * getVideoDuration();
      seekbarTooltip.textContent = PlayerCore.formatTime(hoverSec);
      seekbarTooltip.style.left = `${pos * 100}%`;
    }
  });

  const onSeekEnd = (e) => {
    if (isSeeking) {
      if (e && e.pointerId) {
        try {
          seekbarContainer.releasePointerCapture(e.pointerId);
        } catch (ignored) {}
      }
      isSeeking = false;
      seekbarContainer.classList.remove('dragging');
      PlayerCore.seek(seekDragTarget);
    }
  };

  window.addEventListener('pointerup', onSeekEnd);
  window.addEventListener('pointercancel', onSeekEnd);

  // ================= Button Click Handlers =================
  btnPlayPause.addEventListener('click', () => {
    PlayerCore.togglePlay();
  });

  btnRewind10.addEventListener('click', () => {
    PlayerCore.seekDelta(-10);
  });

  btnForward10.addEventListener('click', () => {
    PlayerCore.seekDelta(10);
  });

  btnPrev.addEventListener('click', () => {
    PlaylistManager.playPrev();
  });

  btnNext.addEventListener('click', () => {
    PlaylistManager.playNext();
  });

  // Volume slider in bottom bar
  volumeSlider.addEventListener('input', (e) => {
    const val = parseFloat(e.target.value) / 100;
    PlayerCore.setVolume(val);
  });

  btnMute.addEventListener('click', () => {
    const muted = PlayerCore.toggleMute();
    btnMute.classList.toggle('active', muted);
  });

  // Aspect Ratio Button
  btnAspect.addEventListener('click', () => {
    currentAspectIndex = (currentAspectIndex + 1) % aspectModes.length;
    const mode = aspectModes[currentAspectIndex];
    PlayerCore.setAspectRatio(mode);
    btnAspect.title = `Aspect: ${aspectLabels[currentAspectIndex]}`;
    showQuickNotification(`Aspect: ${aspectLabels[currentAspectIndex]}`);
  });

  // Picture in Picture
  btnPiP.addEventListener('click', () => {
    PlayerCore.togglePiP();
  });

  // Fullscreen
  btnFullscreen.addEventListener('click', () => {
    PlayerCore.toggleFullscreen(container);
  });

  // Back button (returns to MX Player media folders browser)
  btnBack.addEventListener('click', () => {
    MediaBrowser.returnToBrowser();
  });

  // Rotation Toggle Button (MX Player Screen Orientation)
  const btnRotate = document.getElementById('btn-rotate');
  if (btnRotate) {
    btnRotate.addEventListener('click', (e) => {
      e.stopPropagation();
      if (window.AndroidBridge && window.AndroidBridge.toggleOrientation) {
        window.AndroidBridge.toggleOrientation();
      } else {
        showQuickNotification('Screen Rotation');
      }
    });
  }

  // Center Screen Play/Pause Button
  const btnCenterPlay = document.getElementById('btn-center-play');
  if (btnCenterPlay) {
    btnCenterPlay.addEventListener('click', (e) => {
      e.stopPropagation();
      PlayerCore.togglePlay();
      scheduleHideControls();
    });
  }

  // Screen Lock (Kid Lock)
  btnLock.addEventListener('click', () => {
    GesturesManager.setLocked(true);
    showQuickNotification('Screen Locked');
  });

  btnUnlock.addEventListener('click', () => {
    GesturesManager.setLocked(false);
    showQuickNotification('Screen Unlocked');
  });

  // Drawer Toggles
  btnPlaylist.addEventListener('click', () => {
    playlistDrawer.classList.toggle('open');
    PlaylistManager.render();
  });

  document.getElementById('btn-close-playlist').addEventListener('click', () => {
    playlistDrawer.classList.remove('open');
  });

  // ================= Modal Dialogs Opening & Controls =================
  // Audio Modal
  btnAudio.addEventListener('click', () => {
    populateAudioModal();
    audioModal.classList.add('open');
  });

  // Subtitles Modal
  btnSubtitles.addEventListener('click', () => {
    populateSubtitleModal();
    subtitleModal.classList.add('open');
  });

  // Speed Modal
  btnSpeed.addEventListener('click', () => {
    speedModal.classList.add('open');
  });

  // Close modals when clicking close button or backdrop
  document.querySelectorAll('.modal-close, .modal-backdrop').forEach(el => {
    el.addEventListener('click', (e) => {
      if (e.target === el || e.target.classList.contains('modal-close')) {
        document.querySelectorAll('.modal-backdrop').forEach(m => m.classList.remove('open'));
      }
    });
  });

  // ================= Audio & Dual Track Population =================
  function populateAudioModal() {
    const listEl = document.getElementById('audio-track-list');
    listEl.innerHTML = '';

    const tracks = PlayerCore.getAudioTracks();
    if (tracks && tracks.length > 0) {
      tracks.forEach(track => {
        const isSelected = !!track.isSelected;
        const item = document.createElement('div');
        item.className = `track-item ${isSelected ? 'selected' : ''}`;

        const nameSpan = document.createElement('span');
        nameSpan.textContent = track.name;
        item.appendChild(nameSpan);

        if (isSelected) {
          const activeSpan = document.createElement('span');
          activeSpan.textContent = '✓ Active';
          item.appendChild(activeSpan);
        }

        item.addEventListener('click', () => {
          PlayerCore.selectAudioTrack(track);
          showQuickNotification(`Audio: ${track.name}`);
          setTimeout(populateAudioModal, 150);
        });
        listEl.appendChild(item);
      });
    } else {
      listEl.innerHTML = '<div style="color: var(--text-muted); font-size: 0.85rem;">Default Stereo Audio Active</div>';
    }

    // HW+ Boost Slider (100% to 200%)
    const boostSlider = document.getElementById('audio-boost-slider');
    const boostVal = document.getElementById('audio-boost-val');
    boostSlider.value = Math.round(PlayerCore.getVolume() * 100);
    boostVal.textContent = `${boostSlider.value}%`;

    boostSlider.oninput = (e) => {
      const val = parseInt(e.target.value, 10);
      boostVal.textContent = `${val}%`;
      PlayerCore.setVolume(val / 100);
      volumeSlider.value = Math.min(100, val);
    };

    // Equalizer preset chips
    document.querySelectorAll('.eq-preset-chip').forEach(chip => {
      chip.onclick = () => {
        document.querySelectorAll('.eq-preset-chip').forEach(c => c.classList.remove('selected'));
        chip.classList.add('selected');
        PlayerCore.setEqualizerPreset(chip.dataset.preset);
      };
    });

    // External audio button
    document.getElementById('btn-load-ext-audio').onclick = () => {
      inputAudioFile.click();
    };

    inputAudioFile.onchange = (e) => {
      if (e.target.files.length > 0) {
        PlayerCore.loadExternalAudioFile(e.target.files[0]);
        populateAudioModal();
      }
    };
  }

  // ================= Subtitles Modal Population =================
  function populateSubtitleModal() {
    const trackListEl = document.getElementById('sub-track-list');
    trackListEl.innerHTML = '';

    const tracks = PlayerCore.getSubtitleTracks();
    const anySelected = tracks && tracks.some(t => t.isSelected);

    // "None (Subtitles Off)" option
    const noneItem = document.createElement('div');
    noneItem.className = `track-item ${!anySelected ? 'selected' : ''}`;
    noneItem.innerHTML = `<span>None (Subtitles Off)</span>${!anySelected ? '<span>✓ Active</span>' : ''}`;
    noneItem.addEventListener('click', () => {
      PlayerCore.selectSubtitleTrack(null);
      showQuickNotification('Subtitles Off');
      setTimeout(populateSubtitleModal, 150);
    });
    trackListEl.appendChild(noneItem);

    if (tracks && tracks.length > 0) {
      tracks.forEach(track => {
        const item = document.createElement('div');
        item.className = `track-item ${track.isSelected ? 'selected' : ''}`;

        const nameSpan = document.createElement('span');
        nameSpan.textContent = track.name;
        item.appendChild(nameSpan);

        if (track.isSelected) {
          const activeSpan = document.createElement('span');
          activeSpan.textContent = '✓ Active';
          item.appendChild(activeSpan);
        }

        item.addEventListener('click', () => {
          PlayerCore.selectSubtitleTrack(track);
          showQuickNotification(`Subtitles: ${track.name}`);
          setTimeout(populateSubtitleModal, 150);
        });
        trackListEl.appendChild(item);
      });
    }

    // Subtitle Sync Offset
    const syncValEl = document.getElementById('sub-sync-val');
    const syncSlider = document.getElementById('sub-sync-slider');
    const offset = SubtitlesManager.getSyncOffset ? SubtitlesManager.getSyncOffset() : 0;
    syncValEl.textContent = `${offset >= 0 ? '+' : ''}${offset.toFixed(2)}s`;
    syncSlider.value = offset;

    syncSlider.oninput = (e) => {
      const val = parseFloat(e.target.value);
      if (SubtitlesManager.setSyncOffset) SubtitlesManager.setSyncOffset(val);
      syncValEl.textContent = `${val >= 0 ? '+' : ''}${val.toFixed(2)}s`;
    };

    document.getElementById('btn-sub-sync-minus').onclick = () => {
      const newOffset = SubtitlesManager.adjustSyncOffset ? SubtitlesManager.adjustSyncOffset(-0.1) : 0;
      syncSlider.value = newOffset;
      syncValEl.textContent = `${newOffset >= 0 ? '+' : ''}${newOffset.toFixed(2)}s`;
    };

    document.getElementById('btn-sub-sync-plus').onclick = () => {
      const newOffset = SubtitlesManager.adjustSyncOffset ? SubtitlesManager.adjustSyncOffset(0.1) : 0;
      syncSlider.value = newOffset;
      syncValEl.textContent = `${newOffset >= 0 ? '+' : ''}${newOffset.toFixed(2)}s`;
    };

    // Subtitle Size Slider
    const sizeSlider = document.getElementById('sub-size-slider');
    const sizeVal = document.getElementById('sub-size-val');
    sizeSlider.value = SubtitlesManager.styleConfig ? SubtitlesManager.styleConfig.fontSize : 24;
    sizeVal.textContent = `${sizeSlider.value}px`;

    sizeSlider.oninput = (e) => {
      sizeVal.textContent = `${e.target.value}px`;
      if (SubtitlesManager.setFontSize) SubtitlesManager.setFontSize(e.target.value);
    };

    // Load subtitle file button
    document.getElementById('btn-load-sub-file').onclick = () => {
      inputSubFile.click();
    };

    inputSubFile.onchange = async (e) => {
      if (e.target.files.length > 0) {
        const file = e.target.files[0];
        const res = await SubtitlesManager.loadFromFile(file);
        if (res.success) {
          populateSubtitleModal();
          showQuickNotification(`Loaded: ${res.trackName}`);
        }
      }
    };
  }

  // ================= Speed Modal Population =================
  document.querySelectorAll('.speed-option').forEach(btn => {
    btn.onclick = () => {
      const rate = parseFloat(btn.dataset.speed);
      PlayerCore.setPlaybackRate(rate);
      const speedBadge = document.querySelector('.speed-badge');
      if (speedBadge) speedBadge.textContent = `${rate}x`;
      document.querySelectorAll('.speed-option').forEach(b => b.classList.remove('selected'));
      btn.classList.add('selected');
      if (speedModal) speedModal.classList.remove('open');
      showQuickNotification(`Speed: ${rate}x`);
    };
  });

  // ================= Welcome Screen File Loaders (Optional) =================
  const btnWelcomeOpenFile = document.getElementById('btn-welcome-open-file');
  if (btnWelcomeOpenFile) {
    btnWelcomeOpenFile.addEventListener('click', () => {
      if (inputVideoFile) inputVideoFile.click();
    });
  }

  const btnWelcomeOpenFolder = document.getElementById('btn-welcome-open-folder');
  if (btnWelcomeOpenFolder) {
    btnWelcomeOpenFolder.addEventListener('click', () => {
      if (inputFolder) inputFolder.click();
    });
  }

  const btnWelcomeOpenUrl = document.getElementById('btn-welcome-open-url');
  if (btnWelcomeOpenUrl) {
    btnWelcomeOpenUrl.addEventListener('click', () => {
      if (urlModal) urlModal.classList.add('open');
    });
  }

  if (inputVideoFile) {
    inputVideoFile.addEventListener('change', (e) => {
      if (e.target.files.length > 0) {
        PlaylistManager.addFiles(Array.from(e.target.files), true);
      }
    });
  }

  if (inputFolder) {
    inputFolder.addEventListener('change', (e) => {
      if (e.target.files.length > 0) {
        const videoFiles = Array.from(e.target.files).filter(f =>
          f.type.startsWith('video/') ||
          ['mp4', 'mkv', 'webm', 'avi', 'mov', 'ts'].includes(f.name.split('.').pop().toLowerCase())
        );
        if (videoFiles.length > 0) {
          PlaylistManager.addFiles(videoFiles, true);
        } else {
          alert('No compatible video files found in the selected folder.');
        }
      }
    });
  }

  const btnConfirmUrl = document.getElementById('btn-confirm-url');
  if (btnConfirmUrl) {
    btnConfirmUrl.addEventListener('click', () => {
      const urlInput = document.getElementById('input-stream-url');
      if (urlInput) {
        const url = urlInput.value.trim();
        if (url) {
          PlaylistManager.addUrl(url);
          if (urlModal) urlModal.classList.remove('open');
          urlInput.value = '';
        }
      }
    });
  }

  // Quick toast notification helper
  function showQuickNotification(msg) {
    const toast = document.createElement('div');
    toast.style.cssText = `
      position: absolute;
      top: 70px;
      left: 50%;
      transform: translateX(-50%);
      background: rgba(15, 23, 42, 0.9);
      border: 1px solid var(--accent-cyan);
      color: #fff;
      padding: 8px 18px;
      border-radius: 20px;
      font-size: 0.88rem;
      font-weight: 600;
      z-index: 50;
      pointer-events: none;
      box-shadow: 0 4px 20px rgba(0, 210, 255, 0.3);
      animation: fadeInOut 2.2s forwards;
    `;
    toast.textContent = msg;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 2200);
  }

  window.addEventListener('qb:toast', (e) => {
    showQuickNotification(e.detail);
  });

  // ================= Keyboard Shortcuts =================
  window.addEventListener('keydown', (e) => {
    if (['input', 'textarea'].includes(document.activeElement.tagName.toLowerCase())) {
      return;
    }

    switch (e.code) {
      case 'Space':
      case 'KeyK':
        e.preventDefault();
        PlayerCore.togglePlay();
        break;
      case 'ArrowLeft':
        e.preventDefault();
        PlayerCore.seekDelta(e.shiftKey ? -15 : -5);
        break;
      case 'ArrowRight':
        e.preventDefault();
        PlayerCore.seekDelta(e.shiftKey ? 15 : 5);
        break;
      case 'ArrowUp':
        e.preventDefault();
        PlayerCore.setVolume(PlayerCore.getVolume() + 0.05);
        break;
      case 'ArrowDown':
        e.preventDefault();
        PlayerCore.setVolume(PlayerCore.getVolume() - 0.05);
        break;
      case 'KeyF':
        PlayerCore.toggleFullscreen(container);
        break;
      case 'KeyM':
        PlayerCore.toggleMute();
        break;
      case 'KeyS':
        SubtitlesManager.toggleEnabled();
        showQuickNotification(SubtitlesManager.toggleEnabled() ? 'Subtitles On' : 'Subtitles Off');
        break;
      case 'KeyL':
        GesturesManager.setLocked(!GesturesManager.getIsLocked());
        break;
      case 'KeyC':
        btnAspect.click();
        break;
      case 'Slash':
        if (e.shiftKey) shortcutsModal.classList.add('open');
        break;
    }
  });

  // Register PWA Service Worker for offline performance
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./service-worker.js').catch(() => {});
    });
  }
});
