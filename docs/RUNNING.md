# Running TriviaQuiz

Prompt to give the user:
Read docs/RUNNING.md for the theme format. Make a theme about <TOPIC> with
10 easy, 10 medium and 10 hard questions, each with 3 wrong answers. Make sure
each has only one right answer and fact-check any you're unsure of. Save it as
a .json file in themes/. Don't show me the questions or answers, don't change
any other files and don't commit.

## 1. Start the app

You need Java 21. If your default Java is older, point `JAVA_HOME` at a Java 21 install. The one
bundled with IntelliJ IDEA Ultimate works.

In PowerShell, from the project folder:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\jbr"
.\gradlew.bat bootRun
```

It's ready when the log says `Started TriviaQuizApplication`. Gradle keeps showing a progress bar
below 100% while the app runs, which is normal. Press Ctrl+C to stop it.

`JAVA_HOME` set this way only lasts for that PowerShell window.

Open <http://localhost:8080>. The home page needs internet, because it loads categories from Open
Trivia DB. Theme Party itself doesn't, so you can also go straight to
<http://localhost:8080/party/host>.

## 2. Add a theme

Theme Party needs at least one theme. Each `.json` file in `themes/` is one theme. To try it out,
save this as `themes/space.json`:

```json
{
  "theme": "Space",
  "questions": [
    {
      "difficulty": "easy",
      "question": "Which planet is known as the Red Planet?",
      "correct_answer": "Mars",
      "incorrect_answers": ["Venus", "Jupiter", "Mercury"],
      "explanation": "Iron oxide, or rust, in its soil makes Mars look red."
    },
    {
      "difficulty": "easy",
      "question": "Which star is closest to Earth?",
      "correct_answer": "The Sun",
      "incorrect_answers": ["Sirius", "Proxima Centauri", "Polaris"],
      "explanation": "The Sun is a star, about 150 million km from Earth."
    },
    {
      "difficulty": "medium",
      "question": "Which planet in the Solar System has the shortest day?",
      "correct_answer": "Jupiter",
      "incorrect_answers": ["Earth", "Saturn", "Neptune"],
      "explanation": "Jupiter spins once in just under 10 hours."
    },
    {
      "difficulty": "medium",
      "question": "What is the largest moon in the Solar System?",
      "correct_answer": "Ganymede",
      "incorrect_answers": ["Titan", "Callisto", "The Moon"],
      "explanation": "Jupiter's moon Ganymede is even wider than the planet Mercury."
    },
    {
      "difficulty": "hard",
      "question": "Which planet does the moon Triton orbit?",
      "correct_answer": "Neptune",
      "incorrect_answers": ["Uranus", "Saturn", "Jupiter"],
      "explanation": "Triton is Neptune's largest moon, and orbits against the planet's spin."
    },
    {
      "difficulty": "hard",
      "question": "Which lander made the first soft landing on a comet?",
      "correct_answer": "Philae",
      "incorrect_answers": ["Huygens", "Beagle 2", "Schiaparelli"],
      "explanation": "Philae, released by Rosetta, landed on comet 67P in November 2014."
    }
  ]
}
```

For your own themes:

- Include at least 2 `easy`, 2 `medium` and 2 `hard` questions, with those words in lowercase.
- Give every question all five fields. Leave out `used`: the app adds `"used": true` after a question
  has been answered, and **Reset used questions** on the host page (only on the computer running the app) removes it again.
- Don't repeat a question in a file, or an answer within a question.
- New and edited files don't need a restart. Click **Refresh themes** on the host page.
- Broken files are skipped, and the host page says why.
- **Remove** on the host page takes a theme out by moving it to `themes/removed/`. Move the file
  back into `themes/` to restore it. The button only shows on the computer running the app.

## 3. Play Theme Party

1. On the computer, open <http://localhost:8080/party/host>.
2. On each phone, on the same Wi-Fi, open the address shown on that page and enter a name (for
   players elsewhere, see [Play online with Cloudflare](#4-play-online-with-cloudflare)). You need
   at least 2 players, and nobody can join after the start.
3. Choose how many rounds of each difficulty (0 to 20) and whether to tick **Random themes**,
   **Bonus needs a second correct answer** and **Time limit**, then click **Start game**. They are locked until the next game.
4. The default is 6 rounds: no easy, 3 medium, then 3 hard. Rounds always go from easiest to
   hardest. In each round every player takes one turn.
5. On your turn you pick a theme (with **Random themes** on, from 8 random ones), and everyone answers one question from it. An answer can't be changed.
   With **Time limit** on, the clock starts at 4 seconds per player and each answer takes 2 seconds
   off, but can't push it below 3. Without it there's no limit.
6. When everyone has answered, or time runs out, the answer and explanation show. Any player can tap **Next** to move
   everyone on.
7. After the last round, the highest score wins. Ties share the win.

The scores are always on screen. On the host page each player has a **Kick** button, for when
someone has stopped answering. **Leave game** removes that player straight away. If only one player
remains, the game ends.

**Play again (keep players)** returns everyone to the lobby.

### Testing on one computer

Each player needs a separate browser session:

- Host: a normal browser window at `/party/host`.
- Player 1: a private window at `http://localhost:8080/j/CODE`.
- Player 2: a different browser at the same address.

Replace `CODE` with the room code from the host page. Two private windows in the same browser
usually share a session, so they count as one player.

### Scoring

For the picker:

- If right, plus the number of players in the game, counting the picker. With the bonus setting
  ticked, this only counts if at least one other player is right too.
- -1 for each other player who is right, even if the picker is wrong.

For everyone else: +1 if right, whether or not the picker was.

For example, with 4 players, if the picker and one other are right, the picker gets 4 - 1 = 3 and
the other gets 1. Someone who has been kicked or has left doesn't count towards the number of players.

## 4. Play online with Cloudflare

To play with people who aren't on your Wi-Fi, a free Cloudflare Tunnel gives the app a public
`https://` address. It works without a Cloudflare account and without opening any ports on your
router.

### Install cloudflared (once)

```powershell
winget install --id Cloudflare.cloudflared
```

Or download the Windows `.msi` from
<https://github.com/cloudflare/cloudflared/releases/latest>. Open a new PowerShell window afterwards
so the `cloudflared` command is found.

### Host a game online

You need two PowerShell windows.

1. **Window 1: start the tunnel.**

   ```powershell
   cloudflared tunnel --url http://localhost:8080
   ```

   After a few seconds it prints a box like this. Copy the address:

   ```
   |  Your quick Tunnel has been created! Visit it at:  |
   |  https://some-random-words.trycloudflare.com       |
   ```

2. **Window 2: start the app with that address.** From the project folder:

   ```powershell
   $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\jbr"
   .\gradlew.bat bootRun --args="--trivia.public-url=https://some-random-words.trycloudflare.com"
   ```

   Use your own address from step 1, starting with `https://` and with no `/` at the end.

3. On the computer, open <http://localhost:8080/party/host> (or <http://localhost:8080/host>). The
   first join address listed is the online one. Send it to your friends. They open it in any
   browser and enter a name, as in section 3.

4. When you're done, press Ctrl+C in both windows. The address stops working as soon as the tunnel
   stops.

Each time you start `cloudflared` this way you get a new random address, so repeat steps 1 and 2
with the new one. People on your Wi-Fi can still use the local addresses at the same time.

### What people online can and can't do

To keep your computer safe, anyone coming in through the tunnel can only join a game, play, chat
and leave. Everything else answers "This page isn't available online", including:

- the host pages and their buttons: starting, kicking, closing, and removing or resetting themes
- the home page, single player, the pass-the-device game and the question list

So host from the computer running the app, at `localhost`. Some other tips:

- Only share the join link with people you want in the game.
- Don't leave the tunnel running when you're not playing.
- Online players with a slow connection may be a little behind, which matters for the
  first-correct point.

### If online play doesn't work

| Problem | Fix |
|---|---|
| `cloudflared` isn't recognised | Open a new PowerShell window after installing, or reinstall it |
| The host page doesn't show the online address | Start the app with `--trivia.public-url=...` as in step 2, then reload the host page |
| Friends get an error page from Cloudflare | Check the app is running and says `Started TriviaQuizApplication`, and that the tunnel points at the right port (8080, or 8081 if you changed it) |
| Friends see "This page isn't available online" | They opened something other than the join link. Send them the full `.../j/CODE` address |
| The address stopped working | The tunnel was closed or restarted. Start it again and use the new address |

## Other modes

- Single player: choose a category on the home page and click **Start quiz**.
- Networked game: `/host`
- Pass-the-device game: `/multiplayer`

These get their questions from Open Trivia DB, so they need internet.

## Run the tests

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\jbr"
.\gradlew.bat test
```

One test calls Open Trivia DB, so it fails without internet.

## If it doesn't work

| Problem | Fix |
|---|---|
| Gradle complains about the Java or JVM version | Set `JAVA_HOME` as in step 1, in the same window |
| Port 8080 is in use | Run `$env:SERVER_PORT = 8081` before starting, and use 8081 in the addresses |
| Phones can't open the address | Allow Java through Windows Firewall, use the same Wi-Fi and avoid guest networks |
| The host page lists several addresses | Try each one. VPNs and virtual adapters add extras |
| A theme doesn't appear | Check that it ends in `.json` (not `.json.txt`) and is in `themes/`, then refresh |
| The game disappeared | Rooms are in memory, so a restart clears them. Idle rooms are removed after 2 hours |
| The game stops when the computer sleeps | Keep the computer awake while hosting |
