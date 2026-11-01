# Claude Launcher

An Android home-screen launcher with a built-in **MCP server**, so Claude can organize
your apps into folders for you. You keep using your phone normally; when you want a
reorganization, you flip the server on, talk to Claude, and watch the folders appear.

## Why a launcher?

Android gives no app the ability to rearrange the *stock* home screen (Pixel Launcher,
One UI Home…) — that layout is private to the launcher that draws it. The only
legitimate way to let software organize your icons is to **be the launcher**. This app
registers as a home screen, owns its own grid, and exposes that grid over MCP.

## Safety model — what this can and cannot touch

| Can | Cannot |
|---|---|
| Create, rename, dissolve folders on *this* launcher's home screen | Install, uninstall, disable, force-stop, or update any app |
| Move icons between pages and folders | Change any app's permissions, data, settings, or notifications |
| Hide an icon from *this* home screen (the app stays installed and in the drawer) | Modify the layout of any other launcher |
| Undo the last 50 changes | Read app contents, messages, files, or accounts |

Concretely:

- The only Android API it uses to look at other apps is `LauncherApps` — the same
  read-only enumeration every third-party launcher uses. The only thing it ever *does*
  to another app is launch it when you tap the icon.
- Its layout is a JSON file inside its own private sandbox (`layout.json`, written
  atomically with a rolling backup). Your stock launcher's layout is never read or
  written. **Switching your default launcher back restores your old home screen exactly.**
- Every MCP mutation is a transaction: the engine computes the new layout, validates
  every invariant (no app placed twice, no duplicate folder names, page capacity, …), and
  only then commits. A bad `apply_plan` with one typo rejects the *whole* plan and
  changes nothing.
- The endpoint requires a bearer token (shown in the app, rotatable). Without it every
  request gets `401`. Nothing on your Wi-Fi can rearrange your icons without pairing.
- No tool is annotated `destructiveHint`. Everything is undoable from Claude (`undo`
  tool) or from the launcher's dock (Undo button). Undo history lives in memory for the
  life of the launcher process.

## Project layout

```
core/   Pure Kotlin/JVM: layout model, transactional engine, MCP JSON-RPC server, codec.
        No Android dependency → fully unit-tested on any machine (55 tests, incl. a
        200k-operation randomized fuzz and an 8-thread concurrency test).
        Also contains a desktop dev server so you can try the connector before installing.
app/    Android launcher (Jetpack Compose) + file-backed store + Ktor MCP endpoint
        running as a foreground service.
```

## Build

Requirements: Android Studio Ladybug or newer (bundled JDK 17+), Android SDK 35.

```bash
# Run the core test-suite (no device needed)
./gradlew :core:test

# Build a debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Install on a USB-connected phone (USB debugging on)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you don't have the Gradle wrapper yet: `gradle wrapper --gradle-version 8.10` in the
project root, or open the folder in Android Studio and let it generate one.

On a machine without network access to Maven Central, `core/jvm-build.sh` compiles and
tests `:core` using only the jars bundled inside a Gradle distribution.

## First run on the phone

1. Install the APK. Open **Claude Launcher** from your current home screen *once* so
   Android knows it exists (it starts with an empty home screen; everything is in the
   **Apps** drawer at the bottom).
2. Tap **Claude ○** in the dock → **Start MCP server**. Android will ask to allow
   notifications (Android 13+); that's for the "server running" notification.
3. The sheet shows the endpoint (`http://<phone-ip>:8765/mcp`) and the bearer token.
4. Optional: make it your default home screen (Settings → Apps → Default apps → Home
   app). You can switch back any time; nothing is lost either way.

Gestures on the home screen:

- Tap an icon → open the app. Long-press → remove from home screen (stays in drawer).
- Tap a folder → open it. Long-press → dissolve it (apps return as loose icons).
- Inside a folder: long-press an app → move it out. **Rename** / **Dissolve** buttons.
- **Apps** → drawer with search. Long-press an app → add to home screen. Green dot = already placed.
- **Undo** → revert the last change, whoever made it (you or Claude).

## Connecting from Claude

Claude's custom connectors need an **HTTPS URL they can reach**; a LAN address won't do.
Two easy options:

**Tailscale Funnel** (simplest; keeps the phone off the public internet except via Tailscale):

```bash
# on the phone's Tailscale node, or any node that can reach the phone's Tailscale IP
tailscale funnel --bg --set-path=/mcp http://<phone-tailscale-ip>:8765/mcp
```

**Cloudflare Tunnel** (`cloudflared tunnel --url http://<phone-ip>:8765`) from a laptop on
the same Wi-Fi also works and gives you a `*.trycloudflare.com` URL.

Then in Claude → Settings → Connectors → **Add custom connector**:

- URL: `https://<your-tunnel-host>/mcp`
- Authentication: bearer token → paste the token from the app

Ask Claude: *"List my apps and organize them into sensible folders."* It will call
`list_apps`, propose a plan, and call `apply_plan`. The folders appear on the phone
instantly. Don't like it? *"Undo that"* or press Undo on the phone.

### Try it before building the APK

The same MCP server runs on your laptop with a fake phone:

```bash
./gradlew :core:devServer --args="8765 my-secret-token"
curl -s -H "Authorization: Bearer my-secret-token" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' http://127.0.0.1:8765/mcp
```

Point a tunnel at it, add it as a connector, and you can test the whole Claude flow
without touching the phone.

## MCP tools

| Tool | Purpose |
|---|---|
| `list_apps` | Every installed app with label, package, component, and current placement |
| `get_layout` | Full page/folder structure, revision, undo depth |
| `apply_plan` | Atomic bulk organization: `{folders:[{name, apps}], pin_to_top_level, replace_existing}` |
| `create_folder` / `rename_folder` / `dissolve_folder` | Folder lifecycle |
| `add_to_folder` / `remove_from_folder` | Move apps in/out of folders |
| `place_app` / `hide_app` | Add or remove a loose icon on the home screen |
| `add_page` / `remove_page` | Page management (only empty pages can be removed) |
| `show_all_apps` | Place everything not yet on the home screen |
| `undo` | Revert the last change (50-deep) |
| `safety_info` | What this server can and cannot do |

Apps can be referenced by package name (`com.spotify.music`), by `package/activity`
component, or by exact label (`Spotify`) — ambiguous labels are rejected with the
candidates listed.

Transport: MCP **Streamable HTTP**, stateless. `POST /mcp` with one JSON-RPC message;
notifications return `202`; `GET /mcp` → `405` (no server-initiated streams).
`GET /health` is unauthenticated and returns the current revision.

## Known gaps / next steps

- Icons are a simple grid with no drag-and-drop; placement is via long-press and via Claude.
- No widgets, no wallpaper picker (system wallpaper shows through), no app shortcuts.
- The `:app` module was written without an Android SDK available in the authoring
  environment, so the first `assembleDebug` may surface a compile nit or two in the
  Compose/Ktor glue. `:core` — all the logic that matters — is fully tested.
- Undo history isn't persisted across launcher restarts (the layout itself is).
