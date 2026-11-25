/*
 * The room chat: a round button in the corner that opens a panel. On a phone the panel fills the
 * screen and follows the on-screen keyboard; on a wider screen it floats above the button.
 *
 * Reactions: a smiley button beside it opens a row of emoji (the panel has the same row). A
 * reaction floats up everyone's screen with the sender's name. It isn't shown in the chat log.
 *
 * It polls /api/room/{code}/chat on its own, so it never touches the game screen room.js draws.
 * Like room.js it builds everything with textContent: messages are typed by other people.
 */
(function (global) {
  'use strict';

  var OPEN_POLL_MS = 1000;
  var CLOSED_POLL_MS = 2500;
  var ERROR_SHOW_MS = 3000;
  var NEAR_BOTTOM_PX = 80;
  var GROUP_MS = 2 * 60 * 1000; // Lines from the same person this close together share one name.
  var MAX_FLOATING = 12; // A burst of reactions mustn't pile up hundreds of animations.
  var SVG = 'http://www.w3.org/2000/svg';

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  function icon(path) {
    var svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    var shape = document.createElementNS(SVG, 'path');
    shape.setAttribute('d', path);
    svg.appendChild(shape);
    return svg;
  }

  function formatTime(millis) {
    return new Date(millis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function startChat(options) {
    var code = options.code;
    var lastId = 0;
    var loaded = false;
    var open = false;
    var stopped = false;
    var sending = false;
    var unread = 0;
    var timer = null;
    var errorTimer = null;
    var previous = null; // The last message drawn, for grouping.
    var trayOpen = false;
    var reactionsBuilt = false;
    var calm = global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // The toggle button.
    var toggle = el('button', 'chat-toggle');
    toggle.type = 'button';
    toggle.setAttribute('aria-label', 'Open chat');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-controls', 'chat-panel');
    toggle.appendChild(icon('M4 3h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H9l-5 4v-4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z'));
    var badge = el('span', 'chat-badge');
    badge.hidden = true;
    toggle.appendChild(badge);

    // The panel.
    var panel = el('section', 'chat-panel');
    panel.id = 'chat-panel';
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', 'Room chat');
    panel.hidden = true;

    var header = el('div', 'chat-header');
    header.appendChild(el('h2', 'chat-title', 'Chat'));
    var close = el('button', 'chat-close');
    close.type = 'button';
    close.setAttribute('aria-label', 'Close chat');
    close.appendChild(icon('M6 6l12 12M18 6L6 18'));
    header.appendChild(close);

    var scroller = el('div', 'chat-scroller');
    var empty = el('p', 'chat-empty', 'No messages yet. Say hi!');
    var list = el('ol', 'chat-messages');
    list.setAttribute('aria-live', 'polite');
    scroller.appendChild(empty);
    scroller.appendChild(list);

    var error = el('p', 'chat-error');
    error.hidden = true;

    var form = el('form', 'chat-form');
    var input = el('input', 'chat-input');
    input.type = 'text';
    input.placeholder = 'Message';
    input.maxLength = 200;
    input.autocomplete = 'off';
    input.setAttribute('enterkeyhint', 'send');
    input.setAttribute('aria-label', 'Message');
    var sendButton = el('button', 'chat-send');
    sendButton.type = 'submit';
    sendButton.setAttribute('aria-label', 'Send');
    sendButton.appendChild(icon('M3 20l18-8L3 4v6l12 2-12 2z'));
    form.appendChild(input);
    form.appendChild(sendButton);

    // Reactions. Both rows are filled in once the server says which emoji it accepts.
    var reactToggle = el('button', 'react-toggle', '🙂');
    reactToggle.type = 'button';
    reactToggle.setAttribute('aria-label', 'React');
    reactToggle.setAttribute('aria-expanded', 'false');
    reactToggle.setAttribute('aria-controls', 'react-tray');
    reactToggle.hidden = true;
    var tray = el('div', 'react-tray');
    tray.id = 'react-tray';
    tray.setAttribute('role', 'group');
    tray.setAttribute('aria-label', 'Reactions');
    tray.hidden = true;
    var panelReactions = el('div', 'chat-reactions');
    panelReactions.setAttribute('role', 'group');
    panelReactions.setAttribute('aria-label', 'Reactions');
    var floats = el('div', 'reaction-floats');
    floats.setAttribute('aria-hidden', 'true'); // The chat log already announces them.

    [header, scroller, error, panelReactions, form].forEach(function (part) { panel.appendChild(part); });
    [floats, panel, tray, reactToggle, toggle].forEach(function (part) { document.body.appendChild(part); });
    document.body.classList.add('has-chat');

    function buildReactions(emojis) {
      if (reactionsBuilt || !emojis || !emojis.length) { return; }
      reactionsBuilt = true;
      emojis.forEach(function (emoji) {
        [tray, panelReactions].forEach(function (holder) {
          var button = el('button', 'reaction-choice', emoji);
          button.type = 'button';
          button.setAttribute('aria-label', 'React ' + emoji);
          button.addEventListener('click', function () {
            button.classList.remove('pop');
            void button.offsetWidth; // Restart the animation.
            button.classList.add('pop');
            sendReaction(emoji);
          });
          holder.appendChild(button);
        });
      });
      reactToggle.hidden = false;
    }

    function setTray(next) {
      trayOpen = next;
      tray.hidden = !next;
      reactToggle.setAttribute('aria-expanded', String(next));
      reactToggle.classList.toggle('active', next);
    }

    function floatReaction(message) {
      if (calm) { return; }
      var bubble = el('div', 'reaction-float');
      bubble.appendChild(el('span', 'reaction-float-emoji', message.text));
      bubble.appendChild(el('span', 'reaction-float-name', message.mine ? 'You' : message.author));
      bubble.style.left = (12 + Math.random() * 76) + '%';
      bubble.addEventListener('animationend', function () { bubble.remove(); });
      floats.appendChild(bubble);
      while (floats.childNodes.length > MAX_FLOATING) { floats.removeChild(floats.firstChild); }
    }

    function nearBottom() {
      return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < NEAR_BOTTOM_PX;
    }

    function scrollToBottom() {
      scroller.scrollTop = scroller.scrollHeight;
    }

    function updateBadge() {
      badge.hidden = unread === 0;
      badge.textContent = unread > 9 ? '9+' : String(unread);
      toggle.setAttribute('aria-label', unread ? 'Open chat, ' + unread + ' new' : 'Open chat');
    }

    function draw(message) {
      if (message.reaction) { return; } // Reactions only float; they aren't logged in the chat.
      var grouped = previous && previous.author === message.author && previous.host === message.host &&
        previous.mine === message.mine && message.sentAt - previous.sentAt < GROUP_MS;
      var item = el('li', 'chat-message' + (message.mine ? ' mine' : '') + (message.host ? ' host' : '') +
        (grouped ? ' grouped' : ''));
      if (!message.mine && !grouped) {
        item.appendChild(el('span', 'chat-author', message.author));
      }
      var bubble = el('span', 'chat-bubble', message.text);
      var time = el('time', 'chat-time', formatTime(message.sentAt));
      time.dateTime = new Date(message.sentAt).toISOString();
      bubble.appendChild(time);
      item.appendChild(bubble);
      list.appendChild(item);
      previous = message;
    }

    function apply(view) {
      if (view.maxLength) { input.maxLength = view.maxLength; }
      buildReactions(view.reactions);
      // A send's response and a poll can carry the same messages.
      var fresh = view.messages.filter(function (message) { return message.id > lastId; });
      if (!fresh.length) { return; }

      var stick = nearBottom() || fresh.some(function (message) { return message.mine; });
      fresh.forEach(draw);
      lastId = fresh[fresh.length - 1].id;
      if (fresh.some(function (message) { return !message.reaction; })) { empty.hidden = true; }
      // Only live ones float: not the history a device loads when it opens the page.
      if (loaded) {
        fresh.filter(function (message) { return message.reaction; }).forEach(floatReaction);
      }

      if (!open && loaded) {
        // Reactions float past on screen, so only typed messages count as unread.
        var incoming = fresh.filter(function (message) { return !message.mine && !message.reaction; }).length;
        if (incoming) {
          unread += incoming;
          updateBadge();
          toggle.classList.remove('nudge');
          void toggle.offsetWidth; // Restart the animation.
          toggle.classList.add('nudge');
        }
      }
      if (stick || !loaded) { scrollToBottom(); }
    }

    function showError(text) {
      error.textContent = text;
      error.hidden = false;
      clearTimeout(errorTimer);
      errorTimer = setTimeout(function () { error.hidden = true; }, ERROR_SHOW_MS);
    }

    function shutDown() {
      stopped = true;
      clearTimeout(timer);
      [panel, toggle, reactToggle, tray, floats].forEach(function (part) { part.remove(); });
      document.body.classList.remove('has-chat', 'chat-open');
    }

    function schedule() {
      if (stopped) { return; }
      clearTimeout(timer);
      timer = setTimeout(poll, open ? OPEN_POLL_MS : CLOSED_POLL_MS);
    }

    function poll() {
      if (stopped) { return; }
      fetch('/api/room/' + code + '/chat?after=' + lastId, { headers: { 'Accept': 'application/json' } })
        .then(function (response) {
          // Room closed, or this device left or was removed: no chat to be part of.
          if (response.status === 404 || response.status === 403) {
            shutDown();
            return null;
          }
          return response.ok ? response.json() : null;
        })
        .then(function (view) {
          if (view) {
            apply(view);
            loaded = true;
          }
          schedule();
        })
        .catch(schedule); // Wi-Fi blinking or the server restarting: try again.
    }

    function send(event) {
      event.preventDefault();
      var text = input.value.trim();
      if (!text || sending) { return; }
      sending = true;
      sendButton.disabled = true;

      fetch('/api/room/' + code + '/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({ text: text, after: lastId })
      })
        .then(function (response) {
          if (response.status === 404 || response.status === 403) {
            shutDown();
            return null;
          }
          return response.json().then(function (view) {
            if (response.ok) { input.value = ''; }
            if (view.error) { showError(view.error); }
            apply(view);
          });
        })
        .catch(function () { showError("Couldn't send. Check your connection."); })
        .then(function () {
          sending = false;
          sendButton.disabled = false;
          if (!stopped) { input.focus(); } // Keeps a phone's keyboard up for the next message.
        });
    }

    function sendReaction(emoji) {
      if (stopped) { return; }
      fetch('/api/room/' + code + '/chat/react', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({ emoji: emoji, after: lastId })
      })
        .then(function (response) {
          if (response.status === 404 || response.status === 403) {
            shutDown();
            return null;
          }
          return response.json().then(function (view) {
            // Tapping faster than the limit just drops the extra ones; no need to scold.
            if (view.error && response.status !== 429) { showError(view.error); }
            apply(view);
          });
        })
        .catch(function () { /* a lost reaction isn't worth an error */ });
    }

    // The on-screen keyboard shrinks the visual viewport but not the layout one on iOS, so a
    // full-screen panel would end up behind it. Size the panel to what is actually visible.
    function fitToViewport() {
      var viewport = global.visualViewport;
      if (!viewport) { return; }
      panel.style.setProperty('--chat-viewport-height', viewport.height + 'px');
      panel.style.setProperty('--chat-viewport-top', viewport.offsetTop + 'px');
      if (open && nearBottom()) { scrollToBottom(); }
    }

    function setOpen(next) {
      open = next;
      panel.hidden = !open;
      toggle.setAttribute('aria-expanded', String(open));
      document.body.classList.toggle('chat-open', open);
      if (open) {
        setTray(false); // The panel has its own row of reactions.
        unread = 0;
        updateBadge();
        fitToViewport();
        scrollToBottom();
        // Only a mouse-and-keyboard screen gets the cursor straight away; on a phone it would pop
        // the keyboard up over the messages before they've been read.
        if (global.matchMedia && global.matchMedia('(pointer: fine)').matches) { input.focus(); }
        poll();
      } else {
        input.blur();
        toggle.focus();
        schedule();
      }
    }

    toggle.addEventListener('click', function () { setOpen(!open); });
    close.addEventListener('click', function () { setOpen(false); });
    form.addEventListener('submit', send);
    reactToggle.addEventListener('click', function () { setTray(!trayOpen); });
    // A tap anywhere else closes the tray.
    document.addEventListener('click', function (event) {
      if (trayOpen && !tray.contains(event.target) && !reactToggle.contains(event.target)) { setTray(false); }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') { return; }
      if (trayOpen) {
        setTray(false);
        reactToggle.focus();
      } else if (open) {
        setOpen(false);
      }
    });
    if (global.visualViewport) {
      global.visualViewport.addEventListener('resize', fitToViewport);
      global.visualViewport.addEventListener('scroll', fitToViewport);
    }

    poll();
  }

  global.startChat = startChat;
})(window);
