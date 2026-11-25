// "Copy" buttons for the host pages' join links: <button data-copy="text">. The Clipboard API needs a
// secure context, which localhost is; the fallback covers a host page opened by IP address.
(function () {
  function fallbackCopy(text) {
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(area);
    return ok;
  }

  function copy(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () { return true; }, function () {
        return fallbackCopy(text);
      });
    }
    return Promise.resolve(fallbackCopy(text));
  }

  document.querySelectorAll('[data-copy]').forEach(function (button) {
    var label = button.textContent;
    var timer = null;
    button.addEventListener('click', function () {
      copy(button.getAttribute('data-copy')).then(function (ok) {
        button.textContent = ok ? 'Copied ✓' : 'Copy failed';
        button.classList.toggle('copied', ok);
        clearTimeout(timer);
        timer = setTimeout(function () {
          button.textContent = label;
          button.classList.remove('copied');
        }, 2000);
      });
    });
  });
})();
