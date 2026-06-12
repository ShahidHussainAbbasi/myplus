// Demo free-trial UX: when any AJAX create is rejected with the gateway's 403 DEMO_LIMIT, show a clean
// upsell prompt instead of (only) a generic error. Loaded on every authenticated page (header-js).
(function () {
  if (!window.jQuery) return;

  function showDemoUpsell(msg) {
    if (document.getElementById('demoUpsellOverlay')) return;
    var html =
      '<div id="demoUpsellOverlay" style="position:fixed;inset:0;background:rgba(13,35,83,.55);z-index:99999;display:flex;align-items:center;justify-content:center">'
      + '<div style="background:#fff;max-width:460px;width:92%;border-radius:14px;box-shadow:0 12px 48px rgba(0,0,0,.3);overflow:hidden;font-family:inherit">'
      + '<div style="background:linear-gradient(135deg,#0D3B8C,#1565C0);color:#fff;padding:20px 24px">'
      + '<h3 style="margin:0;font-weight:700;font-size:19px">You\'ve reached the demo limit</h3></div>'
      + '<div style="padding:22px 24px">'
      + '<p style="margin:0 0 20px;color:#334155;line-height:1.6">' + (msg || 'You\'ve reached the 50-entry demo limit. Register at maxtheservice.com to unlock the full features.') + '</p>'
      + '<div style="display:flex;gap:10px;justify-content:flex-end">'
      + '<button type="button" onclick="document.getElementById(\'demoUpsellOverlay\').remove()" style="padding:9px 16px;border:1px solid #cbd5e1;background:#fff;border-radius:8px;cursor:pointer">Keep exploring</button>'
      + '<a href="/registration.html" style="padding:9px 18px;background:#1565C0;color:#fff;border-radius:8px;text-decoration:none;font-weight:600">Register free</a>'
      + '</div></div></div></div>';
    document.body.insertAdjacentHTML('beforeend', html);
  }
  window.showDemoUpsell = showDemoUpsell;

  // "Reset demo" — clear the demo write counters at the gateway so the trial restarts.
  window.resetDemo = function () {
    jQuery.post('/demo/reset')
      .done(function (d) {
        if (window.toast) { toast((d && d.message) || 'Demo reset.'); }
        setTimeout(function () { window.location.reload(); }, 600);
      })
      .fail(function () { alert('Could not reset the demo right now. Please try again.'); });
  };

  jQuery(document).ajaxError(function (e, xhr) {
    if (xhr && xhr.status === 403) {
      var j = xhr.responseJSON;
      if (j && j.code === 'DEMO_LIMIT') {
        showDemoUpsell(j.message);
      }
    }
  });
})();
