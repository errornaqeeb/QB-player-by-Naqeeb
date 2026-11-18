/**
 * QB Player - MX Player Gestures Controller
 * Replicates 1:1 the signature MX Player touch & mouse drag gestures:
 * - Left vertical drag: Brightness OSD
 * - Right vertical drag: Volume & Boost OSD
 * - Horizontal drag: Seeking with delta & target OSD
 * - Double tap left/right: 10s skip ripple
 * - Double tap center: Play/Pause toggle
 * - Screen lock handling
 */

const GesturesManager = (function() {
  let containerEl = null;
  let brightnessOverlayEl = null;
  let osdVolumeEl = null;
  let osdBrightnessEl = null;
  let osdSeekEl = null;
  let rippleLeftEl = null;
  let rippleRightEl = null;
  let flashPlayPauseEl = null;

  // State variables
  let isLocked = false;
  let isDragging = false;
  let gestureType = null; // 'brightness', 'volume', 'seek', null
  let startX = 0;
  let startY = 0;
  let initialValue = 0; // initial brightness, volume, or video currentTime
  let pendingSeekTarget = 0;
  let brightness = 1.0; // 0.1 to 1.0 (1.0 = 100% bright, 0.1 = 10% bright)

  // Tap tracking for double-tap detection
  let lastTapTime = 0;
  let lastTapX = 0;
  let lastTapY = 0;
  let singleTapTimer = null;
  const DOUBLE_TAP_DELAY_MS = 280;

  // Auto-hide timer for OSDs
  let osdHideTimeout = null;

  function init(elements) {
    containerEl = elements.container;
    brightnessOverlayEl = elements.brightnessOverlay;
    osdVolumeEl = elements.osdVolume;
    osdBrightnessEl = elements.osdBrightness;
    osdSeekEl = elements.osdSeek;
    rippleLeftEl = elements.rippleLeft;
    rippleRightEl = elements.rippleRight;
    flashPlayPauseEl = elements.flashPlayPause;

    // Apply saved brightness if any
    const saved = StorageManager.loadSettings();
    if (saved && typeof saved.brightness === 'number') {
      setBrightness(saved.brightness);
    }

    bindEvents();
  }

  function bindEvents() {
    if (!containerEl) return;

    // Pointer events handle both Mouse and Touch effortlessly
    containerEl.addEventListener('pointerdown', handlePointerDown, { passive: false });
    window.addEventListener('pointermove', handlePointerMove, { passive: false });
    window.addEventListener('pointerup', handlePointerUp);
    window.addEventListener('pointercancel', handlePointerUp);
  }

  function handlePointerDown(e) {
    // If locked, only allow unlock button click
    if (isLocked) return;

    // Ignore gestures if touching any controls, buttons, sliders, modals, or media browser
    if (e.target.closest('#player-controls, .modal-card, #playlist-drawer, #resume-toast, #media-browser, .ctrl-btn, .btn-center-play')) {
      return;
    }

    const rect = containerEl.getBoundingClientRect();
    startX = e.clientX;
    startY = e.clientY;
    isDragging = false;
    gestureType = null;

    const relX = (startX - rect.left) / rect.width;

    // Record baseline values for gestures
    if (relX < 0.4) {
      initialValue = brightness;
    } else if (relX > 0.6) {
      initialValue = PlayerCore.getVolume();
    } else {
      const vid = document.getElementById('video-element');
      initialValue = vid ? vid.currentTime : 0;
    }
  }

  function handlePointerMove(e) {
    if (isLocked) return;
    if (startX === 0 && startY === 0) return;

    const deltaX = e.clientX - startX;
    const deltaY = e.clientY - startY;
    const absX = Math.abs(deltaX);
    const absY = Math.abs(deltaY);

    // Threshold to distinguish tap from drag
    if (!isDragging) {
      if (absX > 15 || absY > 15) {
        isDragging = true;
        // Determine gesture type based on primary direction
        const rect = containerEl.getBoundingClientRect();
        const relX = (startX - rect.left) / rect.width;

        if (absX > absY * 1.2) {
          gestureType = 'seek';
        } else if (relX < 0.45) {
          gestureType = 'brightness';
        } else if (relX > 0.55) {
          gestureType = 'volume';
        }
      }
    }

    if (isDragging && gestureType) {
      e.preventDefault(); // prevent browser scrolling
      const rect = containerEl.getBoundingClientRect();

      if (gestureType === 'brightness') {
        // Dragging up increases brightness, dragging down decreases
        const deltaPct = -(deltaY / (rect.height * 0.75));
        const newBrightness = Math.max(0.1, Math.min(1.0, initialValue + deltaPct));
        setBrightness(newBrightness);
        showBrightnessOSD(newBrightness);
      } else if (gestureType === 'volume') {
        // Dragging up increases volume (up to 200%), down decreases
        const deltaPct = -(deltaY / (rect.height * 0.75)) * 1.5;
        const newVol = Math.max(0, Math.min(2.0, initialValue + deltaPct));
        PlayerCore.setVolume(newVol);
        showVolumeOSD(newVol);
      } else if (gestureType === 'seek') {
        // Horizontal seek: drag full width = ~90 seconds or 10% of duration
        const vid = document.getElementById('video-element');
        const duration = vid && vid.duration ? vid.duration : 300;
        const seekFactor = Math.min(120, Math.max(30, duration * 0.15));
        const deltaSec = (deltaX / rect.width) * seekFactor;
        pendingSeekTarget = Math.max(0, Math.min(duration, initialValue + deltaSec));
        showSeekOSD(deltaSec, pendingSeekTarget, duration);
      }
    }
  }

  function handlePointerUp(e) {
    if (isLocked) {
      startX = 0;
      startY = 0;
      return;
    }

    // If user tapped on controls, buttons, sliders, modals, or browser, DO NOT process as screen gesture/tap!
    if (e.target.closest('#player-controls, .modal-card, #playlist-drawer, #resume-toast, #media-browser, .ctrl-btn, .btn-center-play')) {
      startX = 0;
      startY = 0;
      return;
    }

    if (isDragging) {
      // If we were seeking, commit the seek target now
      if (gestureType === 'seek') {
        PlayerCore.seek(pendingSeekTarget);
      }
      hideAllOSD();
      isDragging = false;
      gestureType = null;
      startX = 0;
      startY = 0;
      return;
    }

    // Check for tap / double tap
    const now = Date.now();
    const rect = containerEl.getBoundingClientRect();
    const tapX = e.clientX - rect.left;
    const relX = tapX / rect.width;

    if (now - lastTapTime < DOUBLE_TAP_DELAY_MS && Math.abs(e.clientX - lastTapX) < 40) {
      // Double Tap Detected! Cancel single tap
      if (singleTapTimer) {
        clearTimeout(singleTapTimer);
        singleTapTimer = null;
      }
      handleDoubleTap(relX);
      lastTapTime = 0;
    } else {
      // Potential single tap: wait to verify not double tap
      lastTapTime = now;
      lastTapX = e.clientX;
      lastTapY = e.clientY;

      if (singleTapTimer) clearTimeout(singleTapTimer);
      singleTapTimer = setTimeout(() => {
        handleSingleTap();
        singleTapTimer = null;
      }, DOUBLE_TAP_DELAY_MS);
    }

    startX = 0;
    startY = 0;
  }

  /**
   * Single Tap on Video Screen:
   * 1. If controls are hidden: Show controls!
   * 2. If controls are already showing: Pause/Play video right there (MX Player style!)
   */
  function handleSingleTap() {
    const controls = document.getElementById('player-controls');
    if (!controls) return;
    if (!controls.classList.contains('visible')) {
      window.dispatchEvent(new CustomEvent('qb:show-controls'));
    } else {
      PlayerCore.togglePlay();
    }
  }

  /**
   * Double Tap:
   * Left 30%: -10s skip
   * Right 30%: +10s skip
   * Center 40%: Play/Pause
   */
  function handleDoubleTap(relX) {
    if (relX < 0.35) {
      // Left Double Tap: -10s Skip
      PlayerCore.seekDelta(-10);
      triggerRipple(rippleLeftEl);
    } else if (relX > 0.65) {
      // Right Double Tap: +10s Skip
      PlayerCore.seekDelta(10);
      triggerRipple(rippleRightEl);
    } else {
      // Center Double Tap: Play / Pause
      PlayerCore.togglePlay().then(isPlaying => {
        triggerPlayPauseFlash(isPlaying);
      });
    }
  }

  function triggerRipple(element) {
    if (!element) return;
    element.classList.remove('animate');
    void element.offsetWidth; // trigger reflow
    element.classList.add('animate');
  }

  function triggerPlayPauseFlash(isPlaying) {
    if (!flashPlayPauseEl) return;
    flashPlayPauseEl.innerHTML = isPlaying
      ? '<svg viewBox="0 0 24 24" width="38" height="38" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>'
      : '<svg viewBox="0 0 24 24" width="38" height="38" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';
    flashPlayPauseEl.classList.remove('animate');
    void flashPlayPauseEl.offsetWidth;
    flashPlayPauseEl.classList.add('animate');
  }

  /**
   * Brightness Control
   */
  function setBrightness(level) {
    brightness = Math.max(0.1, Math.min(1.0, level));
    if (brightnessOverlayEl) {
      // 1.0 brightness = 0 opacity; 0.1 brightness = 0.85 opacity black
      brightnessOverlayEl.style.opacity = (1 - brightness) * 0.88;
    }
  }

  function showBrightnessOSD(level) {
    if (!osdBrightnessEl) return;
    const pct = Math.round(level * 100);
    const fillEl = osdBrightnessEl.querySelector('.osd-vertical-fill');
    const textEl = osdBrightnessEl.querySelector('.osd-text');

    if (fillEl) fillEl.style.height = `${pct}%`;
    if (textEl) textEl.textContent = `${pct}%`;

    osdBrightnessEl.classList.add('active');
    scheduleOSDHide(osdBrightnessEl);
  }

  /**
   * Volume Control
   */
  function showVolumeOSD(level) {
    if (!osdVolumeEl) return;
    const pct = Math.round(level * 100);
    const fillEl = osdVolumeEl.querySelector('.osd-vertical-fill');
    const textEl = osdVolumeEl.querySelector('.osd-text');

    if (fillEl) {
      const fillHeight = Math.min(100, (level / 2.0) * 100);
      fillEl.style.height = `${fillHeight}%`;
      if (level > 1.0) {
        fillEl.classList.add('boosted');
      } else {
        fillEl.classList.remove('boosted');
      }
    }

    if (textEl) {
      if (level > 1.0) {
        textEl.textContent = `${pct}% ⚡`;
      } else {
        textEl.textContent = `${pct}%`;
      }
    }

    osdVolumeEl.classList.add('active');
    scheduleOSDHide(osdVolumeEl);
  }

  /**
   * Seek Gesture OSD
   */
  function showSeekOSD(deltaSec, targetSec, duration) {
    if (!osdSeekEl) return;
    const deltaEl = osdSeekEl.querySelector('.seek-delta-text');
    const timeEl = osdSeekEl.querySelector('.seek-target-time');
    const barEl = osdSeekEl.querySelector('.seek-preview-fill');

    const sign = deltaSec >= 0 ? '+' : '-';
    const absDelta = Math.abs(Math.round(deltaSec));

    if (deltaEl) {
      deltaEl.textContent = `${sign}${PlayerCore.formatTime(absDelta)}`;
      deltaEl.className = 'seek-delta-text ' + (deltaSec >= 0 ? 'forward' : 'backward');
    }

    if (timeEl) {
      timeEl.textContent = `[${PlayerCore.formatTime(targetSec)} / ${PlayerCore.formatTime(duration)}]`;
    }

    if (barEl && duration > 0) {
      barEl.style.width = `${(targetSec / duration) * 100}%`;
    }

    osdSeekEl.classList.add('active');
  }

  function scheduleOSDHide(element) {
    if (osdHideTimeout) clearTimeout(osdHideTimeout);
    osdHideTimeout = setTimeout(() => {
      element.classList.remove('active');
    }, 700);
  }

  function hideAllOSD() {
    if (osdBrightnessEl) osdBrightnessEl.classList.remove('active');
    if (osdVolumeEl) osdVolumeEl.classList.remove('active');
    if (osdSeekEl) osdSeekEl.classList.remove('active');
  }

  /**
   * Screen Lock (Kid Lock) Toggle
   */
  function setLocked(locked) {
    isLocked = Boolean(locked);
    const lockOverlay = document.getElementById('lock-screen-overlay');
    const playerControls = document.getElementById('player-controls');

    if (lockOverlay) {
      lockOverlay.classList.toggle('active', isLocked);
    }
    if (playerControls) {
      playerControls.classList.toggle('locked', isLocked);
    }
    return isLocked;
  }

  function getIsLocked() {
    return isLocked;
  }

  return {
    init,
    setBrightness,
    setLocked,
    getIsLocked
  };
})();
