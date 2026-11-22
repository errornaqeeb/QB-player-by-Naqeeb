/**
 * QB Player - Storage & Playback State Manager ("Play Where Left")
 * Handles automatic resume positions, video history, and user settings.
 */

const StorageManager = (function() {
  const STORAGE_PREFIX = 'qb_player_';
  const HISTORY_KEY = `${STORAGE_PREFIX}recent_history`;
  const SETTINGS_KEY = `${STORAGE_PREFIX}user_settings`;

  // Default User Settings
  const defaultSettings = {
    volume: 0.2, // Default to 20% on fresh app launch
    audioBoost: 1.0, // 1.0 = 100%, 2.0 = 200%
    brightness: 1.0,
    playbackSpeed: 1.0,
    aspectRatio: 'aspect-fit',
    subtitleSize: 26,
    subtitleColor: '#ffff00',
    subtitleBg: 'shadow',
    subtitleOffset: 0, // In seconds (e.g. +0.2 or -0.5)
    autoResume: true,
    resumeThresholdSec: 5, // Only resume if watched more than 5s
    resumeEndSec: 15 // Don't resume if within last 15s
  };

  /**
   * Generates a unique key for a video file or stream URL
   */
  function generateFileKey(fileOrUrl) {
    if (typeof fileOrUrl === 'string') {
      return `url_${encodeURIComponent(fileOrUrl)}`;
    }
    if (fileOrUrl && fileOrUrl.name) {
      return `file_${fileOrUrl.name}_${fileOrUrl.size}_${fileOrUrl.lastModified}`;
    }
    return `unknown_${Date.now()}`;
  }

  /**
   * Save playback position and metadata for a video
   * Accepts both (fileKey, metadataObj) and (fileKey, currentTime, duration, title)
   */
  function savePlaybackPosition(fileKey, metadataOrCurrentTime, duration, title) {
    if (!fileKey) return;
    try {
      let currentTime = 0;
      let dur = 0;
      let vidTitle = 'Untitled Video';

      if (typeof metadataOrCurrentTime === 'object' && metadataOrCurrentTime !== null) {
        currentTime = metadataOrCurrentTime.currentTime || metadataOrCurrentTime.timestamp || 0;
        dur = metadataOrCurrentTime.duration || 0;
        vidTitle = metadataOrCurrentTime.title || 'Untitled Video';
      } else if (typeof metadataOrCurrentTime === 'number') {
        currentTime = metadataOrCurrentTime;
        dur = duration || 0;
        vidTitle = title || 'Untitled Video';
      }

      if (currentTime <= 0) return;

      const payload = {
        title: vidTitle,
        timestamp: currentTime,
        duration: dur,
        updatedAt: Date.now(),
        completed: dur > 0 ? (currentTime / dur > 0.95) : false
      };

      // Save under primary key
      localStorage.setItem(`${STORAGE_PREFIX}pos_${fileKey}`, JSON.stringify(payload));

      // Also save under clean title key for robust cross-view lookup
      if (vidTitle && vidTitle !== 'Untitled Video') {
        const cleanTitleKey = encodeURIComponent(vidTitle.trim().toLowerCase());
        localStorage.setItem(`${STORAGE_PREFIX}pos_${cleanTitleKey}`, JSON.stringify(payload));
      }

      // Also persist to native Android SharedPreferences as robust backup
      if (window.AndroidBridge && window.AndroidBridge.savePosition) {
        window.AndroidBridge.savePosition(fileKey, Math.round(currentTime * 1000));
        if (vidTitle && vidTitle !== 'Untitled Video') {
          window.AndroidBridge.savePosition(vidTitle, Math.round(currentTime * 1000));
        }
      }

      updateRecentHistory(fileKey, payload);
    } catch (e) {
      console.warn('[Storage] Failed to save playback position:', e);
    }
  }

  /**
   * Get saved playback position
   */
  function getPlaybackPosition(fileKey, title) {
    if (!fileKey && !title) return null;
    try {
      if (fileKey) {
        const data = localStorage.getItem(`${STORAGE_PREFIX}pos_${fileKey}`);
        if (data) return JSON.parse(data);
      }
      if (title) {
        const cleanTitleKey = encodeURIComponent(title.trim().toLowerCase());
        const data = localStorage.getItem(`${STORAGE_PREFIX}pos_${cleanTitleKey}`);
        if (data) return JSON.parse(data);
      }
      // Native Android SharedPreferences fallback
      if (window.AndroidBridge && window.AndroidBridge.getSavedPosition) {
        const posMs = window.AndroidBridge.getSavedPosition(fileKey || title);
        if (posMs > 5000) {
          return {
            title: title || 'Video',
            timestamp: posMs / 1000.0,
            duration: 0,
            updatedAt: Date.now(),
            completed: false
          };
        }
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  /**
   * Clear saved playback position for a video
   */
  function clearPlaybackPosition(fileKey) {
    if (!fileKey) return;
    try {
      localStorage.removeItem(`${STORAGE_PREFIX}pos_${fileKey}`);
    } catch (e) {}
  }

  /**
   * Maintain the recent history list
   */
  function updateRecentHistory(fileKey, info) {
    try {
      let history = getRecentHistory();
      // Remove previous entry with same key
      history = history.filter(item => item.fileKey !== fileKey);
      // Prepend newest
      history.unshift({
        fileKey: fileKey,
        title: info.title,
        timestamp: info.timestamp,
        duration: info.duration,
        progressPct: info.duration ? Math.min(100, Math.round((info.timestamp / info.duration) * 100)) : 0,
        updatedAt: info.updatedAt
      });
      // Keep only top 30
      if (history.length > 30) history = history.slice(0, 30);
      localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
    } catch (e) {}
  }

  function getRecentHistory() {
    try {
      const raw = localStorage.getItem(HISTORY_KEY);
      return raw ? JSON.parse(raw) : [];
    } catch (e) {
      return [];
    }
  }

  /**
   * Settings management
   */
  function loadSettings() {
    try {
      const raw = localStorage.getItem(SETTINGS_KEY);
      return raw ? { ...defaultSettings, ...JSON.parse(raw) } : { ...defaultSettings };
    } catch (e) {
      return { ...defaultSettings };
    }
  }

  function saveSettings(settings) {
    try {
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    } catch (e) {}
  }

  return {
    generateFileKey,
    savePlaybackPosition,
    getPlaybackPosition,
    clearPlaybackPosition,
    getRecentHistory,
    loadSettings,
    saveSettings
  };
})();

window.StorageManager = StorageManager;
