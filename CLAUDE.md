# Audiobook core

Cross-platform audiobook player. Targets Android, iOS, Windows, and desktop.
Plays m4a/m4b with chapters, saves position, syncs progress via Google Drive.

This repo is the **shared core**, not the apps. Player adapters and UI live in
the platform repos and are deliberately thin.

## Read this first

`CONTRACT.md` is the spec every client must obey. It defines content IDs,
chapter shape, the progress record, the sync document, and the SQLite schema.
**Read it before changing any parsing, hashing, or sync code.** If a change
needs the contract to change, update `CONTRACT.md` in the same commit and bump
`schema`.

## The one invariant

`reference/mp4_audiobook.py` and `kotlin/src/Mp4Chapters.kt` are two
implementations of the same spec and **must produce identical output**.

```
bash scripts/verify.sh
```

Runs the Python tests, builds fixtures with ffmpeg, runs both ports, and diffs
parse output and content IDs. It must print `0 divergences`. Run it after any
change to either parser. If you change one port, change the other in the same
commit.

Needs `ffmpeg`, `python3`, `pytest`, and `kotlinc` on PATH. Without kotlinc it
degrades to Python-only and says so. `KOTLINC=/path/to/kotlinc` overrides.

## Why it's built this way

**Two implementations on purpose.** Python is the reference: fast to iterate,
easy to test, and it can be diffed against ffprobe as an independent oracle.
Kotlin is what ships. Divergence between ports is the failure mode that breaks
sync silently, so it's checked mechanically rather than by review.

**`Mp4Chapters.kt` is commonMain-safe.** No `java.*`, no okio, no `Charsets`.
All file access goes through the `ByteSource` interface. The UTF-16 and cp1252
decoders are hand-rolled for that reason. Keep it that way: if you add a
`java.*` import, it stops compiling for iOS and Native targets. Platform code
belongs in `JvmSupport.kt` or its siblings.

**Content ID is not a file hash.** It's `sha256(le_u64(size) || first 64KiB ||
last 64KiB)`. Full-file hashing takes seconds per book on a phone and users
import dozens at once. The windows overlap for files under 128KiB and that is
intentional, not a bug to fix. Both ports must overlap identically.

**Chapters are contiguous by construction.** `chapters[i].end_ms ==
chapters[i+1].start_ms`, always. This makes position-to-chapter lookup a plain
binary search with no gap handling in the UI. The normalization pass enforces
it. Don't relax it.

**Parsing never throws on a valid MP4.** No chapters means one chapter spanning
the file, not an error. Only structurally broken files raise `Mp4Error`.

## Chapter schemes

Real m4b files use incompatible chapter formats. Priority order:

1. QuickTime chapter track: `moov/trak/tref/chap` points at a text track whose
   samples are the titles. Timing comes from `stts`, offsets from
   `stsc`+`stco`/`co64`+`stsz`.
2. Nero `chpl` in `moov/udta`. Timestamps are 100-nanosecond units. Start times
   only, so ends are derived from the next start.
3. Neither, so one implicit chapter.

ffmpeg writes both 1 and 2, which is why fixtures can exercise each path by
breaking the `tref/chap` reference.

## Testing notes

Fixtures are generated, never committed. `crosscheck.py --fixtures DIR` builds
them: both chapter schemes, faststart layout, a 25-chapter file with CJK and
quote characters, a 40ms chapter, an hour-long file, four truncation points,
random garbage, an empty file, a zero-size nested atom, and content-ID window
edges at 3 bytes, exactly 131072, and 131073.

When adding a parser feature, add a fixture for it. When fixing a parser bug,
add the file shape that caused it.

ffprobe is a useful independent oracle for chapter timing. It is not a
substitute for the cross-check, since it can't validate content IDs.

## Not built yet

- Sync merge logic (spec is in `CONTRACT.md` section 3, code doesn't exist)
- Drive storage backend
- Any player adapter or UI
- Multi-file book support (spec'd in section 1, parser is single-file only)

## Conventions

- Prose in docs and comments: no em dashes.
- Comments explain why, not what. The what is readable from the code.
- Don't add dependencies to `Mp4Chapters.kt`. It has zero and should keep zero.
