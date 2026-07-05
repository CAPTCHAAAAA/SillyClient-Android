(function(){
  if (document.getElementById('sc-navbar-host')) return;

  var host = document.createElement('div');
  host.id = 'sc-navbar-host';

  var backBtn = document.createElement('button');
  backBtn.className = 'sc-btn';
  backBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>';
  backBtn.onclick = function() { try { window.NavBridge.navGoBack(); } catch(e) {} };

  var fwdBtn = document.createElement('button');
  fwdBtn.className = 'sc-btn';
  fwdBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>';
  fwdBtn.onclick = function() { try { window.NavBridge.navGoForward(); } catch(e) {} };

  var urlBox = document.createElement('div');
  urlBox.className = 'sc-url';
  urlBox.id = 'sc-navbar-url';
  urlBox.textContent = '';

  var menuBtn = document.createElement('button');
  menuBtn.className = 'sc-btn';
  menuBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/></svg>';

  var menu = document.createElement('div');
  menu.className = 'sc-menu';
  menu.id = 'sc-navbar-menu';
  menu.innerHTML =
    '<div class="sc-menu-item" onclick="window.NavBridge.navExitTavern()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg> 返回启动器</div>' +
    '<div class="sc-menu-item" onclick="window.location.reload()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg> 刷新页面</div>' +
    '<div class="sc-divider"></div>' +
    '<div class="sc-menu-item" onclick="alert(\'设置功能开发中\')"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.67 15 1.65 1.65 0 0 0 3 13.51V13a2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 6.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 10 4.67V4a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2v.51a1.65 1.65 0 0 0-1 1.51z"/></svg> 设置</div>';

  menuBtn.onclick = function(e) {
    e.stopPropagation();
    menu.classList.toggle('open');
  };
  document.addEventListener('click', function() { menu.classList.remove('open'); });

  host.appendChild(backBtn);
  host.appendChild(fwdBtn);
  host.appendChild(urlBox);
  host.appendChild(menuBtn);
  host.appendChild(menu);
  document.body.insertBefore(host, document.body.firstChild);

  window.__navUpdate = function(state) {
    var urlEl = document.getElementById('sc-navbar-url');
    if (urlEl) urlEl.textContent = state.url || '';
    if (backBtn) backBtn.disabled = !state.canGoBack;
    if (fwdBtn) fwdBtn.disabled = !state.canGoForward;
  };

  try {
    var bridge = window.NavBridge;
    if (bridge) {
      window.__navUpdate({
        url: bridge.navGetCurrentUrl ? bridge.navGetCurrentUrl() : '',
        canGoBack: bridge.navCanGoBack ? bridge.navCanGoBack() : false,
        canGoForward: bridge.navCanGoForward ? bridge.navCanGoForward() : false
      });
    }
  } catch(e) {}

  console.log('[SC] simple navbar mounted');
})();
