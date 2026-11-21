/**
 * QB Player - MX Player Folders & Video Library Browser
 * Queries device storage folders via AndroidBridge (MediaStore) and renders
 * the classic MX Player folder navigation, video list, search, and instant playback.
 */

const MediaBrowser = (function() {
  let browserEl = null;
  let foldersViewEl = null;
  let videosViewEl = null;
  let recentViewEl = null;
  let currentFolderVideos = [];
  let allVideos = [];
  let currentTab = 'folders'; // 'folders', 'all', 'recent'

  function init() {
    browserEl = document.getElementById('media-browser');
    foldersViewEl = document.getElementById('browser-folders-list');
    videosViewEl = document.getElementById('browser-all-videos-list');
    recentViewEl = document.getElementById('browser-recent-list');

    setupEvents();
    loadFolders();

    // Hook native back button
    window.onNativeBackPressed = handleBack;
  }

  function setupEvents() {
    // Tab switching
    document.querySelectorAll('.browser-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        document.querySelectorAll('.browser-tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        currentTab = tab.dataset.tab;
        switchTab(currentTab);
      });
    });

    // Back to folders button
    const btnBackToFolders = document.getElementById('btn-browser-back-folders');
    if (btnBackToFolders) {
      btnBackToFolders.addEventListener('click', () => {
        showFoldersView();
      });
    }

    // Search bar filtering
    const searchInput = document.getElementById('browser-search-input');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        const query = e.target.value.toLowerCase().trim();
        filterItems(query);
      });
    }

    // Refresh / Rescan button
    const btnRefresh = document.getElementById('btn-browser-refresh');
    if (btnRefresh) {
      btnRefresh.addEventListener('click', () => {
        if (window.AndroidBridge) {
          window.AndroidBridge.requestPermission();
        }
        loadFolders();
      });
    }

    // Open file fallback button
    const btnOpenFile = document.getElementById('btn-browser-open-file');
    if (btnOpenFile) {
      btnOpenFile.addEventListener('click', () => {
        document.getElementById('input-video-file').click();
      });
    }
  }

  function switchTab(tab) {
    document.getElementById('view-folders-section').style.display = tab === 'folders' ? 'block' : 'none';
    document.getElementById('view-videos-section').style.display = tab === 'all' ? 'block' : 'none';
    document.getElementById('view-recent-section').style.display = tab === 'recent' ? 'block' : 'none';

    if (tab === 'folders') {
      loadFolders();
    } else if (tab === 'all') {
      loadAllVideos();
    } else if (tab === 'recent') {
      loadRecentVideos();
    }
  }

  function showFoldersView() {
    document.getElementById('view-folders-container').style.display = 'block';
    document.getElementById('view-folder-videos-container').style.display = 'none';
  }

  function showFolderVideosView(folderName) {
    document.getElementById('view-folders-container').style.display = 'none';
    document.getElementById('view-folder-videos-container').style.display = 'block';
    const folderTitle = document.getElementById('browser-current-folder-title');
    if (folderTitle) folderTitle.textContent = folderName;
  }

  /**
   * Load Folders from Android MediaStore
   */
  function loadFolders() {
    showFoldersView();
    if (!foldersViewEl) foldersViewEl = document.getElementById('browser-folders-list');
    if (!foldersViewEl) return;

    if (window.AndroidBridge && window.AndroidBridge.getMediaFolders) {
      try {
        const jsonStr = window.AndroidBridge.getMediaFolders();
        const folders = JSON.parse(jsonStr || '[]');

        if (!folders || folders.length === 0) {
          foldersViewEl.innerHTML = `
            <div class="empty-state">
              <div class="empty-icon">📁</div>
              <h3>No Video Folders Found</h3>
              <p>Download or transfer movies to your device, or grant storage permission.</p>
              <div style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; margin-top: 14px;">
                <button id="btn-empty-permission" class="btn-primary">
                  Grant Storage Permission
                </button>
                <button id="btn-empty-open-file" class="btn-secondary">
                  Open Video File
                </button>
              </div>
            </div>
          `;
          const btnPerm = document.getElementById('btn-empty-permission');
          if (btnPerm) {
            btnPerm.onclick = () => {
              if (window.AndroidBridge && window.AndroidBridge.requestPermission) {
                window.AndroidBridge.requestPermission();
              }
            };
          }
          const btnOpen = document.getElementById('btn-empty-open-file');
          if (btnOpen) {
            btnOpen.onclick = () => {
              const fileInput = document.getElementById('input-video-file');
              if (fileInput) fileInput.click();
            };
          }
          return;
        }

        renderFolders(folders);
        return;
      } catch (e) {
        console.error('Error loading Android folders:', e);
      }
    }

    // Web / PC Fallback View
    renderWebFallback();
  }

  function renderFolders(folders) {
    foldersViewEl.innerHTML = '';
    folders.forEach(folder => {
      const card = document.createElement('div');
      card.className = 'folder-card';
      card.innerHTML = `
        <div class="folder-icon-wrap">
          <svg viewBox="0 0 24 24" width="36" height="36" fill="var(--accent-cyan)"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>
        </div>
        <div class="folder-details">
          <div class="folder-name">${escapeHtml(folder.name)}</div>
          <div class="folder-sub">${folder.count} ${folder.count === 1 ? 'video' : 'videos'}</div>
        </div>
        <div class="folder-badge">${folder.count}</div>
      `;

      card.addEventListener('click', () => {
        openFolder(folder.id, folder.name);
      });

      foldersViewEl.appendChild(card);
    });
  }

  /**
   * Open a specific folder and display its videos
   */
  function openFolder(bucketId, folderName) {
    if (!window.AndroidBridge) return;
    const fetchVideos = window.AndroidBridge.getFolderVideos || window.AndroidBridge.getVideosInFolder;
    if (!fetchVideos) return;
    try {
      const jsonStr = fetchVideos.call(window.AndroidBridge, String(bucketId));
      currentFolderVideos = JSON.parse(jsonStr || '[]');
      showFolderVideosView(`${folderName} (${currentFolderVideos.length})`);
      renderVideoList(document.getElementById('browser-folder-videos-list'), currentFolderVideos);
    } catch (e) {
      console.error('Error opening folder:', e);
    }
  }

  /**
   * Load All Videos Tab
   */
  function loadAllVideos() {
    const listEl = document.getElementById('browser-all-videos-list');
    if (!listEl) return;

    if (window.AndroidBridge && window.AndroidBridge.getAllVideos) {
      try {
        const jsonStr = window.AndroidBridge.getAllVideos();
        allVideos = JSON.parse(jsonStr || '[]');
        if (allVideos.length === 0) {
          listEl.innerHTML = '<div class="empty-state"><p>No videos found on device.</p></div>';
          return;
        }
        renderVideoList(listEl, allVideos);
        return;
      } catch (e) {}
    }

    listEl.innerHTML = '<div class="empty-state"><p>Available on Android devices.</p></div>';
  }

  /**
   * Load Recent Watch History Tab
   */
  function loadRecentVideos() {
    if (!recentViewEl) return;
    const history = StorageManager.getRecentHistory();
    if (!history || history.length === 0) {
      recentViewEl.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">⏱️</div>
          <h3>No Watch History</h3>
          <p>Videos you watch will appear here with your saved position.</p>
        </div>
      `;
      return;
    }

    recentViewEl.innerHTML = '';
    history.forEach(item => {
      const card = document.createElement('div');
      card.className = 'video-item-card';
      card.innerHTML = `
        <div class="video-thumb-placeholder">
          <svg viewBox="0 0 24 24" width="28" height="28" fill="#fff"><path d="M8 5v14l11-7z"/></svg>
        </div>
        <div class="video-info">
          <div class="video-item-title">${escapeHtml(item.title)}</div>
          <div class="video-item-meta">
            <span>Last watched at: ${PlayerCore.formatTime(item.timestamp)}</span>
            <span>${item.progressPct}%</span>
          </div>
          <div class="playlist-progress-bar">
            <div class="playlist-progress-fill" style="width: ${item.progressPct}%;"></div>
          </div>
        </div>
      `;
      recentViewEl.appendChild(card);
    });
  }

  function renderVideoList(container, videos) {
    container.innerHTML = '';
    videos.forEach(video => {
      const card = document.createElement('div');
      card.className = 'video-item-card';

      // Check saved progress
      const saved = StorageManager.getPlaybackPosition(StorageManager.generateFileKey(video.path || video.title));
      const progressPct = saved && saved.duration ? Math.round((saved.timestamp / saved.duration) * 100) : 0;

      card.innerHTML = `
        <div class="video-thumb-placeholder">
          <svg viewBox="0 0 24 24" width="28" height="28" fill="#fff"><path d="M8 5v14l11-7z"/></svg>
          <div class="video-duration-badge">${video.durationStr || '00:00'}</div>
        </div>
        <div class="video-info">
          <div class="video-item-title">${escapeHtml(video.title)}</div>
          <div class="video-item-meta">
            <span>${video.resolution || 'HD'} • ${video.sizeStr || ''}</span>
            ${progressPct > 0 ? `<span style="color: var(--accent-cyan); font-weight: 600;">${progressPct}% watched</span>` : ''}
          </div>
          ${progressPct > 0 ? `
            <div class="playlist-progress-bar">
              <div class="playlist-progress-fill" style="width: ${progressPct}%;"></div>
            </div>
          ` : ''}
        </div>
      `;

      card.addEventListener('click', () => {
        playSelectedVideo(video);
      });

      container.appendChild(card);
    });
  }

  /**
   * Play video immediately in full-screen player
   */
  function playSelectedVideo(video) {
    // Hide media browser
    if (browserEl) browserEl.classList.add('hidden');
    document.documentElement.classList.add('native-exoplayer');
    document.body.classList.add('native-exoplayer');

    // Auto rotate: If video width > height, request landscape on Android
    if (window.AndroidBridge && video.width && video.height) {
      if (video.width >= video.height) {
        window.AndroidBridge.setOrientation('landscape');
      }
    }

    // Associate mediaId for native dual audio and subtitle extraction
    if (video.id) {
      PlayerCore.setCurrentMediaId(video.id);
    } else {
      PlayerCore.setCurrentMediaId(null);
    }

    // Reset subtitles for new video
    if (window.SubtitlesManager && window.SubtitlesManager.clearTracks) {
      window.SubtitlesManager.clearTracks();
    }

    // Load source in player
    PlayerCore.loadSource(video.url || video.path, video.title);
    PlayerCore.play();

    // Update Topbar
    const titleEl = document.getElementById('topbar-video-title');
    if (titleEl) titleEl.textContent = video.title;
    const metaEl = document.getElementById('topbar-video-meta');
    if (metaEl) metaEl.textContent = `${video.resolution || 'HD'} • Ad-Free`;
  }

  /**
   * Return from player back to folder browser
   */
  function returnToBrowser() {
    PlayerCore.pause();
    if (PlayerCore.stopNativeTimeUpdates) {
      PlayerCore.stopNativeTimeUpdates();
    }
    if (window.AndroidBridge && window.AndroidBridge.stopAndHidePlayer) {
      window.AndroidBridge.stopAndHidePlayer();
    }
    if (window.AndroidBridge && window.AndroidBridge.setOrientation) {
      window.AndroidBridge.setOrientation('sensor');
    }
    document.documentElement.classList.remove('native-exoplayer');
    document.body.classList.remove('native-exoplayer');
    if (browserEl) browserEl.classList.remove('hidden');
    loadFolders();
  }

  function handleBack() {
    // If playing video, back button returns to folder browser
    if (browserEl && browserEl.classList.contains('hidden')) {
      returnToBrowser();
      return true;
    }
    // If viewing videos inside a folder, back button returns to folders list
    const folderVideosContainer = document.getElementById('view-folder-videos-container');
    if (folderVideosContainer && folderVideosContainer.style.display === 'block') {
      showFoldersView();
      return true;
    }
    return false; // Exit app
  }

  function filterItems(query) {
    if (!query) {
      if (currentTab === 'folders') loadFolders();
      else if (currentTab === 'all') renderVideoList(document.getElementById('browser-all-videos-list'), allVideos);
      return;
    }

    if (currentTab === 'folders') {
      const cards = foldersViewEl.querySelectorAll('.folder-card');
      cards.forEach(card => {
        const text = card.textContent.toLowerCase();
        card.style.display = text.includes(query) ? 'flex' : 'none';
      });
    } else if (currentTab === 'all') {
      const filtered = allVideos.filter(v => v.title.toLowerCase().includes(query));
      renderVideoList(document.getElementById('browser-all-videos-list'), filtered);
    }
  }

  function renderWebFallback() {
    foldersViewEl.innerHTML = `
      <div style="padding: 20px 12px; text-align: center;">
        <div style="font-size: 2.2rem; margin-bottom: 8px;">🎬</div>
        <h3 style="color: #fff; font-size: 1.15rem; margin-bottom: 8px;">QB Player Media Library</h3>
        <p style="color: var(--text-secondary); font-size: 0.9rem; max-width: 440px; margin: 0 auto 20px auto;">
          Open your video files or folders to start playing. On Android and Oppo Pad, your device video folders (Download, Movies, Camera) are scanned automatically!
        </p>
        <div style="display: flex; gap: 12px; justify-content: center; flex-wrap: wrap;">
          <button class="btn-primary" onclick="document.getElementById('input-video-file').click()">
            Open Video File(s)
          </button>
          <button class="btn-secondary" onclick="document.getElementById('input-folder').click()">
            Open Folder
          </button>
        </div>
      </div>
    `;
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>'"]/g, tag => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[tag] || tag));
  }

  return {
    init,
    loadFolders,
    openFolder,
    playSelectedVideo,
    returnToBrowser,
    handleBack
  };
})();

window.MediaBrowser = MediaBrowser;
