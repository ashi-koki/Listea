# Listea

An Android app for working through a folder of media, one file at a time.

Point Listea at a folder on your device, turn it into a checklist, and review the contents by
swiping — marking each file as done and optionally tagging it for whatever happens next. When a
list is finished, Listea can POST a summary of it to a webhook, which is what makes the tagging
useful: the sorting happens on the phone, and the acting-on-it happens wherever you want.

> **Status: work in progress.**
> This is an in-development personal project. It builds and runs, and the features described below
> work, but nothing here is stable — the database schema, the settings and the interface all still
> change between versions, and there is no migration story yet. Expect rough edges, and don't point
> it at anything you can't afford to have a bug touch.

---

## What it does

Listea has three top-level places, reached from the bottom navigation bar.

### Folder

Browses the folder you granted access to, through Android's Storage Access Framework. Files are
never copied, moved or modified — Listea only reads.

Every folder shows its relationship to a List:

| Type        | Meaning                                                           |
|-------------|-------------------------------------------------------------------|
| **None**    | No List covers this folder.                                       |
| **List**    | This exact folder owns a List.                                    |
| **Sublist** | An ancestor folder's List already covers this folder's files.     |

From the current folder you can refresh it, create a List for it, open the List that owns it, or
start a Quick Review of just the files sitting directly in it. Tapping a file opens the gallery
viewer; tapping a subfolder navigates into it.

Lists may not overlap, so creating one where another already reaches is a replacement, and always
asks first.

### Lists

Every List, filterable by All / In progress / Completed, showing numeric progress and webhook
state. Opening one gives you its detail page:

- **Source** — which folder it came from, whether that folder has changed since the List was
  built, and the manual *Update from folder* that reconciles the two. Files that vanished stay in
  the List, flagged, keeping their checked state, until you choose to clear them.
- **Progress** — the count, a bar, the way into Review, and the List's webhook settings.
- **Items** — check, edit, tag or delete individual items.

Lists can also be created by hand, with no folder behind them at all.

### Review

The media-first surface. One file fills the screen; a thin bar gives you back, the filename and
your position in the queue.

- **Swipe left** — mark the current item checked and advance.
- **Swipe right** — go back, changing nothing.
- **Bottom bar** — completion checkbox, ★ Favourite, two configurable custom actions, and Info.
- **Info** — the full filename, path, owning List, checked and tag state, file type, size and
  modified time.

Images (JPEG/PNG/WebP), animated GIFs and video all render in place; video plays through Media3
with its own controls, hidden until you touch the screen, and a fullscreen button.

**Quick Review** is the same screen over a narrower queue: the files sitting directly in one
browsed folder, backed by the real items of whichever List owns it. Edits made there are edits to
that List. It always starts at the first unchecked item and never remembers where it got to.

**File Viewer** is the same screen with no List semantics at all — a plain gallery that pages
between sibling files in a folder and changes nothing.

### Webhooks

Each List can POST a JSON body when it transitions from incomplete to complete:

```json
{
  "event": "list.completed",
  "list": { "id": 1, "title": "2026-07-11", "completedAt": 1767100000000 },
  "items": [
    {
      "id": 12,
      "title": "a.jpg",
      "isCompleted": true,
      "relativePath": "2026-07-11/bilibili/a.jpg",
      "actions": ["favorite", "cust1"]
    }
  ]
}
```

`isCompleted` says the item was processed in Listea. `actions` says what downstream automation
should do with it. Neither implies the other — checking something off is not an action.

The wire values in `actions` are configurable in Settings and are resolved at delivery time, so
renaming one changes future payloads without touching any stored item. There is a *Test webhook*
button that sends the same shape with `"event": "list.webhook.test"`.

### Settings

Root folder, Quick Review integration, the two custom actions (display name and wire value),
default webhook values copied into newly created Lists, video autoplay and start-muted, whether
full Review resumes where it left off, and whether opening a List automatically checks its source
folder for changes.

---

## Environment

| | |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (BOM 2026.02.01), Material 3 |
| Build | Android Gradle Plugin 9.3.0, Gradle wrapper, KSP |
| Min SDK | 24 (Android 7.0) |
| Compile / target SDK | 37 |
| Java | 11 |

Libraries: Room 2.8.2 for persistence, DataStore Preferences for settings, `documentfile` for SAF
access, Media3 (ExoPlayer) 1.8.0 for video, Coil 3.4.0 for images and animated GIF/WebP.

The only permission is `INTERNET`, used solely for webhook delivery. Folder access is granted by
you through the system folder picker and is not a runtime permission.

## Building

You need Android Studio (or a standalone Android SDK) with API level 37 installed.

```bash
git clone https://github.com/ashi-koki/Listea.git
cd Listea

# Point the build at your SDK — this file is gitignored and is not committed.
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug        # build
./gradlew installDebug         # build and install on a connected device
./gradlew testDebugUnitTest    # run the unit tests
```

Opening the project in Android Studio and pressing Run works too; Studio writes
`local.properties` for you.

## Using it

1. **Grant a folder.** On first launch the Folder tab has nothing to show. Go to **Settings →
   Storage → Select folder** and pick a folder through the system picker. Listea keeps the grant
   across restarts.
2. **Browse to something worth reviewing.** Navigate into subfolders from the Folder tab. The
   breadcrumb at the top walks back up.
3. **Create a List.** In the folder you want, tap **Create List**. Listea scans that folder and
   everything below it and builds a List from the files it finds. If another List already covers
   the same ground, you'll be asked to confirm the replacement first.
4. **Review it.** Open the List and tap **Review**, or use **Quick Review** from the Folder tab to
   work through just one folder's files. Swipe left to check and move on, right to go back. Tap
   ★ / C1 / C2 to tag an item for later.
5. **Wire up the webhook** *(optional)*. On the List's detail page, expand **Webhook**, switch it
   on and paste a URL. Use **Test webhook** to check the receiver before relying on it. The real
   delivery fires once, when the last item is checked.
6. **Keep it in sync.** If files change on disk, the List's Source card says so. **Update from
   folder** shows exactly what changed and applies it only when you confirm. Checked state
   survives; nothing is deleted unless you ask.

### Notes and limits

- Listea never writes to your files. It reads the folder, stores its own list, and sends webhooks.
- Changing the root folder in Settings **deletes the Lists linked to the old root**. You're shown
  which ones before anything happens. Manual Lists are always kept.
- Folder-backed Lists cannot overlap: one List's folder can't contain another's.
- Directories are never List items — only files are.

## Project layout

```
app/src/main/java/me/ashikoki/listea/
  MainActivity.kt        app shell, top-level navigation, the Folder screen
  AppShell.kt            shared scaffolds and the shared spacing scale
  ManagementUi.kt        shared pieces for Folder / Lists / List Detail
  MediaUi.kt             shared media shell, review action bar, Info sheet
  ListsScreen.kt         the Lists index
  ListDetailScreen.kt    one List: source, progress, items
  ReviewScreen.kt        full Review, and the shared review session
  QuickReviewScreen.kt   folder-scoped Review over the same session
  FileViewerScreen.kt    the plain gallery
  MediaPreview.kt        the one renderer: images, GIF, video
  SwipeCard.kt           the one swipe gesture
  SettingsScreen.kt      Settings
  ListsViewModel.kt      all list, folder, freshness and webhook operations
  Webhook.kt             payload construction and delivery
  data/                  Room entities, DAO and database
```

## Roadmap

Not implemented, and not currently being worked on: automatic list updating, search, sharing,
file deletion or moving, image zoom, tablet layouts, and any form of sync between devices.

## Licence

None yet.
