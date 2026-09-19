"""
Reference implementation of the audiobook core's file-inspection layer.

Two jobs:
  1. content_id(path)      -> stable identifier for a book file, path-independent
  2. parse_audiobook(path) -> normalized metadata + chapter list

Chapter sources handled, in priority order:
  A. QuickTime chapter track  (moov/trak/tref/chap -> text track samples)
  B. Nero 'chpl'              (moov/udta/chpl)
  C. none                     (single implicit chapter spanning the file)

Pure stdlib. This is the spec; the Kotlin/Swift ports must agree byte for byte
on content_id and produce the same normalized Chapter list.
"""

from __future__ import annotations

import hashlib
import struct
from dataclasses import dataclass, field
from typing import BinaryIO, Optional

# ---------------------------------------------------------------- content id

HASH_WINDOW = 65536


def content_id(path: str) -> str:
    """sha256( le_u64(filesize) || first 64KiB || last 64KiB ), lowercase hex.

    Windows overlap on files smaller than 128KiB is intentional and harmless:
    both ports must do it the same way, so the rule is unconditional.
    """
    import os

    size = os.path.getsize(path)
    h = hashlib.sha256()
    h.update(struct.pack("<Q", size))
    with open(path, "rb") as f:
        h.update(f.read(HASH_WINDOW))
        f.seek(max(0, size - HASH_WINDOW))
        h.update(f.read(HASH_WINDOW))
    return h.hexdigest()


# ------------------------------------------------------------------- model

@dataclass
class Chapter:
    index: int
    title: str
    start_ms: int
    end_ms: int


@dataclass
class Book:
    duration_ms: int
    chapters: list[Chapter]
    chapter_source: str  # "quicktime" | "nero" | "none"
    title: Optional[str] = None
    author: Optional[str] = None
    narrator: Optional[str] = None
    album: Optional[str] = None
    tags: dict[str, str] = field(default_factory=dict)


class Mp4Error(Exception):
    pass


# -------------------------------------------------------------- atom walking

_CONTAINERS = {
    b"moov", b"trak", b"mdia", b"minf", b"stbl", b"udta",
    b"meta", b"ilst", b"edts", b"tref", b"mvex", b"moof", b"traf",
}


@dataclass
class Atom:
    type: bytes
    offset: int        # offset of the atom header
    size: int          # total size including header
    body: int          # offset of the payload
    children: list["Atom"] = field(default_factory=list)

    @property
    def body_size(self) -> int:
        return self.offset + self.size - self.body

    def find(self, *path: bytes) -> Optional["Atom"]:
        node = self
        for want in path:
            nxt = None
            for c in node.children:
                if c.type == want:
                    nxt = c
                    break
            if nxt is None:
                return None
            node = nxt
        return node

    def find_all(self, want: bytes) -> list["Atom"]:
        return [c for c in self.children if c.type == want]


def _parse_atoms(f: BinaryIO, start: int, end: int, depth: int = 0) -> list[Atom]:
    if depth > 12:  # guard against crafted nesting
        return []
    out: list[Atom] = []
    pos = start
    while pos + 8 <= end:
        f.seek(pos)
        hdr = f.read(8)
        if len(hdr) < 8:
            break
        size, typ = struct.unpack(">I4s", hdr)
        header = 8
        if size == 1:
            ext = f.read(8)
            if len(ext) < 8:
                break
            size = struct.unpack(">Q", ext)[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            # Truncated or bogus atom: keep whatever we parsed, stop here.
            break
        atom = Atom(type=typ, offset=pos, size=size, body=pos + header)
        if typ in _CONTAINERS:
            body = atom.body
            if typ == b"meta":
                # 'meta' is usually a full box (4-byte version/flags) but some
                # writers emit it bare. Sniff: a full box starts with 0x00000000.
                f.seek(body)
                if f.read(4) == b"\x00\x00\x00\x00":
                    body += 4
            atom.children = _parse_atoms(f, body, pos + size, depth + 1)
        out.append(atom)
        pos += size
    return out


def _read(f: BinaryIO, atom: Atom) -> bytes:
    f.seek(atom.body)
    return f.read(atom.body_size)


# ----------------------------------------------------------------- sample map

def _sample_offsets(f: BinaryIO, stbl: Atom) -> list[tuple[int, int]]:
    """Resolve (file_offset, size) for every sample in a track."""
    stsz = stbl.find(b"stsz")
    stsc = stbl.find(b"stsc")
    stco = stbl.find(b"stco") or stbl.find(b"co64")
    if not (stsz and stsc and stco):
        return []

    b = _read(f, stsz)
    uniform, count = struct.unpack_from(">II", b, 4)
    if uniform:
        sizes = [uniform] * count
    else:
        if len(b) < 12 + 4 * count:
            raise Mp4Error("stsz truncated")
        sizes = list(struct.unpack_from(f">{count}I", b, 12))

    b = _read(f, stsc)
    (n,) = struct.unpack_from(">I", b, 4)
    runs = [struct.unpack_from(">III", b, 8 + 12 * i) for i in range(n)]

    b = _read(f, stco)
    (n,) = struct.unpack_from(">I", b, 4)
    if stco.type == b"co64":
        chunks = list(struct.unpack_from(f">{n}Q", b, 8))
    else:
        chunks = list(struct.unpack_from(f">{n}I", b, 8))

    # Expand stsc runs into samples-per-chunk for each chunk.
    per_chunk: list[int] = []
    for i, (first, spc, _desc) in enumerate(runs):
        last = runs[i + 1][0] - 1 if i + 1 < len(runs) else len(chunks)
        for _ in range(max(0, last - first + 1)):
            per_chunk.append(spc)
            if len(per_chunk) >= len(chunks):
                break
    while len(per_chunk) < len(chunks):
        per_chunk.append(runs[-1][1] if runs else 1)

    out: list[tuple[int, int]] = []
    s = 0
    for ci, base in enumerate(chunks):
        off = base
        for _ in range(per_chunk[ci]):
            if s >= len(sizes):
                return out
            out.append((off, sizes[s]))
            off += sizes[s]
            s += 1
    return out


def _sample_durations(f: BinaryIO, stbl: Atom) -> list[int]:
    stts = stbl.find(b"stts")
    if not stts:
        return []
    b = _read(f, stts)
    (n,) = struct.unpack_from(">I", b, 4)
    out: list[int] = []
    for i in range(n):
        count, delta = struct.unpack_from(">II", b, 8 + 8 * i)
        if count > 1_000_000:
            raise Mp4Error("stts entry count implausible")
        out.extend([delta] * count)
    return out


def _timescale_duration(f: BinaryIO, mdhd: Atom) -> tuple[int, int]:
    b = _read(f, mdhd)
    version = b[0]
    if version == 1:
        timescale, duration = struct.unpack_from(">IQ", b, 20)
    else:
        timescale, duration = struct.unpack_from(">II", b, 12)
    return (timescale or 1000), duration


# -------------------------------------------------------- chapter extraction

def _decode_text_sample(raw: bytes) -> str:
    """QuickTime text sample: uint16 length, text, then optional atoms ('encd')."""
    if len(raw) < 2:
        return ""
    (n,) = struct.unpack_from(">H", raw, 0)
    body = raw[2:2 + n]
    if body[:2] in (b"\xfe\xff", b"\xff\xfe"):
        try:
            return body.decode("utf-16").strip("\x00")
        except UnicodeDecodeError:
            pass
    for enc in ("utf-8", "cp1252", "latin-1"):
        try:
            return body.decode(enc).strip("\x00")
        except UnicodeDecodeError:
            continue
    return body.decode("latin-1", "replace")


def _quicktime_chapters(f: BinaryIO, moov: Atom) -> Optional[list[Chapter]]:
    traks = moov.find_all(b"trak")
    # Collect referenced chapter-track ids from any tref/chap.
    wanted: list[int] = []
    for t in traks:
        chap = t.find(b"tref", b"chap")
        if chap:
            raw = _read(f, chap)
            wanted += [
                struct.unpack_from(">I", raw, i)[0]
                for i in range(0, len(raw) - 3, 4)
            ]
    if not wanted:
        return None

    for t in traks:
        tkhd = t.find(b"tkhd")
        mdhd = t.find(b"mdia", b"mdhd")
        stbl = t.find(b"mdia", b"minf", b"stbl")
        if not (tkhd and mdhd and stbl):
            continue
        b = _read(f, tkhd)
        track_id = struct.unpack_from(">I", b, 20 if b[0] == 1 else 12)[0]
        if track_id not in wanted:
            continue

        timescale, _dur = _timescale_duration(f, mdhd)
        samples = _sample_offsets(f, stbl)
        durations = _sample_durations(f, stbl)
        if not samples:
            continue

        chapters: list[Chapter] = []
        t_units = 0
        for i, (off, size) in enumerate(samples):
            if size <= 0 or size > 1 << 20:
                continue
            f.seek(off)
            title = _decode_text_sample(f.read(size))
            start_ms = t_units * 1000 // timescale
            d = durations[i] if i < len(durations) else 0
            t_units += d
            end_ms = t_units * 1000 // timescale
            chapters.append(Chapter(len(chapters), title, start_ms, end_ms))
        if chapters:
            return chapters
    return None


def _nero_chapters(f: BinaryIO, moov: Atom) -> Optional[list[Chapter]]:
    chpl = moov.find(b"udta", b"chpl")
    if not chpl:
        return None
    b = _read(f, chpl)
    if len(b) < 9:
        return None
    version = b[0]
    # v1: version(1) flags(3) reserved(4) count(1). v0: version(1) flags(3) count(1).
    p = 9 if version == 1 else 5
    count = b[p - 1]
    out: list[Chapter] = []
    for _ in range(count):
        if p + 9 > len(b):
            break
        (ts,) = struct.unpack_from(">Q", b, p)
        p += 8
        n = b[p]
        p += 1
        title = b[p:p + n].decode("utf-8", "replace")
        p += n
        out.append(Chapter(len(out), title, ts // 10_000, 0))
    return out or None


# ------------------------------------------------------------------ metadata

_ILST = {
    b"\xa9nam": "title",
    b"\xa9ART": "author",
    b"\xa9alb": "album",
    b"\xa9wrt": "narrator",
    b"aART": "album_artist",
    b"\xa9gen": "genre",
    b"\xa9day": "year",
    b"\xa9cmt": "comment",
}


def _ilst_tags(f: BinaryIO, moov: Atom) -> dict[str, str]:
    ilst = moov.find(b"udta", b"meta", b"ilst")
    if not ilst:
        return {}
    tags: dict[str, str] = {}
    for item in ilst.children:
        data = item.find(b"data")
        if data is None:
            # 'data' is not in _CONTAINERS' children set for unknown parents;
            # parse this item's body on demand.
            kids = _parse_atoms(f, item.body, item.offset + item.size)
            data = next((k for k in kids if k.type == b"data"), None)
        if data is None or data.body_size <= 8:
            continue
        f.seek(data.body + 8)
        val = f.read(data.body_size - 8).decode("utf-8", "replace").strip("\x00")
        key = _ILST.get(item.type)
        if key and val:
            tags[key] = val
    return tags


# -------------------------------------------------------------------- public

def parse_audiobook(path: str) -> Book:
    with open(path, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        if size < 8:
            raise Mp4Error("file too small to be MP4")
        top = _parse_atoms(f, 0, size)
        if not any(a.type == b"ftyp" for a in top) and not any(
            a.type == b"moov" for a in top
        ):
            raise Mp4Error("not an MP4 container")
        moov = next((a for a in top if a.type == b"moov"), None)
        if moov is None:
            raise Mp4Error("no moov atom (fragmented or streaming-only file?)")

        mvhd = moov.find(b"mvhd")
        duration_ms = 0
        if mvhd:
            b = _read(f, mvhd)
            if b[0] == 1:
                ts, dur = struct.unpack_from(">IQ", b, 20)
            else:
                ts, dur = struct.unpack_from(">II", b, 12)
            duration_ms = dur * 1000 // (ts or 1000)

        chapters = _quicktime_chapters(f, moov)
        source = "quicktime"
        if not chapters:
            chapters = _nero_chapters(f, moov)
            source = "nero"
        if not chapters:
            chapters = [Chapter(0, "", 0, duration_ms)]
            source = "none"

        chapters = _normalize(chapters, duration_ms)
        tags = _ilst_tags(f, moov)

    return Book(
        duration_ms=duration_ms,
        chapters=chapters,
        chapter_source=source,
        title=tags.get("title"),
        author=tags.get("author"),
        narrator=tags.get("narrator"),
        album=tags.get("album"),
        tags=tags,
    )


def _normalize(chapters: list[Chapter], duration_ms: int) -> list[Chapter]:
    """Sort, clamp, fill gaps, drop zero-length, renumber, title fallbacks."""
    ch = sorted(chapters, key=lambda c: c.start_ms)
    for c in ch:
        c.start_ms = max(0, c.start_ms)
        if duration_ms:
            c.start_ms = min(c.start_ms, duration_ms)

    # Derive end from the next start; the last one runs to the file end.
    for i, c in enumerate(ch):
        nxt = ch[i + 1].start_ms if i + 1 < len(ch) else (duration_ms or c.end_ms)
        if c.end_ms <= c.start_ms or (nxt and c.end_ms > nxt):
            c.end_ms = nxt
        if duration_ms:
            c.end_ms = min(c.end_ms, duration_ms)

    ch = [c for c in ch if c.end_ms > c.start_ms] or ch[:1]
    out: list[Chapter] = []
    for i, c in enumerate(ch):
        title = c.title.strip() or f"Chapter {i + 1}"
        out.append(Chapter(i, title, c.start_ms, c.end_ms))
    return out
