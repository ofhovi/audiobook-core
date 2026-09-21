# Audiobook core contract, v1

Every client (Android, iOS, Windows, desktop) must agree on this. If two clients
disagree on any rule here, sync corrupts progress silently, which is the worst
failure mode this app has. Change this document before changing code.

Rule of thumb: anything below is a wire format. Treat it like a public API.

---

## 1. Content ID

A book is identified by its content, never by its path or filename. Paths differ
per device and users rename files.

```
content_id = hex_lower( sha256( le_u64(file_size) || first_65536_bytes || last_65536_bytes ) )
```

- `le_u64` is the file size in bytes, little-endian, 8 bytes.
- Read exactly 65536 bytes from offset 0, and exactly 65536 bytes from
  `max(0, file_size - 65536)`.
- If the file is smaller than 131072 bytes the two windows overlap. That is
  intentional. Do not special-case it. Every port must overlap identically.
- If the file is smaller than 65536 bytes, both reads return the whole file.
- Output is 64 lowercase hex characters.

Why not full-file sha256: a 400MB file takes seconds on a phone and users import
dozens at once. Why not just size: collisions across a series are common.

**Multi-file books** (a folder of 40 MP3s treated as one book) get a synthetic ID:

```
content_id = hex_lower( sha256( "multi\n" || join("\n", sorted(content_id(each file))) ) )
```

Sort the per-file IDs as hex strings, ascending, byte-comparison. Do not sort by
filename, which differs across devices.

## 2. Chapter shape

The parser normalizes all chapter schemes to one list. Clients never see raw MP4
atoms. Sources, in priority order:

1. QuickTime chapter track (`moov/trak/tref/chap` pointing at a text track)
2. Nero `chpl` (`moov/udta/chpl`)
3. None, in which case a single chapter spans the whole file

```json
{
  "chapter_source": "quicktime",
  "chapters": [
    {"index": 0, "title": "Chapter One", "start_ms": 0,     "end_ms": 10000},
    {"index": 1, "title": "Chapter Two", "start_ms": 10000, "end_ms": 22000}
  ]
}
```

Guarantees the parser must uphold, because the UI relies on them:

- `index` is dense and zero-based, in ascending `start_ms` order.
- Chapters are **contiguous**: `chapters[i].end_ms == chapters[i+1].start_ms`.
  A position-to-chapter lookup is then a simple binary search with no gap case.
- `start_ms < end_ms` for every chapter. Zero-length chapters are dropped.
- The last chapter's `end_ms` equals the file duration.
- `title` is never empty. Fall back to `"Chapter {index+1}"`.
- Nero `chpl` stores start times only, so ends are derived from the next start.
- Parsing never throws on a valid MP4. A file with no recognizable chapters
  returns one chapter, not an error.

Titles are UTF-8. QuickTime text samples may be UTF-16 with a BOM, or legacy
cp1252. Decode in that order and never fail: replace undecodable bytes.

**Cover art.** The parse result carries `cover_format` (`"jpeg"`, `"png"`, or
absent), read from `moov/udta/meta/ilst/covr`. It does not carry the image
bytes: those come from a separate `extract_cover`/`extractCover` call, taken
on demand rather than on every parse, since a client listing a library
shouldn't have to read every embedded image just to show titles. Format is
read from the `data` atom's iTunes type indicator (13 = JPEG, 14 = PNG),
falling back to sniffing the magic bytes if a writer left it at 0. An
unrecognized format is treated as no cover, not an error, per the
never-throws-on-a-valid-MP4 rule above.

## 3. Progress record

```json
{
  "content_id": "a3f...",
  "position_ms": 4530000,
  "speed": 1.25,
  "updated_at": 1758231041123,
  "device_id": "b7c2...",
  "finished": false
}
```

- `updated_at` is Unix epoch **milliseconds UTC**. Never local time.
- `device_id` is a random UUIDv4 generated once on first launch and persisted.
  It is not a hardware ID and must survive app updates but not reinstalls.
- `speed` is playback rate, 0.5 to 3.0.
- `finished` is set explicitly, not inferred from position, because users skip
  end credits and a "99% played" book should not silently mark itself done.

**Conflict resolution.** Last-write-wins by `updated_at`, with one guard:

```
if |remote.position_ms - local.position_ms| > 120000
   and remote.device_id != local.device_id:
       ask the user
else:
       take the higher updated_at
```

The prompt shows both positions as timestamps and which device each came from.
Never resolve a large divergence silently. Clock skew between devices will
otherwise delete an hour of someone's progress, and that is the bug that makes
people uninstall an audiobook app.

If `updated_at` values are equal, prefer the higher `position_ms`.

## 4. Sync document

One JSON object in Google Drive's `appDataFolder`, filename `sync-v1.json`.

OAuth scope is `drive` (full account access), not `drive.appdata` alone.
`appDataFolder`, like `drive.file`, is scoped per OAuth client: a folder or
file one client creates is invisible to a *different* client, even under the
same Google account. Windows and iOS necessarily register as different
clients (different redirect mechanisms), so two clients each holding only
`drive.appdata`/`drive.file` can never see each other's data, only their own.
This was discovered by shipping it and watching it silently fail cross-device
(each client "successfully" synced against its own private, empty view),
not by reading Google's docs closely enough beforehand. `drive` grants access
to the user's whole Drive rather than to a specific client's slice of it, so
every client sees the same folder regardless of who created it. `drive` is a
restricted scope: expect a stronger consent warning and, while the app stays
in Testing status, refresh tokens that expire after 7 days regardless of
activity (Google's Testing-mode policy, not something this app controls).

```json
{
  "schema": 1,
  "device_id": "b7c2...",
  "written_at": 1758231041123,
  "progress": [ /* progress records, one per content_id */ ],
  "bookmarks": [
    {"content_id": "a3f...", "position_ms": 120000, "note": "good bit", "created_at": 1758231041123}
  ]
}
```

- `schema` is mandatory and checked on read. A client seeing `schema` greater
  than it understands must **refuse to write** and tell the user to update,
  rather than overwrite a newer format with an older one.
- Pull on app start and on foreground. Push on pause, chapter change, and
  backgrounding. Do not push on every position tick.
- Merge per `content_id` using the rule in section 3. Never replace the whole
  document with local state.
- Bookmarks merge as a union keyed on `(content_id, position_ms)`. Deletions
  need tombstones, which v1 does not have, so v1 bookmarks are add-only.

File sync is deliberately **not** in this document. Files and progress are
different problems: progress is tiny and frequent, files are large and rare.
Keep the storage backend behind an interface (`open(content_id) -> byte range
reader`) so Drive, WebDAV, and local disk are interchangeable.

## 5. Local store

SQLite, same schema on every platform.

```sql
CREATE TABLE books (
  content_id   TEXT PRIMARY KEY,
  title        TEXT,
  author       TEXT,
  narrator     TEXT,
  duration_ms  INTEGER NOT NULL,
  chapters     TEXT NOT NULL,          -- JSON array, section 2
  chapter_source TEXT NOT NULL,
  added_at     INTEGER NOT NULL
);

CREATE TABLE sources (                 -- where this book's bytes live, per device
  content_id   TEXT NOT NULL REFERENCES books(content_id) ON DELETE CASCADE,
  kind         TEXT NOT NULL,          -- 'local' | 'drive' | 'webdav'
  locator      TEXT NOT NULL,          -- path, file id, or URL
  part_index   INTEGER NOT NULL DEFAULT 0,   -- for multi-file books
  PRIMARY KEY (content_id, kind, part_index)
);

CREATE TABLE progress (
  content_id   TEXT PRIMARY KEY REFERENCES books(content_id) ON DELETE CASCADE,
  position_ms  INTEGER NOT NULL,
  speed        REAL NOT NULL DEFAULT 1.0,
  updated_at   INTEGER NOT NULL,
  device_id    TEXT NOT NULL,
  finished     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE bookmarks (
  content_id   TEXT NOT NULL REFERENCES books(content_id) ON DELETE CASCADE,
  position_ms  INTEGER NOT NULL,
  note         TEXT,
  created_at   INTEGER NOT NULL,
  PRIMARY KEY (content_id, position_ms)
);
```

Note that `sources` is separate from `books` on purpose: the same book can live
at a different path on each device, and the book row syncs while the source row
does not.

## 6. Persistence timing

Progress is written locally every 5 seconds of playback, plus immediately on:
pause, seek, chapter change, speed change, app backgrounding, and playback end.

Position loss after a force-quit is the single most visible bug in an audiobook
player. When in doubt, write.

## 7. Library sync

Section 4 deliberately excludes files: "progress is tiny and frequent, files
are large and rare." This section is that different problem. A book added on
one device does not appear on another through section 4's sync document,
that document only ever carries progress for books a device already has.

Library sync lives in its own Drive folder, **not** `appDataFolder`: files
here are large and the user should be able to see and manage them like any
other Drive content, which a hidden app-data folder doesn't allow. Uses the
same `drive` scope as section 4, for the same reason: `drive.file` looked
right on paper (per-file access to what the app itself created) but is scoped
per OAuth client the same way `appDataFolder` is, so it can't do the one
thing this section exists for, letting a *different* client see a file this
one created. See section 4's note. A client authorized under the old
`drive.appdata`/`drive.file` pair must re-consent to get the wider scope.

- Folder name: `Audiobook Core`, created at the root of the user's Drive if
  it doesn't already exist (find-by-name first; do not create duplicates).
- `library-v1.json` in that folder:

```json
{
  "schema": 1,
  "books": [
    {
      "content_id": "a3f...",
      "title": "Chapter One: The Beginning",
      "author": "...",
      "narrator": "...",
      "duration_ms": 36961826,
      "chapters": [ /* section 2 shape */ ],
      "chapter_source": "quicktime",
      "cover_format": "jpeg",
      "file_name": "a3f....m4b",
      "added_at": 1758231041123
    }
  ]
}
```

  Same schema-refusal rule as section 4: a client seeing a `schema` it
  doesn't understand refuses to write rather than overwrite a newer format.
- The audio file itself is a separate Drive file in the same folder, named
  `<content_id>.<original extension>`. `file_name` in the metadata above is
  that name, kept explicit rather than reconstructed, since a future format
  might not derive it so mechanically.
- **Cover image bytes are not synced in v1.** `cover_format` travels so a
  puller knows one exists; the bytes stay device-local, same gap as section 2
  already has for the on-demand `extract_cover`/`extractCover` accessor.
- Merge is a union keyed on `content_id`: no divergence concept like section
  3's progress merge, since library entries are add-once metadata, not
  something playback keeps rewriting. On a same-`content_id` collision
  (should only happen from clock skew across devices adding the same file
  near-simultaneously) keep the entry with the higher `added_at`.
- Pulling a `content_id` the local device doesn't have: add the metadata
  locally, then download the audio file into that platform's own managed
  cache location (never a user-facing path picker, there was no user pick).
  Playback reads it like any other local file once downloaded.
- Pushing: after a local add (from a user-picked file) succeeds and the
  device is signed in, upload the audio file, then merge this device's
  library into the shared `library-v1.json` and write it back.
- v1 uploads and downloads the whole file, not resumably. A dropped
  connection mid-transfer means starting over. Fine for now; revisit if it
  turns out to matter in practice.

---

## Changelog

- **v1** (initial): content ID, chapter shape, progress record, Drive sync
  document, local schema.
- Added section 7 (library sync: book metadata and audio files, a separate
  Drive folder and scope from the progress-only sync document). Additive,
  no `schema` bump: section 4's sync document is unchanged.
- Changed both sections' OAuth scope from `drive.appdata`/`drive.file` to
  `drive`: those are scoped per OAuth client, so Windows and iOS (necessarily
  different clients) could each sync successfully against their own private,
  invisible-to-each-other data and never see one another's. No `schema`
  bump, this is a transport/auth detail, not a document shape change.
