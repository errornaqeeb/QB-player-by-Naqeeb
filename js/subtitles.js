/**
 * QB Player - Subtitles & Sync Manager
 * Supports SRT, WebVTT, and ASS/SSA formats with real-time sync offset and visual styling.
 */

const SubtitlesManager = (function() {
  let cues = [];
  let currentCue = null;
  let syncOffset = 0; // offset in seconds (e.g. +0.250s or -0.500s)
  let isEnabled = true;
  let tracks = []; // list of available subtitle tracks
  let activeTrackIndex = -1;

  // UI elements
  let subContainerEl = null;
  let subTextEl = null;

  // Subtitle styling configuration
  const styleConfig = {
    fontSize: 26,
    color: '#ffff00',
    background: 'shadow', // 'shadow', 'box', 'solid'
    bottomOffset: 80
  };

  function init(containerEl, textEl) {
    subContainerEl = containerEl;
    subTextEl = textEl;
    applyStyles();
  }

  /**
   * Apply visual styles to subtitle display element
   */
  function applyStyles() {
    if (!subTextEl || !subContainerEl) return;
    subTextEl.style.fontSize = `${styleConfig.fontSize}px`;
    subTextEl.style.color = styleConfig.color;
    subContainerEl.style.bottom = `${styleConfig.bottomOffset}px`;

    if (styleConfig.background === 'box') {
      subTextEl.style.backgroundColor = 'rgba(0, 0, 0, 0.65)';
      subTextEl.style.textShadow = 'none';
      subTextEl.style.padding = '4px 16px';
    } else if (styleConfig.background === 'solid') {
      subTextEl.style.backgroundColor = '#000000';
      subTextEl.style.textShadow = 'none';
      subTextEl.style.padding = '6px 18px';
    } else { // 'shadow'
      subTextEl.style.backgroundColor = 'transparent';
      subTextEl.style.padding = '2px 8px';
      subTextEl.style.textShadow = `
        -2px -2px 0 #000,
         2px -2px 0 #000,
        -2px  2px 0 #000,
         2px  2px 0 #000,
         0px  3px 5px rgba(0, 0, 0, 0.9)
      `;
    }
  }

  /**
   * Set Subtitle Font Size
   */
  function setFontSize(sizePx) {
    styleConfig.fontSize = Math.max(14, Math.min(52, parseInt(sizePx, 10) || 26));
    applyStyles();
  }

  /**
   * Set Subtitle Text Color
   */
  function setColor(hex) {
    styleConfig.color = hex;
    applyStyles();
  }

  /**
   * Set Background style
   */
  function setBackgroundStyle(style) {
    styleConfig.background = style;
    applyStyles();
  }

  /**
   * Adjust Subtitle Sync Delay Offset (in seconds)
   * e.g. delta = +0.1 adds 100ms delay, delta = -0.1 displays 100ms earlier
   */
  function adjustSyncOffset(deltaSeconds) {
    syncOffset = Math.round((syncOffset + deltaSeconds) * 100) / 100;
    return syncOffset;
  }

  function setSyncOffset(seconds) {
    syncOffset = Math.round(parseFloat(seconds) * 100) / 100 || 0;
    return syncOffset;
  }

  function getSyncOffset() {
    return syncOffset;
  }

  /**
   * Toggle Subtitles On/Off
   */
  function toggleEnabled() {
    isEnabled = !isEnabled;
    if (!isEnabled && subTextEl) {
      subTextEl.textContent = '';
      subTextEl.style.display = 'none';
    } else if (isEnabled && subTextEl) {
      subTextEl.style.display = 'inline-block';
    }
    return isEnabled;
  }

  function setEnabled(enabled) {
    isEnabled = Boolean(enabled);
    if (!isEnabled && subTextEl) {
      subTextEl.textContent = '';
      subTextEl.style.display = 'none';
    } else if (isEnabled && subTextEl) {
      subTextEl.style.display = 'inline-block';
    }
  }

  /**
   * Parse timestamp string (00:01:23,456 or 00:01:23.456 or 1:23.45) into seconds
   */
  function parseTimestamp(timeStr) {
    if (!timeStr) return 0;
    const parts = timeStr.trim().replace(',', '.').split(':');
    let seconds = 0;
    if (parts.length === 3) {
      seconds = parseFloat(parts[0]) * 3600 + parseFloat(parts[1]) * 60 + parseFloat(parts[2]);
    } else if (parts.length === 2) {
      seconds = parseFloat(parts[0]) * 60 + parseFloat(parts[1]);
    } else {
      seconds = parseFloat(parts[0]) || 0;
    }
    return isNaN(seconds) ? 0 : seconds;
  }

  /**
   * Parse SRT Subtitle format
   */
  function parseSRT(text) {
    const normalized = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    const blocks = normalized.split(/\n\n+/);
    const parsed = [];

    for (const block of blocks) {
      const lines = block.trim().split('\n');
      if (lines.length < 2) continue;

      let timeLineIndex = 0;
      if (/^\d+$/.test(lines[0].trim())) {
        timeLineIndex = 1;
      }
      if (!lines[timeLineIndex] || !lines[timeLineIndex].includes('-->')) continue;

      const [startStr, endStr] = lines[timeLineIndex].split('-->');
      const start = parseTimestamp(startStr);
      const end = parseTimestamp(endStr);
      const textLines = lines.slice(timeLineIndex + 1).join('\n')
        .replace(/<[^>]+>/g, '') // remove HTML tags if any
        .trim();

      if (textLines && end > start) {
        parsed.push({ start, end, text: textLines });
      }
    }
    return parsed.sort((a, b) => a.start - b.start);
  }

  /**
   * Parse WebVTT Subtitle format
   */
  function parseVTT(text) {
    const normalized = text.replace(/^WEBVTT[^\n]*\n+/i, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    return parseSRT(normalized);
  }

  /**
   * Parse SSA / ASS SubStation Alpha format
   */
  function parseASS(text) {
    const lines = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
    const parsed = [];
    let inEvents = false;
    let formatFields = [];

    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith('[Events]')) {
        inEvents = true;
        continue;
      }
      if (inEvents) {
        if (trimmed.startsWith('Format:')) {
          formatFields = trimmed.substring(7).split(',').map(s => s.trim().toLowerCase());
        } else if (trimmed.startsWith('Dialogue:')) {
          const values = trimmed.substring(9).split(',');
          // If text contains commas, group rest of values
          const startIndex = formatFields.indexOf('start');
          const endIndex = formatFields.indexOf('end');
          const textIndex = formatFields.indexOf('text');

          if (startIndex >= 0 && endIndex >= 0 && textIndex >= 0) {
            const start = parseTimestamp(values[startIndex]);
            const end = parseTimestamp(values[endIndex]);
            const rawText = values.slice(textIndex).join(',');
            // Clean ASS override tags like {\an8}, \N
            const cleanText = rawText.replace(/\{[^}]+\}/g, '').replace(/\\N/gi, '\n').trim();
            if (cleanText && end > start) {
              parsed.push({ start, end, text: cleanText });
            }
          }
        }
      }
    }
    return parsed.sort((a, b) => a.start - b.start);
  }

  /**
   * Load subtitle file directly from user File object
   */
  async function loadFromFile(file) {
    try {
      const text = await file.text();
      const ext = file.name.split('.').pop().toLowerCase();
      let parsedCues = [];

      if (ext === 'srt') {
        parsedCues = parseSRT(text);
      } else if (ext === 'vtt') {
        parsedVTT(text);
        parsedCues = parseVTT(text);
      } else if (ext === 'ass' || ext === 'ssa') {
        parsedCues = parseASS(text);
      } else {
        // Fallback: try SRT
        parsedCues = parseSRT(text);
      }

      if (parsedCues.length > 0) {
        cues = parsedCues;
        const trackName = file.name.replace(/\.[^/.]+$/, "");
        tracks.push({ name: trackName, cues: parsedCues, custom: true });
        activeTrackIndex = tracks.length - 1;
        setEnabled(true);
        return { success: true, count: parsedCues.length, trackName };
      } else {
        return { success: false, error: 'No subtitles found or unsupported format' };
      }
    } catch (e) {
      console.error('[Subtitles] Failed to parse subtitle file:', e);
      return { success: false, error: e.message };
    }
  }

  /**
   * Switch between available subtitle tracks
   */
  function selectTrack(index) {
    if (index === -1) {
      activeTrackIndex = -1;
      cues = [];
      if (subTextEl) subTextEl.textContent = '';
      return;
    }
    if (tracks[index]) {
      activeTrackIndex = index;
      cues = tracks[index].cues;
      setEnabled(true);
    }
  }

  function getTracks() {
    return tracks.map((t, idx) => ({
      index: idx,
      name: t.name,
      isActive: idx === activeTrackIndex
    }));
  }

  function clearTracks() {
    tracks = [];
    cues = [];
    activeTrackIndex = -1;
    currentCue = null;
    if (subTextEl) subTextEl.textContent = '';
  }

  /**
   * Update Subtitles based on current video playback time
   * Called on every 'timeupdate' event
   */
  function update(currentTime) {
    if (!isEnabled || cues.length === 0 || !subTextEl) {
      if (subTextEl && subTextEl.textContent !== '') subTextEl.textContent = '';
      return;
    }

    // Apply user sync offset (subtitles shown earlier or later)
    const effectiveTime = currentTime - syncOffset;

    // Fast check if still in current cue
    if (currentCue && effectiveTime >= currentCue.start && effectiveTime <= currentCue.end) {
      return;
    }

    // Binary search for matching cue
    let low = 0;
    let high = cues.length - 1;
    let match = null;

    while (low <= high) {
      const mid = Math.floor((low + high) / 2);
      const cue = cues[mid];
      if (effectiveTime >= cue.start && effectiveTime <= cue.end) {
        match = cue;
        break;
      } else if (effectiveTime < cue.start) {
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }

    currentCue = match;
    if (match) {
      subTextEl.textContent = match.text;
      subTextEl.style.display = 'inline-block';
    } else {
      subTextEl.textContent = '';
      subTextEl.style.display = 'none';
    }
  }

  return {
    init,
    loadFromFile,
    update,
    adjustSyncOffset,
    setSyncOffset,
    getSyncOffset,
    toggleEnabled,
    setEnabled,
    setFontSize,
    setColor,
    setBackgroundStyle,
    selectTrack,
    getTracks,
    clearTracks,
    styleConfig
  };
})();
