# ADR-0020: Record into MediaStore's Movies collection, not the app's private directory

**Status:** Proposed
**Date:** 2026-09-05
**Deciders:** Davide Mendolia
**PRD sections:** 6.7, 3 (non-goals: "Files land in the device's camera roll or Movies folder")
**Related ADRs:** [ADR-0002](0002-android-capture-stack.md), [ADR-0003](0003-foreground-service-for-capture-and-server.md)

## Context

Takes are written to `getExternalFilesDir(null)` — `Android/data/com.scenaristo.camera/files/`. That was the shortest path to a working recorder and nothing has revisited it since.

It is wrong in three ways that all point the same direction, and none of them is subtle:

1. **The files are invisible.** Nothing scans that directory, so a finished take does not appear in Google Photos, in the gallery, in a file picker, or over MTP when the phone is plugged into a laptop. The user has recorded something they cannot find.
2. **The files are deleted when the app is uninstalled.** Android removes `Android/data/<package>` with the package. A creator who uninstalls to reinstall loses every take, silently.
3. **It contradicts what the product already promises.** PRD 6.7's crash criterion says the partial file "appears in the camera roll or Movies folder with the normal filename", and PRD section 3 says files "land in the device's camera roll or Movies folder". The current behaviour matches neither.

Point 3 makes this a defect rather than a design question. What is still open is *which* of the two the PRD names, because it names both.

## Decision

**Revised 2026-09-05 by Davide, before acceptance.** The first draft of this ADR made MediaStore the only destination. It is now the opt-in, and the default is the app's own folder.

We will:

1. **Record into the app's external files directory by default** — `Android/data/<package>/files/Movies/`, via `FileOutputOptions`. This is what an ordinary app does with its own files: nothing else on the phone sees them, the gallery does not index them, and no permission is involved.
2. **Offer the shared gallery as a setting.** `saveToGallery` on `CaptureSettings` and `SettingsPatch` — additive, so no protocol break — switches the recorder to `MediaStoreOutputOptions` under `Movies/Scenaristo Camera/`. It persists like the other settings.

The reason the default flipped: **filling a creator's shared storage with multi-gigabyte files is not a choice to make on their behalf.** A take is 250 MB a minute (#21). An app that quietly writes that into `Movies/` on every launch is a bad neighbour, and the user is the only one who can clean it up. Writing to our own folder is the behaviour a user can predict from every other app on the phone.

What that trade costs, stated plainly:

- **Takes in the app folder are deleted when the app is uninstalled.** That is the real price, and it is why the option exists.
- They are not visible to the gallery or to a laptop over MTP without the user turning the setting on or moving the file themselves.

**`Movies/Scenaristo Camera/` rather than `DCIM/Camera/`** for the opt-in path — confirmed by Davide on 2026-09-05:

- `DCIM/Camera` is where a phone's own camera app puts things, and where gallery apps expect *captures*. A 2.9 GB take is not a snapshot.
- `Movies/` is the platform's own home for produced video, is what `MediaStore.Video` defaults to, and keeps a named subdirectory a creator can point an editor or sync tool at.

Consequences that follow either way:

- No storage permission is required on either path. Since Android 10 an app inserting its own MediaStore rows needs none, and the minimum here is API 34 (ADR-0012).
- The interrupted-take marker of #17 carries the **display name** rather than a path, which works for both destinations — and is what the user is told anyway.
- Free-space estimation keeps using `getExternalFilesDir` for its `StatFs` call. It measures the volume, and both destinations are on it.

**This amends PRD 6.7's acceptance criterion**, which reads *"The partial file appears in the camera roll or Movies folder with the normal filename."* By default it now appears in the app's own folder; it appears in `Movies/` when the user has asked for that. The criterion should be amended to say so, or restated as "in a location the app names on the next launch", which is what #17 actually delivers.

## Options Considered

### Option A: MediaStore, `Movies/Scenaristo Camera/` (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low: one output-options class, one marker-format change |
| Risk | Low; the API is the platform's supported path and needs no permission |
| Effort | Hours |
| Reversibility | High |

**Pros:** Visible, survives uninstall, no permission prompt, keeps takes out of the photo roll, gives a stable folder to point tooling at.
**Cons:** A content URI is less convenient than a path for anything that wants to read the file back; `adb` inspection needs a `content query` rather than `ls`.

### Option B: MediaStore, `DCIM/Camera/`

**Pros:** Where gallery apps look first; matches "camera roll" in the PRD most literally.
**Cons:** Mixes deliberate long takes into the user's photo roll. A creator who records ten 3 GB takes has buried their photos, and the app has made that decision for them.

### Option C: Keep `getExternalFilesDir` and add a MediaStore copy on finalise

| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Medium |
| Effort | Days |
| Reversibility | Medium |

**Pros:** Nothing about the recording path changes; the private copy is a safety net.
**Cons:** Copies multi-gigabyte files, doubling write wear and the time between "stop" and "safe", and doubling peak storage at exactly the moment storage is most likely to be short. It also does nothing for the crash case: a copy that happens on finalise never happens when the app is killed, which is the case PRD 6.7 is about.

### Option D: Keep it as it is

**Pros:** No work.
**Cons:** Contradicts PRD 6.7 and section 3; loses takes on uninstall; the user cannot find their own recordings.

## Trade-off Analysis

C is the tempting one and it fails on the case that matters. The reason the file needs to be in MediaStore is that the app might die — and a copy made at the end is exactly what a crash prevents. Recording *into* the final location is the only version that helps the take PRD 6.7 is written about.

Between A and B, both satisfy the PRD, and the difference is whose folder gets a 3 GB file in it. A treats a take as work product; B treats it as a snapshot. A is reversible in one constant if that is judged wrong.

## Consequences

- Easier: takes are findable, survive uninstall, and reach a laptop over MTP without `adb`.
- Harder: the output is a URI, so anything reading a take back needs a `ContentResolver`. Nothing does today.
- The 400 KB `moov` reserve of ADR-0002 behaves the same way. Verified on the reference device: a MediaStore recording force-killed ~22 s in yields a file of 751 video frames and 1164 audio frames, `duration=25.04 s`, that `ffprobe` reads without a warning. Crash resilience is not traded away for visibility.
- **One new wrinkle, and it only appears after a crash.** MediaStore's `_size` column is written when the row is finalised, so a take interrupted mid-recording leaves a row claiming 549 KB against 119 MB actually on disk. `is_pending` is already `0`, so the file is visible and plays — but a gallery that trusts `_size` will describe it wrongly. Nothing the app does can update that column after the process is gone; the fix, if one is wanted, is to notice the stale row on the next launch and correct it, which is the same launch-time pass that already reports the interrupted take (#17).
- Revisit when: a user asks for takes in DCIM, or when a "download the last recording" feature (PRD 6.11) needs to read files back and the URI becomes load-bearing.

## Action Items

1. [x] Confirm `Movies/Scenaristo Camera/` over `DCIM/Camera/`. **Davide, 2026-09-05: Movies.** The decision is made; the `Status` line is Davide's to flip.
2. [x] Switch `FileOutputOptions` to `MediaStoreOutputOptions` and carry the display name in the interrupted-take marker.
3. [x] Re-run the force-kill of #17 against a MediaStore recording and confirm the partial file is still readable and still visible in the gallery. **Done**: readable, 25.04 s recovered, `is_pending=0`. The `_size` column is stale, recorded above.
4. [ ] Decide whether PRD 6.7's "camera roll or Movies folder" should be narrowed to whichever this settles on.
