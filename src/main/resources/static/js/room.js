/*
 * The networked game's client. Both the host screen and the players' phones run this: they poll
 * /api/room/{code}/state and redraw from whatever comes back. The server decides what each device
 * is allowed to see, so the only difference between a phone and the host screen here is that the
 * host is never "you" and so never gets answer buttons.
 *
 * A Theme Party room's state carries a `party` object, and createPartyBoard draws those instead.
 *
 * Everything is built with textContent rather than innerHTML — questions come from opentdb with
 * HTML entities already decoded, or from theme files, so they can contain markup that must not be
 * executed.
 */
(function (global) {
  'use strict';

  var POLL_MS = 1000;
  var TIMED_POLL_MS = 300; // A second's lag is too much when there are only a few on the clock.
  var CLOCK_TICK_MS = 100;
  var URGENT_MS = 3000;
  var SETTLE_MS = 600;
  var FEW_THEMES = 8; // Few enough for big buttons. Matches THEMES_OFFERED, so a normal deal gets them.
  var ROW_PX = 30; // Must match .score-row's height.
  var RESULT_STAGGER_MS = 120;
  var MIN_SCALE = 10; // So the first few points don't fill the whole track.

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  function points(value) {
    return (value > 0 ? '+' : '') + value;
  }

  /*
   * The Theme Party screen. Unlike the classic one it can't redraw on every version change: each
   * player's answer bumps the version, and replacing a button while someone's finger is on it
   * swallows the tap. So the content is rebuilt only when the screen itself changes, and everything
   * else is updated in place. The scoreboard is never rebuilt, which also lets its bars animate.
   */
  function createPartyBoard(code, mount, isHost) {
    var latest = null;
    var screenKey = null;
    var turnKey = null;
    var settlingUntil = 0;
    var wasYourPick = false;
    var flagMode = false; // While on, tapping a theme flags it as bad instead of picking it.
    var lobbyThemesOpen = false; // Whether the lobby's theme list is open, kept when the lobby redraws.
    var nextAt = 0; // When Next becomes tappable after a reveal.
    var nextTimer = null;
    var updaters = [];
    var pending = {};
    var scoreboard = createScoreboard(isHost ? kick : null);
    var content = el('div');
    var clock = createClock();
    scoreboard.node.insertBefore(clock.node, scoreboard.node.firstChild);

    mount.textContent = '';
    mount.appendChild(scoreboard.node);
    mount.appendChild(content);

    function kick(player, button) {
      if (!global.confirm('Kick ' + player.name + ' out of the game?')) { return; }
      button.disabled = true;
      // The next poll redraws without them.
      fetch('/party/host/kick', { method: 'POST', body: new URLSearchParams({ playerId: player.id }) })
        .catch(function () { /* re-enabled below, so it can be tapped again */ })
        .then(function () { button.disabled = false; });
    }

    function send(path, body) {
      pending[path] = true;
      update();
      fetch('/api/room/' + code + '/party/' + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      })
        .then(function (response) { return response.json(); })
        .then(function (next) { if (next && next.party) { render(next); } })
        .catch(function () { /* re-enabled below, so it can be tapped again */ })
        .then(function () {
          delete pending[path];
          update();
        });
    }

    function update() {
      updaters.forEach(function (apply) { apply(latest.party); });
    }

    function blocked(path) {
      return !!pending[path] || Date.now() < settlingUntil;
    }

    function headline(party) {
      if (party.stage === 'lobby') {
        if (!party.players.length) { return 'Waiting for players to join...'; }
        return latest.canStart ? 'Ready when the host starts the game.' : 'Waiting for more players...';
      }
      if (party.stage === 'finished') {
        return party.winners.length === 1
          ? party.winners[0] + ' wins!'
          : "It's a tie: " + party.winners.join(', ');
      }
      if (party.stage === 'picking') {
        return party.yourPick ? 'Your turn to pick a theme' : party.picker + ' is picking a theme...';
      }
      if (party.stage === 'tiebreak') { return "It's a tie! Tiebreaker..."; }
      if (party.stage === 'answering' && !party.question) { return 'Get ready...'; }
      return party.tiebreak ? 'Tiebreaker: ' + party.theme : party.theme;
    }

    function tiebreakerNames(party) {
      return party.players
        .filter(function (player) { return player.tiebreaker; })
        .map(function (player) { return player.you ? 'you' : player.name; });
    }

    function ordinal(n) {
      var tens = n % 100;
      if (tens >= 11 && tens <= 13) { return n + 'th'; }
      return n + ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th');
    }

    function addThemes(party) {
      if (!party.themes.length) {
        content.appendChild(el('p', 'error', 'No themes available. Ask the host to add one.'));
      }
      // Being ahead means fewer themes to choose from; say why, so it doesn't look like a bug.
      if (party.pickerPlace && party.themes.length) {
        content.appendChild(el('p', 'place-note', "You're " + ordinal(party.pickerPlace) +
          ', so you pick from ' + party.themes.length + (party.themes.length === 1 ? ' theme.' : ' themes.')));
      }
      var grid = el('div', 'theme-grid' + (party.themes.length <= FEW_THEMES ? ' few' : ''));
      party.themes.forEach(function (theme) {
        var button = el('button', 'theme-choice' + (theme.flagged ? ' flagged' : ''), theme.name);
        button.type = 'button';
        if (theme.flagged) { button.title = 'You flagged this as a bad theme'; }
        button.addEventListener('click', function () {
          if (flagMode) {
            flagMode = false;
            update();
            send('flag', { theme: theme.id });
            return;
          }
          send('pick', { version: latest.version, theme: theme.id });
        });
        updaters.push(function () {
          // In flag mode a theme already flagged by you can't be flagged again.
          button.disabled = blocked('pick') || blocked('flag') || (flagMode && theme.flagged);
        });
        grid.appendChild(button);
      });
      updaters.push(function () { grid.classList.toggle('flagging', flagMode); });
      content.appendChild(grid);

      if (!party.themes.length) { return; }
      var flagToggle = el('button', 'flag-toggle');
      flagToggle.type = 'button';
      flagToggle.addEventListener('click', function () {
        flagMode = !flagMode;
        update();
      });
      updaters.push(function () {
        flagToggle.textContent = flagMode ? 'Cancel: tap a theme to flag it as bad' : '👎 Flag a bad theme';
        flagToggle.classList.toggle('active', flagMode);
      });
      content.appendChild(flagToggle);
    }

    function addQuestion(party, canAnswer) {
      var card = el('fieldset');
      card.appendChild(el('p', 'question-text', party.question.question));

      party.question.answers.forEach(function (answer) {
        if (!canAnswer) {
          card.appendChild(el('div', 'answer readonly', answer));
          return;
        }
        var button = el('button', 'answer', answer);
        button.type = 'button';
        button.addEventListener('click', function () {
          send('answer', { version: latest.version, answer: answer });
        });
        updaters.push(function (current) {
          if (!current.question) { return; }
          var chosen = current.question.yourAnswer;
          button.disabled = chosen !== null || blocked('answer');
          button.classList.toggle('picked', chosen === answer);
        });
        card.appendChild(button);
      });
      content.appendChild(card);

      var waiting = el('p', 'question-meta');
      updaters.push(function (current) {
        if (!current.question) { return; }
        var names = current.players
          // In a tiebreak, only those tied are waited for.
          .filter(function (player) { return !player.answered && (!current.tiebreak || player.tiebreaker); })
          .map(function (player) { return player.you ? 'you' : player.name; });
        waiting.textContent = names.length ? 'Waiting for ' + names.join(', ') : '';
      });
      content.appendChild(waiting);
    }

    // What this question did to a player's score, as a suffix, including the first-correct point.
    // Blank for a guesser who was wrong.
    function scoreChange(party, player) {
      var extra = (player.first ? party.reveal.firstCorrectPoints : 0) +
        (player.streakBonus ? party.reveal.streakPoints : 0);
      if (player.picker) { return ' ' + points(party.reveal.points + extra); }
      return player.correct ? ' ' + points(party.reveal.guesserPoints + extra) : '';
    }

    // Symbol and word as well as color, for anyone who can't tell red from green.
    function addResults(party) {
      var you = party.players.filter(function (player) { return player.you; })[0];
      if (you) {
        content.appendChild(el('div', 'result-banner ' + (you.correct ? 'right' : 'wrong'),
          (you.first ? '\u26a1 FIRST TO GET IT RIGHT'
            : you.correct ? '\u2713 YOU GOT IT RIGHT'
            : (you.answered ? '\u2717 YOU GOT IT WRONG' : '\u2717 OUT OF TIME')) + scoreChange(party, you)));
      }

      var grid = el('div', 'results' + (isHost ? ' on-host' : ''));
      var ordered = party.players.slice().sort(function (a, b) {
        return Number(b.picker) - Number(a.picker); // The picker first, then join order.
      });
      ordered.forEach(function (player, index) {
        var tile = el('div', 'result-tile ' + (player.correct ? 'right' : 'wrong') + (player.first ? ' first' : ''));
        tile.style.animationDelay = index * RESULT_STAGGER_MS + 'ms';
        if (player.first) { tile.appendChild(el('span', 'result-first', '\u26a1 FIRST')); }
        if (player.streakBonus) { tile.appendChild(el('span', 'result-streak', '\ud83d\udd25 ' + player.streak + ' IN A ROW')); }
        tile.appendChild(el('span', 'result-mark', player.correct ? '\u2713' : '\u2717'));
        tile.appendChild(el('span', 'result-name', player.name + (player.you ? ' (you)' : '')));
        tile.appendChild(el('span', 'result-word', (player.correct ? 'RIGHT' : (player.answered ? 'WRONG' : 'NO ANSWER')) +
          scoreChange(party, player)));
        grid.appendChild(tile);
      });
      content.appendChild(grid);
    }

    // The lobby's short guide to scoring, so players know the rules while they wait.
    function addRules(rules) {
      var card = el('div', 'rules');
      card.appendChild(el('h3', 'rules-title', 'How scoring works'));
      var list = el('ul', 'rules-list');
      var lines = [
        ['Your pick, you’re right', '+1 per player in the game, ' + (-rules.penaltyPerOtherCorrect) +
          ' for each other player also right' + (rules.bonusNeedsSecond ? ' (needs someone else right too)' : '')],
        ['Your pick, you’re wrong', (-rules.penaltyPerOtherCorrect) + ' for each player who’s right'],
        ['Someone else’s pick, you’re right', points(rules.guesserPoints)],
        ['⚡ First right answer', points(rules.firstCorrectPoints) + ' extra'],
        ['🔥 ' + rules.streakLength + '+ right in a row', points(rules.streakPoints) + ' extra for each one'],
        ['⚔ Tie at the end', 'tiebreaker: first right answer wins']
      ];
      if (rules.limitThemes) {
        lines.push(['In the lead?', 'fewer themes to pick from: ' + rules.themesForLeader + ' for 1st, ' +
          Math.min(rules.themesForLeader + rules.themesPerPlace, rules.themesOffered) + ' for 2nd']);
      }
      lines.forEach(function (line) {
        var item = el('li');
        item.appendChild(el('span', 'rules-when', line[0]));
        item.appendChild(el('span', 'rules-what', line[1]));
        list.appendChild(item);
      });
      card.appendChild(list);
      content.appendChild(card);
    }

    function lobbyTheme(party, id) {
      return (party.lobbyThemes || []).filter(function (theme) { return theme.id === id; })[0];
    }

    // While waiting for the start, players can hide themes from this game. The list is the room's,
    // shared with every player and the host, so each button shows what the server last said.
    function addLobbyThemes(party) {
      var box = el('details', 'lobby-themes');
      box.open = lobbyThemesOpen;
      box.addEventListener('toggle', function () { lobbyThemesOpen = box.open; });
      var summary = el('summary', 'lobby-themes-title');
      box.appendChild(summary);
      box.appendChild(el('p', 'question-meta',
        'Tap a theme to hide it from this game, so nobody can pick it. Tap it again to bring it back.'));

      var list = el('div', 'lobby-theme-list');
      party.lobbyThemes.forEach(function (theme) {
        var button = el('button', 'lobby-theme', theme.name);
        button.type = 'button';
        button.addEventListener('click', function () {
          var current = lobbyTheme(latest.party, theme.id);
          if (current) { send('hide', { theme: theme.id, hidden: !current.hidden }); }
        });
        updaters.push(function (current) {
          var now = lobbyTheme(current, theme.id);
          var hidden = !!(now && now.hidden);
          button.classList.toggle('hidden-theme', hidden);
          button.setAttribute('aria-pressed', String(hidden));
          button.title = hidden ? 'Hidden from this game. Tap to show it again.' : 'Tap to hide it from this game';
          // Not disabled while a tap is sent, or the whole list would flash grey. A double tap
          // is harmless: both ask for the same state.
          button.disabled = !now;
        });
        list.appendChild(button);
      });
      box.appendChild(list);

      updaters.push(function (current) {
        var hidden = (current.lobbyThemes || []).filter(function (theme) { return theme.hidden; }).length;
        summary.textContent = 'Themes (' + party.lobbyThemes.length + ')' + (hidden ? ': ' + hidden + ' hidden' : '');
      });
      content.appendChild(box);
    }

    // A tiebreak question's reveal: who won, or that nobody did and another question follows.
    function addTiebreakReveal(party, canAnswer, inGame) {
      var item = party.reveal;
      var winner = party.players.filter(function (player) { return player.first; })[0];
      var text = winner
        ? '🏆 ' + (winner.you ? 'YOU WIN' : winner.name.toUpperCase() + ' WINS') + ' THE TIEBREAKER ' +
          points(item.tiebreakPoints)
        : 'NOBODY GOT IT: ANOTHER QUESTION';
      content.appendChild(el('div', 'result-banner ' + (winner ? 'right' : 'wrong'), text));

      var card = el('fieldset');
      card.appendChild(el('p', 'question-text', item.question));
      card.appendChild(el('p', 'reveal-answer', 'Answer: ' + item.correctAnswer));
      if (canAnswer && item.yourAnswer !== item.correctAnswer) {
        card.appendChild(el('p', 'your-answer',
          item.yourAnswer === null ? "You didn't answer" : 'You answered: ' + item.yourAnswer));
      }
      card.appendChild(el('p', null, item.explanation));
      content.appendChild(card);

      if (!inGame) { return; }
      var next = el('button', 'next', winner ? 'See final scores' : 'Next tiebreaker question');
      next.addEventListener('click', function () { send('next', { version: latest.version }); });
      updaters.push(function () { next.disabled = blocked('next') || Date.now() < nextAt; });
      content.appendChild(next);
    }

    function addReveal(party, canAnswer, inGame) {
      if (party.tiebreak) { return addTiebreakReveal(party, canAnswer, inGame); }
      var item = party.reveal;
      addResults(party);
      var card = el('fieldset');
      card.appendChild(el('p', 'question-text', item.question));
      card.appendChild(el('p', 'reveal-answer', 'Answer: ' + item.correctAnswer));
      if (canAnswer && item.yourAnswer !== item.correctAnswer) {
        card.appendChild(el('p', 'your-answer',
          item.yourAnswer === null ? "You didn't answer in time" : 'You answered: ' + item.yourAnswer));
      }
      card.appendChild(el('p', null, item.explanation));

      var parts = [];
      if (item.bonus) {
        parts.push(points(item.bonus) + ' correct');
      } else if (item.pickerCorrect) {
        parts.push('correct, but no bonus without a second correct answer');
      } else {
        parts.push('not correct');
      }
      if (item.penalty) {
        parts.push('-' + item.penalty + ' for ' + item.othersCorrect +
          (item.othersCorrect === 1 ? ' other' : ' others') + ' correct');
      }
      card.appendChild(el('p', 'turn-total',
        party.picker + ': ' + points(item.points) + ' (' + parts.join(', ') + ')'));
      if (item.firstCorrect) {
        card.appendChild(el('p', 'first-correct',
          '⚡ ' + item.firstCorrect + ' was first to get it right: ' + points(item.firstCorrectPoints) + ' extra'));
      }
      party.players.filter(function (player) { return player.streakBonus; }).forEach(function (player) {
        card.appendChild(el('p', 'streak-bonus', '🔥 ' + player.name + ' has ' + player.streak +
          ' in a row: ' + points(item.streakPoints) + ' extra'));
      });
      content.appendChild(card);

      if (!inGame) { return; }
      var next = el('button', 'next',
        party.nextPicker === null ? 'See final scores' : 'Next: ' + party.nextPicker + "'s turn");
      // Left without type="button", which the stylesheet draws as secondary. It isn't in a form.
      next.addEventListener('click', function () { send('next', { version: latest.version }); });
      // Held for a moment after the reveal, so the answer can't be skipped before it's read.
      updaters.push(function () { next.disabled = blocked('next') || Date.now() < nextAt; });
      content.appendChild(next);
    }

    function build(party, canAnswer, inGame) {
      updaters = [];
      if (!party.themes) { flagMode = false; } // A new screen never starts in flag mode.
      content.textContent = '';
      content.appendChild(el('h2', 'room-status', headline(party)));

      if (party.tiebreak) {
        var tied = tiebreakerNames(party);
        content.appendChild(el('p', 'tiebreak-note', 'Tiebreaker between ' + tied.join(' and ') +
          '. First to get it right wins.' + (canAnswer ? '' : " You're watching this one.")));
      } else if (party.stage !== 'lobby' && party.stage !== 'finished') {
        content.appendChild(el('p', 'question-meta',
          'Round ' + party.round + ' of ' + party.rounds + ' (' + party.difficulty + '). ' +
          (party.yourPick ? 'Your turn' : party.picker + "'s turn") +
          (party.bonusNeedsSecond ? '. Bonus needs a second correct answer.' : '.')));
      }
      if (latest.notice) { content.appendChild(el('p', 'error', latest.notice)); }

      // The theme flash: the server holds the question back for a moment after the pick.
      if (party.stage === 'answering' && !party.question) {
        var flash = el('div', 'theme-flash');
        flash.appendChild(el('span', 'theme-flash-label',
          party.tiebreak ? 'Tiebreaker theme' : (party.yourPick ? 'You picked' : party.picker + ' picked')));
        flash.appendChild(el('strong', 'theme-flash-name', party.theme));
        content.appendChild(flash);
        return;
      }

      // Players wait here for the host, so it's a good moment to explain the scoring.
      if (party.stage === 'lobby' && party.rules && !isHost) { addRules(party.rules); }
      // The host hides themes from their own list on the host page.
      if (party.stage === 'lobby' && inGame && !isHost && party.lobbyThemes && party.lobbyThemes.length) {
        addLobbyThemes(party);
      }
      if (party.themes) { addThemes(party); }
      if (party.question) { addQuestion(party, canAnswer); }
      if (party.reveal) { addReveal(party, canAnswer, inGame); }
    }

    function render(state) {
      // A poll sent before a tap can land after that tap's response; don't go backwards.
      if (latest && state.version < latest.version) { return; }
      latest = state;
      var party = state.party;

      if (party.removed && !isHost) {
        global.location.replace('/j/' + code); // The join page says why.
        return;
      }

      var picker = party.players.filter(function (player) { return player.picker; })[0];
      var nextTurnKey = [party.stage, party.round, picker ? picker.id : '', party.tiebreakRound].join(':');
      var you = party.players.filter(function (player) { return player.you; })[0];
      var inGame = !!you;
      // In a tiebreak only those tied answer; everyone else in the game watches.
      var canAnswer = inGame && (!party.tiebreak || you.tiebreaker);
      var nextScreenKey = JSON.stringify([
        nextTurnKey,
        canAnswer,
        inGame,
        party.players.length > 0,
        party.themes,
        party.nextPicker,
        party.winners,
        party.reveal,
        // The result tiles, which change if someone is kicked or leaves during the reveal.
        party.reveal && party.players.map(function (player) { return [player.id, player.correct]; }),
        state.notice,
        state.canStart,
        party.rules, // The host may change a setting the guide mentions.
        // Themes added or removed. Hiding one only updates its button, so this leaves that out.
        party.lobbyThemes && party.lobbyThemes.map(function (theme) { return [theme.id, theme.name]; }),
        // Distinguishes a restarted game, whose question differs at the same stage and turn.
        party.question && [party.question.question, party.question.answers]
      ]);

      scoreboard.update(party);
      clock.update(party, nextTurnKey);

      if (party.millisUntilNext) {
        nextAt = Date.now() + party.millisUntilNext;
        clearTimeout(nextTimer);
        nextTimer = setTimeout(update, party.millisUntilNext + 20);
      } else {
        nextAt = 0;
      }

      if (nextScreenKey !== screenKey) {
        if (turnKey !== null && nextTurnKey !== turnKey) {
          // The new screen's buttons land where the old ones were, and Next acts for everyone, so
          // the second half of a double tap must not press one.
          settlingUntil = Date.now() + SETTLE_MS;
          setTimeout(update, SETTLE_MS + 20);
          // The host page has its own content above the board, so only move players' screens.
          if (state.you && !isHost) { global.scrollTo(0, 0); }
        }
        build(party, canAnswer, inGame);
        screenKey = nextScreenKey;
        turnKey = nextTurnKey;
      }
      update();

      // Players can hide themes in the lobby too, so the host page's own theme list follows along.
      if (isHost && party.lobbyThemes) {
        mount.dispatchEvent(new CustomEvent('lobbythemes', { detail: party.lobbyThemes }));
      }

      var yourPick = party.stage === 'picking' && party.yourPick;
      if (yourPick && !wasYourPick && global.navigator && global.navigator.vibrate) {
        global.navigator.vibrate(200);
      }
      wasYourPick = yourPick;
    }

    return { render: render };
  }

  /*
   * The countdown. The server sends the time left, and this counts down smoothly between polls. The
   * server only ever shortens a question's time, so the earliest estimate is kept: that stops
   * network jitter making the number flick back up.
   */
  function createClock() {
    var node = el('div', 'countdown');
    var track = el('span', 'countdown-track');
    var fill = el('span', 'countdown-fill');
    var label = el('strong', 'countdown-label');
    var key = null;
    var endsAt = null;
    var allowed = 0;
    var ticker = null;
    track.appendChild(fill);
    node.appendChild(track);
    node.appendChild(label);
    node.hidden = true;

    function draw() {
      var left = Math.max(0, endsAt - Date.now());
      fill.style.width = Math.min(100, left / allowed * 100) + '%';
      label.textContent = Math.ceil(left / 1000) + 's';
      node.classList.toggle('urgent', left <= URGENT_MS);
    }

    function update(party, turnKey) {
      // No clock during the theme flash: it starts when the question shows.
      if (party.stage !== 'answering' || party.millisLeft === null || !party.question) {
        clearInterval(ticker);
        ticker = null;
        key = null;
        node.hidden = true;
        return;
      }
      var nextKey = turnKey + party.question.question;
      var estimate = Date.now() + party.millisLeft;
      endsAt = key === nextKey ? Math.min(endsAt, estimate) : estimate;
      key = nextKey;
      allowed = party.millisAllowed;
      node.hidden = false;
      if (!ticker) { ticker = setInterval(draw, CLOCK_TICK_MS); }
      draw();
    }

    return { node: node, update: update };
  }

  /*
   * One bar per player, highest first, growing left or right of a shared zero line. Rows keep their
   * DOM order and are moved with a transform, so re-sorting slides rather than jumps. onKick is
   * only given on the host page.
   */
  function createScoreboard(onKick) {
    var node = el('div', 'scoreboard');
    var list = el('div', 'score-rows');
    var rows = new Map();
    node.setAttribute('aria-label', 'Scores');
    node.appendChild(list);

    function addRow(player) {
      var row = {
        node: el('div', 'score-row'),
        name: el('span', 'score-name'),
        bar: el('span', 'score-bar'),
        zero: el('span', 'score-zero'),
        value: el('span', 'score-value'),
        result: el('span', 'score-result'),
        kick: null
      };
      var track = el('span', 'score-track');
      track.appendChild(row.bar);
      track.appendChild(row.zero);
      [row.name, track, row.value, row.result].forEach(function (part) { row.node.appendChild(part); });
      if (onKick) {
        row.kick = el('button', 'kick', 'Kick');
        row.kick.type = 'button';
        row.kick.addEventListener('click', function () { onKick(player, row.kick); });
        row.node.appendChild(row.kick);
      }
      list.appendChild(row.node);
      rows.set(player.id, row);
      return row;
    }

    function update(party) {
      var players = party.players;
      node.hidden = !players.length;

      rows.forEach(function (row, id) {
        if (!players.some(function (player) { return player.id === id; })) {
          list.removeChild(row.node);
          rows.delete(id);
        }
      });

      var scores = players.map(function (player) { return player.score; });
      var low = Math.min.apply(null, [0].concat(scores));
      var high = Math.max.apply(null, [0].concat(scores));
      var span = Math.max(high - low, MIN_SCALE);
      function percent(value) { return (value - low) / span * 100 + '%'; }

      var ranked = players.slice().sort(function (a, b) { return b.score - a.score; });
      list.style.height = ranked.length * ROW_PX + 'px';
      ranked.forEach(function (player, rank) {
        var row = rows.get(player.id) || addRow(player);

        row.node.style.transform = 'translateY(' + rank * ROW_PX + 'px)';
        row.node.classList.toggle('current', player.picker || player.tiebreaker);
        row.node.title = player.name + ': ' + player.score + ' points';
        row.name.textContent = (player.picker ? '▶ ' : '') + (player.tiebreaker ? '⚔ ' : '') +
          (party.stage === 'answering' && player.answered ? '✓ ' : '') +
          player.name + (player.you ? ' (you)' : '') +
          (player.streak >= 2 ? ' 🔥' + player.streak : '');
        row.value.textContent = player.score;
        row.result.textContent = player.correct === null ? '' : (player.correct ? '\u2713' : '\u2717');
        row.node.classList.toggle('right', player.correct === true);
        row.node.classList.toggle('wrong', player.correct === false);
        row.zero.style.left = percent(0);
        row.bar.style.left = percent(Math.min(0, player.score));
        row.bar.style.width = Math.abs(player.score) / span * 100 + '%';
        row.bar.classList.toggle('negative', player.score < 0);
        if (row.kick) { row.kick.hidden = party.stage === 'finished'; }
      });
    }

    return { node: node, update: update };
  }

  function startRoom(options) {
    var code = options.code;
    var mount = options.mount;
    var drawnVersion = -1;
    var wasYourTurn = false;
    var stopped = false;
    var timer = null;
    var partyBoard = null;
    var pollMs = POLL_MS;
    var questionIn = null; // During a theme flash, when to poll for the question.

    function stop(message) {
      stopped = true;
      if (timer) { clearTimeout(timer); }
      mount.textContent = '';
      mount.appendChild(el('p', 'room-status', message));
    }

    function schedule() {
      if (stopped) { return; }
      // Just after the flash ends, so the question shows on every screen at about the same time.
      timer = setTimeout(poll, questionIn === null ? pollMs : Math.min(pollMs, questionIn + 40));
    }

    function poll() {
      if (stopped) { return; }
      fetch('/api/room/' + code + '/state', { headers: { 'Accept': 'application/json' } })
        .then(function (response) {
          if (response.status === 404) {
            stop('This room has closed.');
            return null;
          }
          return response.json();
        })
        .then(function (state) {
          if (state) { render(state); }
          schedule();
        })
        // A failed fetch is usually the server restarting or the phone's Wi-Fi blinking.
        // Keep polling; the next one will pick the game back up.
        .catch(schedule);
    }

    function submit(state, answer) {
      fetch('/api/room/' + code + '/answer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ version: state.version, answer: answer })
      })
        .then(function (response) { return response.json(); })
        // A 409 body is the current state too, so a stale tap just re-syncs the screen.
        .then(function (next) { if (next) { render(next); } })
        .catch(function () { /* the next poll will catch up */ });
    }

    function renderPlayers(state) {
      if (!state.players.length) { return; }
      var list = el('div', 'roster');
      state.players.forEach(function (player) {
        var row = el('div', 'player' + (player.current ? ' current' : ''));
        var label = player.name + (player.you ? ' (you)' : '');
        row.appendChild(el('span', null, label));
        if (state.phase !== 'lobby') {
          row.appendChild(el('span', 'player-cats', player.cleared + ' / ' + player.total + ' cleared'));
        }
        list.appendChild(row);
      });
      mount.appendChild(list);
    }

    function renderQuestion(state) {
      var q = state.question;
      var card = el('fieldset');
      card.appendChild(el('legend', null, q.category + ' (' + q.difficulty + ')'));
      card.appendChild(el('p', 'question-meta',
        'Question ' + q.step + ' of ' + q.steps + ' this turn'));
      card.appendChild(el('p', null, q.question));

      q.answers.forEach(function (answer) {
        if (state.yourTurn) {
          var button = el('button', 'answer', answer);
          button.type = 'button';
          button.addEventListener('click', function () {
            // Disable the whole set immediately: on a phone it is easy to double-tap, and the
            // server would reject the second one as stale anyway.
            Array.prototype.forEach.call(card.querySelectorAll('button'), function (b) {
              b.disabled = true;
            });
            submit(state, answer);
          });
          card.appendChild(button);
        } else {
          card.appendChild(el('div', 'answer readonly', answer));
        }
      });

      mount.appendChild(card);
    }

    function renderFeedback(state) {
      var f = state.feedback;
      var banner = el('div', 'banner ' + (f.correct ? 'ok' : 'bad'));
      banner.appendChild(el('strong', null,
        f.answeredBy + (f.correct ? ' got it right' : ' got it wrong')));
      banner.appendChild(el('p', null, f.question));
      if (!f.correct) {
        banner.appendChild(el('p', null,
          (f.givenAnswer ? 'Answered: ' + f.givenAnswer + ' — ' : '') +
          'the answer was ' + f.correctAnswer));
      }
      mount.appendChild(banner);
    }

    function statusLine(state) {
      if (state.phase === 'lobby') {
        if (!state.players.length) { return 'Waiting for players to join...'; }
        return state.canStart
          ? 'Ready when the host starts the game.'
          : 'Waiting for more players...';
      }
      if (state.phase === 'finished') {
        return state.winner + ' wins!';
      }
      var current = state.players.filter(function (p) { return p.current; })[0];
      // Between questions the server is off fetching the next one, which can take a few seconds
      // when opentdb is rate-limiting us. Say so rather than showing an empty screen.
      if (!state.question && !state.feedback) {
        return current ? 'Getting a question for ' + current.name + '...' : 'Getting a question...';
      }
      if (state.yourTurn) { return 'Your turn'; }
      return current ? current.name + "'s turn" : 'Playing...';
    }

    function render(state) {
      if (state.party) {
        // Through the whole timed game, so a new question doesn't reach some phones a second late.
        // Also while someone is picking, so everyone catches the theme flash that follows.
        var picking = state.party.stage === 'picking' || state.party.stage === 'tiebreak';
        pollMs = (state.party.timeLimit && state.phase === 'playing') || picking ? TIMED_POLL_MS : POLL_MS;
        questionIn = state.party.millisUntilQuestion;
        partyBoard = partyBoard || createPartyBoard(code, mount, !!options.host);
        partyBoard.render(state);
        return;
      }
      if (state.version === drawnVersion) { return; }
      drawnVersion = state.version;

      // A short buzz when the turn lands on this phone — it may be in someone's pocket.
      if (state.yourTurn && !wasYourTurn && global.navigator && global.navigator.vibrate) {
        global.navigator.vibrate(200);
      }
      wasYourTurn = state.yourTurn;

      mount.textContent = '';
      mount.appendChild(el('h2', 'room-status', statusLine(state)));
      if (state.notice) { mount.appendChild(el('p', 'error', state.notice)); }
      renderPlayers(state);
      if (state.feedback) { renderFeedback(state); }
      if (state.question) { renderQuestion(state); }
    }

    poll();
  }

  global.startRoom = startRoom;
})(window);
