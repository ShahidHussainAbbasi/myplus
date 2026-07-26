/*
 * Show/hide password — drop-in eye toggle for every password field on the page. Self-initializing: include the
 * script and it injects an eye button into each input[type="password"]. Works regardless of the page's CSS — it sets
 * the button's essential positioning inline (so it works on the old Bootstrap-3 password screens too) and also adds
 * the `pw-toggle` class so login.css can enhance it (hover) where that stylesheet is loaded. Icon = Bootstrap
 * glyphicon (loaded on all these pages). Pure client-side; no value ever leaves the field.
 *
 * Opt-out: add `data-no-pw-toggle` to an input to skip it.
 */
(function () {
  'use strict';

  function attach(input) {
    if (input.dataset.pwToggled || input.hasAttribute('data-no-pw-toggle')) return;
    input.dataset.pwToggled = '1';

    var wrap = input.parentElement;
    if (!wrap) return;
    // Ensure a positioning context for the absolutely-placed button.
    if (getComputedStyle(wrap).position === 'static') wrap.style.position = 'relative';
    // Leave room on the right so typed text doesn't run under the eye.
    input.style.paddingRight = '42px';

    var btn = document.createElement('button');
    btn.type = 'button';                 // never submits the form
    btn.className = 'pw-toggle';
    btn.setAttribute('aria-label', 'Show password');
    btn.title = 'Show password';
    btn.style.cssText = 'position:absolute;right:8px;top:50%;transform:translateY(-50%);' +
      'width:32px;height:32px;display:flex;align-items:center;justify-content:center;' +
      'background:none;border:none;border-radius:6px;cursor:pointer;color:#94a3b8;font-size:15px;padding:0;z-index:2';
    btn.innerHTML = '<span class="glyphicon glyphicon-eye-open"></span>';

    btn.addEventListener('click', function () {
      var show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      var icon = btn.querySelector('.glyphicon');
      if (icon) {
        icon.classList.toggle('glyphicon-eye-open', !show);
        icon.classList.toggle('glyphicon-eye-close', show);
      }
      btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
      btn.title = show ? 'Hide password' : 'Show password';
      input.focus();
    });

    wrap.appendChild(btn);
  }

  function init() {
    document.querySelectorAll('input[type="password"]').forEach(attach);
  }

  if (document.readyState !== 'loading') init();
  else document.addEventListener('DOMContentLoaded', init);
})();
