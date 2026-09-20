# Bivouac: release notes

*[Version française](RELEASE_NOTES.fr.md)*

## V2.4.0

**Internationalization (new):**

- The app is now available in English (default) and French. It follows the device language, or
  the per-app language on Android 13 and later
- Numbers, dates and durations follow the format of the displayed language
- A few labels and terms were harmonized along the way

## V2.3.0

**Journal, photos: two storage modes (new):**

- Choice in Settings between "Original quality" (a copy identical to the gallery photo) and
  "Lightweight" (reduced to 2048 px, about ten times smaller). New installs start in lightweight
  mode; installs that already have photos get a prompt on first launch (choose now, later, or
  don't ask again)
- Recompression of already-imported photos: offered when switching to lightweight mode,
  available afterwards from Settings. Each photo is replaced safely (new file, swap, then delete
  the old one)
- In lightweight mode, the full-screen viewer shows the gallery original when it's still on the
  phone (a seamless upgrade in quality)
- "Storage usage" screen in Settings: tracks, photos, the rest
- Photos missing after a restore: found automatically in the gallery when the original is still
  there

**Journal, photos: editing (new):**

- "Crop" a photo: rotate by quarter turns and crop (corners keep the aspect ratio, edges are
  free, drag to move the frame), non-destructive and applied to every view; a Reset button
- Full-screen viewer in edit mode: Crop / Position / Delete action bar, caption editable with a
  tap
- Position: reposition a photo by dragging it along the track (the drawer drops down over the
  map, the elevation profile follows, Cancel or Done), restore it to its GPS position or to the
  position inferred from the shot's timestamp, hide a photo from the map and show it again
  (badge in the grid)
- Caption per photo, shown in the viewer and in the map bubble
- Import: enlarge a candidate photo before picking it, tap to select in the viewer
- Tapping a group of photos on the map opens the whole group in the bubble's carousel, and the
  cursor follows the swipe

**Improvements:**

- Direction arrows recalculated based on zoom and framing, on every day of a trek
- "has a note" and "has photos" icons on each Journal row
- Dedicated dialog when a duplication to Planning is queued behind closing a track

**Bugfixes:**

- Several GPX files shared to Planning: the choice is now disabled instead of silently losing
  every file after the first
- Fixed a duplicate app instance when a GPX file is opened from another app
- No network connection on the very first launch without any interaction (a tile was being
  loaded for a map that wasn't visible yet)
- Thumbnail badges (approximate position, hidden from map) are now legible

## V2.2.1

**Critical bugfix:**

- On Android 8 to 13, importing a GPX track failed every time ("Invalid track or unreadable
  file"), making the app unusable on those versions: in practice, it only worked on Android 14+.
  Fixed (the GPX reading library relied on a Java API missing from older Android versions, now
  bundled with the app itself). Thanks to the volunteer F-Droid reviewer who found and documented
  the issue.

## V2.2.0

**Journal, photos (new):**

- Attach photos from the phone's gallery to a Journal hike: placed automatically on the track
  from their GPS data, markers on the map, swipeable carousel in a marker's bubble (with the
  shot's timestamp), a per-hike gallery and a full-screen viewer
- Photo picker built into the app instead of the Android system picker: the latter strips the
  GPS position from the photos it hands over, which would make placing them on the track
  impossible. Hence two new media permissions (reading images, access to their location),
  requested only the first time the feature is used
- Fully optional feature, can be turned off in Settings; purging all imported photos is possible
  in the same place
- Editing a hike (note, tags, photos) is now transactional: nothing changes until you save, and
  leaving with pending changes explicitly asks you what to do (save, discard, or stay)

**Backup / restore:**

- Progress shown during backup, restore and photo purge (blocking dialog with a counter); heavy
  operations can no longer overlap (backup, restore, GPX imports, photo import/purge)
- Detection of incomplete backups: a truncated backup file (interrupted transfer, insufficient
  space) is now rejected at restore time instead of going unnoticed

**Bugfixes:**

- A track's elevation profile stayed completely empty as soon as a single GPX point had no
  altitude: gaps are now filled by interpolation

## V2.1.0

**Stats (new):**

- New Stats tab, an overview of the Journal: cumulative totals, a monthly progression chart
  (hikes, distance, ascent, speed, bivouacs) across the whole history, and your personal records
  (km-effort, best climb rate, highest altitude reached, highest bivouac, longest trek...). Each
  record links straight back to the relevant hike in the Journal.

**Settings:**

- Version number and build date shown at the bottom of the screen, to identify exactly which
  version is running on the device

**Bugfixes:**

- Planning: on the very first launch of the app, the "No track being planned" screen could
  briefly appear even when a previous session was about to be restored
- Planning: after killing and relaunching the app on a track already saved to the bank, closing
  the screen wrongly asked for a save confirmation again, and saving from that prompt duplicated
  the track instead of simply closing

## V2.0.2

**Settings (new):**

- Adjustable break allowance in the duration estimate (Custom speed): adds a time margin to
  estimates to account for breaks (photos, snacks, stops...), adjustable manually or measured
  automatically from the Journal/selection depending on the chosen mode

**Bugfixes:**

- The automatic ascent-penalty calculation (Auto/Selection) could be overestimated when a stop
  was taken in the middle of a climb: now excluded from the calculation, as was already the case
  on flat ground
- Duplicating a hike from Journal to Planning: the rename dialog could close by itself before you
  could type a name
- Planning: a multi-day trek duplicated from the Journal, whose recording had stopped far from
  the bivouac one evening, could show a fictitious path on the map and inflate the displayed
  total distance: same fix as the one already applied to the Journal
- A single-day track with no bivouac point at all had no way to export a GPX from Planning; the
  option has been added to the track's menu
- Opening a bank track that had become unreadable showed an error screen that made the rest of
  the list disappear; it now shows a one-off message instead, without disturbing the rest
- Journal: the bivouac row's layout (time font, icon) aligned with the rest of the interface
- Strengthened protection against a theoretical risk of an incomplete backup under concurrent
  database access during the operation

## V2.0.1

**Bugfixes:**

- The automatic speed/ascent-penalty calculation (Settings, Auto or Selection mode) could vary
  a lot depending on the hikes present in the Journal or the selection, especially with few hikes
  (around ten or fewer): fixed with a more robust calculation, done within each hike rather than
  by comparing hikes against each other.

## V2.0

**Journal (new):**

- Import one or more already-hiked GPX tracks; several files selected together are recognized as
  the days of a single hike rather than separate hikes
- Chronological list grouped by year, with cumulative distance, duration and elevation gain
- Read-only detail of a hike: map, elevation profile, bivouacs automatically detected at the
  breaks between the days of a multi-day hike
- Free-text note and tags on each hike; filter the list by tag
- Multi-select hikes to overlay them on the same map
- Delete a hike
- Duplicate a hike from Journal to Planning to reuse an already-hiked route

**Planning:**

- Same three-tab drawer (Overview / Profile / Details) as the Journal, for a consistent
  interface between the two sections
- The app resumes on the last section used (Journal or Planning); a GPX file received from
  another app explicitly asks which one to open it in when it isn't obvious

**Mapping:**

- Satellite map layer (Esri World Imagery), which can be turned off from the new Settings, like
  the weather link
- Direction arrows on the track, loops included

**Settings (new):**

- Settings screen, accessible from the section menu
- Custom speed for duration estimates: manual (editable flat-ground speed and ascent penalty),
  automatic (computed from the whole Journal, recalculated on every import), or from a selection
  of representative hikes
- Switch to disable the non-free features (Esri satellite layer, Meteoblue weather link)
- Full backup and restore of the database and settings (open format), backup and app version
  tracking

**Misc:**

- Updating from any previously published version of the app fully preserves the tracks, bivouacs
  and hikes already saved

**Bugfixes:**

- When opening a track in Planning, its lower part could stay hidden behind the drawer until the
  recenter button was pressed
- The GPX content of tracks (Planning bank and current session) is now stored in files rather
  than in the database, which removes a crash risk when opening a very large or point-heavy track
- Opening a track could fail consistently right after a backup, without needing an app restart
  for it to work again
- The Journal's tag filter could keep referencing a tag that no longer existed
- The section-choice dialog (Journal or Planning) could reopen after a screen rotation
- Closing a track received from another app without ever having saved it didn't warn about the
  loss, unlike a track that had been saved and then modified
- Fixed a possible crash on the elevation chart in a transient case of zero height measurement
- Removed stray info bubbles: on a missed tap near a track, and on a short click on a bivouac
  point

## V1.3

**Track bank (new):**

- Save, rename, duplicate, delete and list several planned tracks
- Unsaved-changes indicator, confirmation before closing a modified track without saving it

**Mapping:**

- Initial map zoom adapted to France when the device is set to that region

**Bugfixes:**

- Import failed for a GPX track containing sensor data (heart rate, cadence...), common in
  exports from hiking watches and GPS devices
- In landscape mode, recentering the map on the track could cut off the top of it

## V1.2

**Persistence (new):**

- The current track and its bivouac points are saved automatically and reopened when the app
  launches, unless a GPX file arrives in the meantime from another app (which takes priority)
- The selected map layer (Standard, Hiking, Satellite) is remembered between sessions

**Bugfixes:**

- The recenter button shifted slightly when opening and closing the map layer menu

## V1.1

**Import and display:**

- Import a track directly from another app (opening a `.gpx` file or sharing to the app), in
  addition to the system picker
- Map layer picker (Standard, Hiking OpenTopoMap, Satellite Esri World Imagery, Hiking by
  default)
- Recenter button on the track, which accounts for the area actually visible above the drawer
  (not centered on the whole screen if part of it is hidden)

**Bivouac points:**

- Point altitude displayed; weather icon opening the forecast (Meteoblue, point coordinates,
  FR/EN language matching the device's)

**Elevation profile (new):**

- Elevation profile of the whole track, shown as soon as a track is loaded
- Altitude and distance markers, evenly spaced between the bounds then rounded
- Each bivouac point's position marked on the chart, with its distance shown on the axis; follows
  the point being dragged in real time, even before the gesture is released

**Interface:**

- Collapsed drawer height automatically adjusted to its content (instead of a fixed value)

**Bugfixes:**

- The track and bivouac points were lost on a screen orientation change
- In landscape mode, the drawer could hide most of the map
- The segment table wasn't scrollable once the drawer was expanded full-screen, making bivouac
  points beyond the first page unreachable
- The bivouac point icon in the table looked distorted (clipped edges)

## V1

First functional version:

- GPX track import
- Placing bivouac points on the map
- Daily segment table

**Import and display:**

- Import a GPX file via the system picker (multi-track/multi-segment flattened into one
  continuous track)
- Display on an OSM base map (osmdroid), the track as a blue dashed line with a white outline so
  it stands out on any layer
- Automatic zoom to the track's extent on opening (5% margin on the edges)
- Start icon (green, "play") and end icon (red, "stop"); combined start/end icon for a loop

**Bivouac points:**

- Add a point by tapping the track (24dp tolerance)
- Move by dragging, snapped in real time to the nearest track point
- Delete from the segment table

**Segment table:**

- Generated automatically as soon as the first bivouac point is placed
- Per segment: distance, estimated duration, ascent, descent
- Updated in real time while dragging a point
- Grand total shown at the top of the drawer, faded out once there are segments (to avoid a
  visual duplicate)
- Export a segment as a GPX file, opened directly in a compatible third-party app

**Interface:**

- Expandable bottom drawer (drag upward) over a full-screen map
- App icon and matching bivouac icons (orange tent)

**Notable technical details:**

- Estimated duration from a base speed (3.5 km/h) corrected for ascent using a simplified rule
  close to Naismith's (100 m of ascent ≈ 1 km of flat equivalent)

**Known limitations:**

- Total descent can differ slightly from the sum of the segments (elevation smoothing
  recalculated independently per segment)
- No session save/resume (everything is lost when the app closes)
