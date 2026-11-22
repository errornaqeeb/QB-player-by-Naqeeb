/**
 * QB Player - Playlist & File Manager
 * Handles multi-video queue, folder scanning, smart drag-and-drop detection, and playback loops.
 */

const PlaylistManager = (function() {
  let queue = [];
  let currentIndex = -1;
  let loopMode = 'off'; // 'off', 'one', 'all'
  let isShuffled = false;
  let drawerListEl = null;

  function init(listElement) {
    drawerListEl = listElement;
    setupDragAndDrop();
  }

  /**
   * Smart Drag and Drop handler:
   * Detects whether dropped item is a video, subtitle (.srt/.vtt/.ass), or audio (.mp3/.aac/etc.)
   */
  function setupDragAndDrop() {
    const container = document.getElementById('player-container');
    if (!container) return;

    ['dragenter', 'dragover'].forEach(eventName => {
      container.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        container.style.outline = '2px dashed var(--accent-cyan)';
      }, false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
      container.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        container.style.outline = 'none';
      }, false);
    });

    container.addEventListener('drop', async (e) => {
      const files = Array.from(e.dataTransfer.files);
      if (!files || files.length === 0) return;

      const videoFiles = [];
      const subtitleFiles = [];
      const audioFiles = [];

      files.forEach(file => {
        const ext = file.name.split('.').pop().toLowerCase();
        if (['mp4', 'mkv', 'webm', 'avi', 'mov', 'm4v', 'ts', 'ogv', 'flv'].includes(ext) || file.type.startsWith('video/')) {
          videoFiles.push(file);
        } else if (['srt', 'vtt', 'ass', 'ssa'].includes(ext)) {
          subtitleFiles.push(file);
        } else if (['mp3', 'aac', 'm4a', 'wav', 'ogg', 'flac'].includes(ext) || file.type.startsWith('audio/')) {
          audioFiles.push(file);
        }
      });

      // If subtitles dropped, import into SubtitlesManager directly
      if (subtitleFiles.length > 0) {
        for (const subFile of subtitleFiles) {
          const res = await SubtitlesManager.loadFromFile(subFile);
          if (res.success) {
            window.dispatchEvent(new CustomEvent('qb:toast', {
              detail: `Subtitles Loaded: ${subFile.name}`
            }));
          }
        }
      }

      // If audio file dropped, import as external audio dub
      if (audioFiles.length > 0) {
        const audioRes = PlayerCore.loadExternalAudioFile(audioFiles[0]);
        if (audioRes.success) {
          window.dispatchEvent(new CustomEvent('qb:toast', {
            detail: `External Audio Dub Loaded: ${audioFiles[0].name}`
          }));
        }
      }

      // If video files dropped, add to queue and play first
      if (videoFiles.length > 0) {
        addFiles(videoFiles, true);
      }
    });
  }

  /**
   * Add files to playlist
   */
  function addFiles(files, playImmediately = false) {
    const startIndex = queue.length;
    files.forEach(file => {
      queue.push({
        title: file.name,
        source: file,
        type: 'file',
        fileKey: StorageManager.generateFileKey(file)
      });
    });

    render();

    if (playImmediately && queue.length > startIndex) {
      playIndex(startIndex);
    }
  }

  /**
   * Add URL stream to playlist
   */
  function addUrl(url, title = '') {
    const videoTitle = title || url.split('/').pop().split('?')[0] || 'Web Stream';
    queue.push({
      title: videoTitle,
      source: url,
      type: 'url',
      fileKey: StorageManager.generateFileKey(url)
    });
    render();
    playIndex(queue.length - 1);
  }

  /**
   * Set entire folder or video list into queue with active index
   */
  function setQueue(items, activeIndex = 0) {
    if (!items || items.length === 0) return;
    queue = items.map(item => ({
      id: item.id,
      title: item.title,
      source: item.url || item.path || String(item.id),
      path: item.path,
      url: item.url,
      width: item.width,
      height: item.height,
      resolution: item.resolution,
      sizeStr: item.sizeStr,
      durationStr: item.durationStr,
      type: (item.source instanceof File) ? 'file' : 'url',
      fileKey: StorageManager.generateFileKey(item.path || item.title || item.id)
    }));
    currentIndex = Math.max(0, Math.min(queue.length - 1, activeIndex));
    render();
  }

  /**
   * Play video at specific playlist index
   */
  function playIndex(index) {
    if (index < 0 || index >= queue.length) return;
    currentIndex = index;
    const item = queue[currentIndex];

    // Hide welcome landing screen
    const welcome = document.getElementById('welcome-screen');
    if (welcome) welcome.classList.add('hidden');

    if (window.MediaBrowser && window.MediaBrowser.playVideoItem) {
      window.MediaBrowser.playVideoItem(item, false);
    } else {
      if (item.id) {
        PlayerCore.setCurrentMediaId(item.id);
      }
      PlayerCore.loadSource(item.source || item.path, item.title);
      PlayerCore.play();
      const titleEl = document.getElementById('topbar-video-title');
      if (titleEl) titleEl.textContent = item.title;
    }

    render();

    // Trigger video load and check for resume
    window.dispatchEvent(new CustomEvent('qb:video-opened', { detail: item }));
  }

  function playNext() {
    if (queue.length === 0) return;
    if (loopMode === 'one') {
      PlayerCore.seek(0);
      PlayerCore.play();
      return;
    }

    if (currentIndex + 1 < queue.length) {
      playIndex(currentIndex + 1);
    } else if (loopMode === 'all') {
      playIndex(0);
    }
  }

  function playPrev() {
    if (queue.length === 0) return;
    if (currentIndex > 0) {
      playIndex(currentIndex - 1);
    } else {
      PlayerCore.seek(0);
      PlayerCore.play();
    }
  }

  function toggleLoopMode() {
    if (loopMode === 'off') loopMode = 'all';
    else if (loopMode === 'all') loopMode = 'one';
    else loopMode = 'off';
    return loopMode;
  }

  function getLoopMode() {
    return loopMode;
  }

  /**
   * Render Playlist UI inside the drawer
   */
  function render() {
    if (!drawerListEl) return;
    drawerListEl.innerHTML = '';

    if (queue.length === 0) {
      // Show Recent History if queue is empty
      const history = StorageManager.getRecentHistory();
      if (history.length === 0) {
        drawerListEl.innerHTML = `
          <div style="text-align: center; color: var(--text-muted); padding: 40px 16px;">
            <p>Playlist is empty</p>
            <p style="font-size: 0.8rem; margin-top: 6px;">Open a file or drop videos to start playing.</p>
          </div>
        `;
        return;
      }

      const historyHeader = document.createElement('div');
      historyHeader.style.cssText = 'font-size: 0.8rem; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 1px; margin-bottom: 8px; font-weight: 700;';
      historyHeader.textContent = 'Recent History';
      drawerListEl.appendChild(historyHeader);

      history.forEach(item => {
        const div = document.createElement('div');
        div.className = 'playlist-item';
        div.innerHTML = `
          <div class="playlist-item-title">${item.title}</div>
          <div class="playlist-item-meta">
            <span>Last at: ${PlayerCore.formatTime(item.timestamp)}</span>
            <span>${item.progressPct}%</span>
          </div>
          <div class="playlist-progress-bar">
            <div class="playlist-progress-fill" style="width: ${item.progressPct}%;"></div>
          </div>
        `;
        drawerListEl.appendChild(div);
      });
      return;
    }

    queue.forEach((item, idx) => {
      const div = document.createElement('div');
      div.className = `playlist-item ${idx === currentIndex ? 'active' : ''}`;
      const saved = StorageManager.getPlaybackPosition(item.fileKey);
      const progressPct = saved && saved.duration ? Math.round((saved.timestamp / saved.duration) * 100) : 0;

      div.innerHTML = `
        <div class="playlist-item-title">${idx + 1}. ${item.title}</div>
        <div class="playlist-item-meta">
          <span>${item.type === 'file' ? 'Local Video' : 'Online Stream'}</span>
          <span>${progressPct > 0 ? progressPct + '%' : ''}</span>
        </div>
        ${progressPct > 0 ? `
          <div class="playlist-progress-bar">
            <div class="playlist-progress-fill" style="width: ${progressPct}%;"></div>
          </div>
        ` : ''}
      `;

      div.addEventListener('click', () => {
        playIndex(idx);
      });

      drawerListEl.appendChild(div);
    });
  }

  function getQueue() {
    return queue;
  }

  function getCurrentIndex() {
    return currentIndex;
  }

  return {
    init,
    setQueue,
    addFiles,
    addUrl,
    playIndex,
    playNext,
    playPrev,
    toggleLoopMode,
    getLoopMode,
    render,
    getQueue,
    getCurrentIndex
  };
})();

window.PlaylistManager = PlaylistManager;
