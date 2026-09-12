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

Browses the folder you granted access to, through Android's Storage Access Framework. Nothing
here is moved, renamed or modified. Only three things in Listea write to storage at all:
**Settings → File management**, which deletes and only after you confirm it; **Save** in any
viewer's Info sheet, which copies a file out into Listea's own gallery album; and **Share** beside
it, which stages a copy in Listea's own cache to hand to another app. All three leave the original
exactly where it was.

Every folder shows its relationship to a List:

| Type        | Meaning                                                           |
|-------------|-------------------------------------------------------------------|
| **None**    | No List covers this folder.                                       |
| **List**    | This exact folder owns a List.                                    |
| **Sublist** | An ancestor folder's List already covers this folder's files.     |

From the current folder you can refresh it, create a List for it, open the List that owns it, or
start a Quick Review of just the files sitting directly in it. Tapping a file opens the gallery
viewer; tapping a subfolder navigates into it. Each file shows whether it is checked, whatever it
was checked from.

Quick Review is offered on **every** folder — the type above is about Lists, and reviewing a folder
does not need one.

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
- **Pinch** — look closer, up to 5×, then drag to move around inside the picture.
- **Bottom bar** — completion checkbox, ★ Favourite, however many custom actions you have
  configured, and Info.
- **Info** — the full filename, path, checked and tag state, file type, size and modified time,
  plus the owning List when the queue came from one. At the bottom, **Save to Listea** and
  **Share**.
- **Save to Listea** — copies the file you are looking at into a `DCIM/Listea` album, which your
  phone's gallery app shows as an album called *Listea*. Offered on every viewing surface: full
  Review, Quick Review, the plain file viewer and the list-item viewer. It is not a tag and not
  an action — it takes effect immediately, is not stored on the item, and no webhook hears about
  it. Saving the same file twice says so instead of making a second copy.
- **Share** — hands the file to whatever else is on the phone, through Android's own chooser.
  Offered on the same four surfaces, beside Save, and just as detached from the item: nothing is
  tagged, checked or delivered by sharing. Listea copies the file into its own cache first and
  shares that through a `FileProvider`, because a SAF document URI handed straight to another app
  is read by some receivers and quietly refused by others. What is in the chooser is whatever the
  phone has installed and differs from device to device — Listea names no app and promises no
  destination. Copies are swept up on the next share, a day after they were made.

Images (JPEG/PNG/WebP), animated GIFs and video all render in place; video plays through Media3,
with a transport that comes up and goes away with the rest of the chrome on a tap. There is no
fullscreen button — the picture is already the whole screen.

**Zoom** is a pinch, on all four viewing surfaces and on everything they show — stills, GIFs and
video alike. It goes to 5× and no further, which is where a photograph stops being detail and
starts being the decoder's guesswork. The picture zooms about the point between your fingers, so
whatever you were looking at stays under them.

Zooming and paging are **not two modes**, and there is nothing to switch between them. Every drag
is offered to the picture as panning first, and only what the picture has no room for is left to
move the card — so at fitted size a swipe pages exactly as it always did, and zoomed in the card
does not begin to move until you have panned to the picture's edge. Carrying on from there turns
the page, which is the same gesture you already use. A pinch never turns a page, however far it
wanders. Vertically there is nowhere to page to, so a zoomed picture simply stops at its top and
bottom.

The zoom belongs to the file you are looking at and is dropped when you leave it: every item
arrives at its own fitted size, however closely you were looking at the last one. Rotating the
phone keeps the magnification and pulls the picture back into view rather than leaving half of it
stranded off screen.

On video the transform reaches the picture and stops there — the transport controls sit in the
same box and would otherwise be dragged off screen with it, and a press Media3 has already taken
never becomes a pan.

**The video transport** is one strip along the bottom edge, directly above the action bar:
play/pause, the position and length, a scrubber, playback speed, and mute. Nothing else. It is
Listea's own rather than Media3's `PlayerControlView`, and that is the whole reason it looks like
this.

Media3's control view dims **every pixel of the surface it is given** and lays its buttons down
the middle of it, so reaching the pause button meant blacking out the thing you were watching — a
video you could control or a video you could see, but not both. It also silently drops its
transport row, scrubber and settings button whenever it is laid out shorter than 192dp, which is
roughly what a landscape phone has left once two bars have taken their share, so half the controls
came and went with the orientation. A strip of Listea's own is as tall as it draws, dims only
itself, and is the same strip in both orientations.

Tapping the strip anywhere that is not a control does nothing, rather than counting as the tap on
the media that puts the chrome away: aiming at pause and missing by a few pixels should not be
read as asking for the controls to leave.

**Speed** is a menu behind its own label — 0.5× to 2× — rather than a button that cycles, so 2× is
not four taps away from 0.75×. It belongs to the player, so it stays put across a swipe and
resets when you leave the screen.

**Mute** belongs to the **file, not the player**. Unmute one video and the next one still arrives
muted, because *Start videos muted* is a rule about arriving at a video rather than a mode you
have to keep switching off — so it stays true for every file except the one you decided about.
Turn the setting off and every video simply arrives audible. How loud that is remains the phone's
volume keys' business; Listea has no second volume of its own to get out of step with them.

**Quick Review** is the same screen over one browsed folder's own files. It resolves the folder
directly, so it works on any folder, whether or not a List covers it — and it creates nothing,
replaces nothing and consults no List. It always starts at the first unchecked item and never
remembers where it got to.

**Rounds.** With *Review unchecked items only* on, opening either review queues only the items
that were unchecked at that moment, and keeps that queue for the round: an item you check stays in
front of you and stays swipeable backwards. Leaving and re-entering starts the next round. Pair it
with *Send checked items only* and each round's webhook carries exactly the decisions you just
made, however many rounds it takes to get through a folder.

**File Viewer** is the same screen with no List semantics at all — a plain gallery that pages
between sibling files in a folder and changes nothing.

### Where checked state lives

**A decision belongs to the file, not to the List you happened to make it from.**

Checked, ★ and whichever custom actions an item carries are stored against the file's identity —
the SAF root it is under, plus its path beneath that root — in a table of their own. A List stores
*membership*: which files it covers, in what order. It projects the shared state rather than
owning a copy.

That is what makes all of this true at once:

- Quick Review needs no List, because it has somewhere to put a decision either way.
- Checking a file in a folder's Quick Review completes the List that covers it, and fires that
  List's webhook, without Quick Review knowing the List exists.
- Building a second List over the same files shows the same ticks. Nothing is copied, so nothing
  can diverge.
- Deleting a List discards its membership, not its decisions. Rebuilding it brings them back.
- Nothing resets state as a side effect. Creating a List or opening a Quick Review never clears a
  tick; re-reviewing checked files is what *Review unchecked items only* is for, and a genuine
  "start fresh" would have to be its own explicit action.

Manually typed items are the exception, and deliberately so: they have no file to be a decision
about, so their state stays on their own row exactly as it always did.

### Webhooks

Each List can POST a JSON body when it transitions from incomplete to complete:

```json
{
  "event": "list.completed",
  "list": { "id": 1, "title": "2026-07-11", "completedAt": 1767100000000 },
  "items": [
    {
      "id": "0AT9K3QWMB-4H7ZP2-XC5N0V",
      "title": "a.jpg",
      "isCompleted": true,
      "relativePath": "2026-07-11/bilibili",
      "actions": ["favorite", "cust1"]
    }
  ]
}
```

`isCompleted` says the item was processed in Listea. `actions` says what downstream automation
should do with it. Neither implies the other — checking something off is not an action.

`id` is a 24-character public id, assigned when the item is created and never changed after that.
It is safe to use as a primary key on the receiving side:

```
0AT9K3QWMB-4H7ZP2-XC5N0V
└────┬───┘ └──┬─┘ └──┬─┘
   time     file    random
```

- **time** — milliseconds since the epoch, base32, fixed width. Sorting the ids **as strings**
  sorts them by when the item arrived; no parsing needed.
- **file** — a fingerprint of the file's path under the sync root, so two arrivals of the same
  file share this field. A hint for grouping, not a guarantee.
- **random** — 30 bits, and what actually makes the id unique.

The alphabet is Crockford base32 (no `I`, `L`, `O` or `U`), so an id copied out of a log by hand
cannot be misread.

The same file entering Listea again — a new List over the same folder, a re-sync that re-adds it
after it went missing — gets a **new** id. An id names one *arrival* of a file, not the file
itself, so a receiver can keep both records without either overwriting the other. Two arrivals of
one file are recognisable by their matching middle field, or by `relativePath` + `title`.

> Before v10 this field was the SQLite row number (`"id": 12`). That number restarted at 1 on a
> reinstall and was drawn from two different sequences depending on which review mode sent it, so
> it was never safe to key on. It is no longer on the wire at all. Existing items were given ids
> at upgrade, timestamped from when they were actually created.

`relativePath` is the item's *folder* relative to the sync root, without the file name — `title`
already carries that, so join the two to get the whole path. It is empty for an item sitting
directly in the root, and null for a manual item with no source file.

A round finished in a folder's Quick Review sends the same shape, with the folder standing in for
the list: `list.id` is `0` and `list.title` is the folder's name, because no List was involved.

The wire values in `actions` are configurable in Settings and are resolved at delivery time, so
renaming one changes future payloads without touching any stored item. There is a *Test webhook*
button that sends the same shape with `"event": "list.webhook.test"`.

Reviews can deliver on their own, which is off until you switch it on:

| Event                     | Fires when                              | Posts to             | Items                |
|---------------------------|-----------------------------------------|----------------------|----------------------|
| `list.completed`          | A List goes from incomplete to complete | The List's URL       | The whole List       |
| `list.webhook.test`       | The *Test webhook* button               | The List's URL       | The whole List       |
| `quickreview.completed`   | A Quick Review queue is finished        | The **default** URL  | That folder's round  |
| `review.exited`           | A review round ends, finished or not    | See below            | That review's round  |

`quickreview.completed` needs *Send from Quick Review*. Quick Review has no webhook configuration
of its own and is not getting one — it posts through the **default webhook**, whole: the default
URL is where it goes and *Default enabled* is what switches it on. Anything missing is reported in
the same dialog any other delivery problem is, rather than passing silently. Quick Review still
fires a List's own `list.completed` if a decision made in it happens to complete that List —
without knowing the List is there, because completion is recomputed from the file state that both
of them read.

`review.exited` needs *Send when leaving a review*. Full Review uses the List's own webhook, so a
List with its webhook switched off says so; Quick Review uses the default webhook and also needs
*Send from Quick Review*. A queue that finished on screen has already sent and does not send again
on the way out, and leaving a review that had nothing to queue sends nothing at all.

**A round is reported once.** `list.completed` and `review.exited` go to the same webhook for a
List review, so a round that completes its List would otherwise describe the same swipes twice,
seconds apart. The round's own report is the one that survives, and the completion stands down.

The round's is the narrower and more honest of the two: with *Review unchecked items only* and
*Send checked items only* both on, `review.exited` carries exactly the decisions you just made,
while `list.completed` would carry the whole List — including items checked weeks ago and already
sent. Applying that narrowing to whichever event fires, rather than only to some of them, is the
point of having the switches at all.

This only applies while *Send when leaving a review* is on. With it off there is no second payload
to prefer, so `list.completed` fires exactly as it always has — it has never depended on any of the
review switches, and still does not. **A receiver that watches specifically for `list.completed`
should watch for `review.exited` too once that switch is on.**

*Send checked items only* narrows every payload, the test one included, to the items that are
checked.

**Nothing is ever posted with an empty `items` array.** A usable webhook with nothing to carry — a
review left without a single swipe, a round where nothing was checked, a List that emptied itself
— reports *Nothing to send* instead. `"items": []` would tell a receiver a round happened when
none did. This holds for every event, the test webhook included, so testing an endpoint against a
List with nothing checked reports rather than posts.

Every delivery is announced and recorded against the List it concerns, so a List's *Last delivery*
line covers the ones sent through the default webhook too. One swipe can finish a review *and*
complete the List it belongs to, which is two deliveries against two different configurations:
each is announced in turn rather than overwriting the other, except that two webhooks found
switched off in the same moment are reported once.

#### Nothing goes out without you saying so

**Every automatic delivery asks first.** A webhook fired off the back of a swipe was the one thing
in Listea that left the device without anyone asking, which is a lot to hang on a gesture whose
whole job is to be fast. So `list.completed`, `review.exited` and `quickreview.completed` now put
up a dialog — what event, which List, how many items, which webhook — and post only on **Send**.

- **Not now** sends nothing and **changes nothing else**. No item is checked or unchecked by
  answering either way; completion state is written when you swipe, not when you answer this.
- A declined payload is **kept in the webhook history**, exactly as a failed one is, so refusing
  here costs nothing — send it later from Settings whenever the receiver is ready.
- Tapping outside the dialog counts as *Not now*. The safe reading of an unanswered question is
  that nothing should leave the device.
- The question comes *after* the checks, so it only ever appears when something really would be
  posted. A switched-off webhook or an unusable URL reports as it always did, without asking.

The two deliveries you press a button for — **Test webhook** and **Resend** — do not ask. Pressing
the button is the confirmation.

**Every send shows its progress.** A round can be hundreds of items and a receiver can be slow, so
between pressing send and hearing back there is now a *Sending…* dialog naming what is on the wire.
It covers all four paths — an automatic delivery, the test button, leaving a review, a resend from
the history — and clears itself the moment the result lands, whatever the result is.

#### Webhook history

Settings → Webhook → *Webhook history*. **Every webhook Listea builds is kept with its payload and
what became of it** — sent, failed, refused by a switched-off webhook, or declined when it asked.
It used to keep only failures, which turned out to be the wrong shape: *did that round actually go
out?* is the question people have, and a page that answers it only when the answer is no is a page
you cannot trust.

The one thing not kept is a payload with no items in it. There would be nothing to resend, and
nothing was attempted.

Each record is named by the moment it last reached a verdict and says what event it was, which
List, how many items, and how it went. Tapping one shows the exact JSON that was posted or would
have been; scroll it, and close. Each record has two actions:

- **Resend** posts the stored body, byte for byte, to the default URL. It asks only that a URL is
  filled in — not that *Default enabled* is on, since that switch is one of the things that might
  have gone wrong in the first place. The record is updated in place with the new verdict rather
  than a second one appended: the history is a list of payloads and what became of each, not a log
  of every attempt at one. Offered on successful records too — sending a round again is a thing
  people legitimately want.
- **Delete** drops it, immediately and for good. It is the only thing in the app that can lose a
  payload.

The Settings line counts both: how much history there is, and how much of it a receiver has still
not accepted. Only the second is worth a warning.

Records carry no link back to their List, on purpose: deleting a List must not quietly take the
history of what it sent with it.

### Settings

Root folder and what is in it, Quick Review integration, the custom actions — add, rename, retag
or remove them, each with a display name and a wire value — default webhook values copied into
newly created Lists, video autoplay and start-muted (where each file starts, which the transport's
mute button overrides for that file alone), whether full Review resumes where it left off, whether
reviews queue only unchecked items, whether opening a List automatically checks its source folder
for changes, the three webhook switches above, the way into the webhook history, and file
management.

The Storage card reports the root folder together with what a walk of it finds: how many folders,
how many files, and how much they take up. It is counted the same way a List's scan counts, so the
two can never disagree about what the root holds.

Every switch added after a feature shipped defaults to off, so a fresh install and an untouched
Settings page behave the same as they did before it existed.

#### File management

Settings → File management → *Delete checked files*. **The one thing Listea does that destroys
something outside its own database.** It deletes every checked file under the current root folder
from the device itself, permanently.

Nothing happens without an explicit confirmation, and the confirmation is the full listing rather
than a count: every file is named, grouped under the folder that holds it, and the list scrolls.

```
xxx/yyyy
->file.jpg
xxx/zzz
->file2.txt, file3.mp4
```

Only files Listea still believes exist are offered — checked, not already flagged missing, and
under the current root. It reads the same per-file state everything else does, so a file checked in
a folder no List covers is offered exactly like one checked from a List's page. Each file is
resolved from its path at delete time, one folder listing per folder rather than a stale URI per
file.

Afterwards the decision is kept and the file is flagged missing — both on the file itself and on
any List row pointing at it — so a List whose files you deleted stays complete and says its sources
are missing, exactly as it would after a re-sync. The **Update from folder → remove missing items**
cleanup is there for anyone who wants the rows gone too. Each file is its own provider call, so a partial run is reported as
one rather than pretending to be all-or-nothing.

Deleting needs write access to the root. Android hands that over through the folder picker and
nowhere else — there is no separate "allow file changes" prompt to ask for — so a root granted
before this feature existed can only be read. Listea asks for it in place rather than sending you
to look for it: the delete pauses on **Allow Listea to delete files?**, *Grant access* opens the
picker already inside the folder in use — the initial location is passed as a document URI within
the tree, which is the form the picker actually follows — and confirming it resumes the delete
where it left off. Choosing a *different* folder there is refused rather than acted on, so this can never turn
into a root change and can never delete a List.

#### Ask to delete after webhook

Settings → File management → *Ask to delete after webhook*. Off by default. With it on, a webhook
that has **actually been delivered** is followed by an offer: the files that delivery carried as
checked are still on the device — should they stay there?

The offer follows the *webhook*, not the review, and this is the whole of its semantics. Every
other switch has already had its say by the time it appears: *Review unchecked items only* decided
what the round walked, *Send checked items only* decided what the payload carried. What is offered
is exactly the checked items in the payload that went out, and nothing else — which makes it
narrower than *Delete checked files* above, which covers every checked file under the root however
and whenever it was checked. The dialog says which of the two it is rather than leaving them to be
confused, and lists every file by name, grouped by folder, exactly as the other one does.

A delivery that did **not** go out deletes nothing and offers nothing. Instead it says so, and
says where the round went: it is kept in **Webhook history**, to be resent from there, or the
files can be cleared from **File management** by hand. Declining is free either way — the files
stay, the items keep their decisions, and nothing about the round changes.

It fires for any delivery Listea decided to make on your behalf — a review being left, a Quick
Review queue running out, a List going complete. Not for the *Test webhook* button, and not for a
resend from the history: neither is a round you just finished, and offering to delete files off
the back of a button labelled *Test* would be indefensible.

Deleting from the offer is the same permanent operation as above, with the same write-access
requirement. If the root can only be read, the offer says so and points at File management, which
is the only place that can ask Android for the grant.

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
   work through just one folder's files — that works on any folder, with or without a List, and
   either way the ticks are the same ones. Swipe left to check and move on, right to go back. Tap
   ★ or one of your custom actions to tag an item for later.
5. **Wire up the webhook** *(optional)*. On the List's detail page, expand **Webhook**, switch it
   on and paste a URL. Use **Test webhook** to check the receiver before relying on it. The real
   delivery fires once, when the last item is checked.
6. **Keep it in sync.** If files change on disk, the List's Source card says so. **Update from
   folder** shows exactly what changed and applies it only when you confirm. Checked state
   survives; nothing is deleted unless you ask.

### Notes and limits

- Listea only ever reads the folder you granted. Three things write to storage, and none of them
  moves or renames anything: **Settings → File management → Delete checked files**, which deletes
  permanently and only after you have confirmed the full list; **Save to Listea** in a viewer's
  Info sheet, which creates a copy in `DCIM/Listea`; and **Share**, which stages a copy in
  Listea's own cache for the app you pick. None of them touches the original.
- **Save to Listea** takes only images and video — an album has nothing to do with anything else.
  On Android 9 and below it asks for the storage permission the first time; on Android 10 and up
  it needs no permission at all.
- Changing the root folder in Settings **deletes the Lists linked to the old root**. You're shown
  which ones before anything happens. Manual Lists are always kept.
- Folder-backed Lists cannot overlap: one List's folder can't contain another's.
- Directories are never List items, and never Quick Review items — only files are.
- A file's identity is its path under the root. Replacing a file with a different one of the
  same name, outside the app, hands the new file the old one's checked state.

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
  VideoControls.kt       the video transport: play, seek, speed, mute
  SwipeCard.kt           the one swipe gesture, and where paging and zoom are arbitrated
  MediaZoom.kt           the pinch, the pan, and how far either may go
  SettingsScreen.kt      Settings
  ListsViewModel.kt      all list, folder, freshness and webhook operations
  Webhook.kt             payload construction and delivery
  WebhookHistoryScreen.kt  every kept payload, with resend and delete
  FileManagement.kt      the delete preview, and the deleting itself
  MediaSave.kt           copying a viewed file into the DCIM/Listea gallery album
  MediaShare.kt          staging a viewed file for Android's share chooser
  data/ItemId.kt         the public id an item carries onto the webhook
  QuickReview.kt         resolving a folder into a review queue
  data/                  Room entities, DAO and database
```

## Roadmap

Not implemented, and not currently being worked on: automatic list updating, search, file moving
or renaming, choosing *which* checked files a deletion takes, tablet layouts, and any form of sync
between devices.

## Licence

None yet.
