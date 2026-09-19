"""Tests for the audiobook core file layer. Fixtures are built with ffmpeg."""

import os
import shutil
import struct
import subprocess
import tempfile

import pytest

from mp4_audiobook import Mp4Error, content_id, extract_cover, parse_audiobook

FF = shutil.which("ffmpeg")
pytestmark = pytest.mark.skipif(FF is None, reason="ffmpeg required")

CHAPS = """;FFMETADATA1
title=Test Book
artist=Test Author
composer=Test Narrator
album=Test Series
[CHAPTER]
TIMEBASE=1/1000
START=0
END=10000
title=Chapter One: The Beginning
[CHAPTER]
TIMEBASE=1/1000
START=10000
END=22000
title=Chapter Two
[CHAPTER]
TIMEBASE=1/1000
START=22000
END=30000
title=Chapter Three: \u00dcn\u00efcod\u00e9 T\u00eftle
"""


@pytest.fixture(scope="module")
def tmproot():
    d = tempfile.mkdtemp()
    yield d
    shutil.rmtree(d, ignore_errors=True)


def _run(*args):
    subprocess.run([FF, "-y", "-loglevel", "error", *args], check=True)


@pytest.fixture(scope="module")
def plain(tmproot):
    p = os.path.join(tmproot, "plain.m4a")
    _run("-f", "lavfi", "-i", "sine=frequency=200:duration=30",
         "-c:a", "aac", "-b:a", "32k", p)
    return p


@pytest.fixture(scope="module")
def tagged(tmproot, plain):
    meta = os.path.join(tmproot, "chaps.txt")
    with open(meta, "w", encoding="utf-8") as f:
        f.write(CHAPS)
    p = os.path.join(tmproot, "book.m4b")
    _run("-i", plain, "-i", meta, "-map_metadata", "1", "-c", "copy", p)
    return p


@pytest.fixture(scope="module")
def cover_jpeg(tmproot, plain):
    meta = os.path.join(tmproot, "chaps2.txt")
    with open(meta, "w", encoding="utf-8") as f:
        f.write(CHAPS)
    cover = os.path.join(tmproot, "cover.jpg")
    _run("-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", cover)
    p = os.path.join(tmproot, "with_cover.m4b")
    _run("-i", plain, "-i", meta, "-i", cover, "-map_metadata", "1",
         "-map", "0:a", "-map", "2:v", "-c", "copy",
         "-disposition:v:0", "attached_pic", p)
    return p, cover


# ----------------------------------------------------------------- content id

def test_content_id_is_stable_and_path_independent(tagged, tmproot):
    a = content_id(tagged)
    copy = os.path.join(tmproot, "renamed-elsewhere.m4b")
    shutil.copy(tagged, copy)
    assert content_id(copy) == a
    assert len(a) == 64 and a == a.lower()


def test_content_id_changes_with_content(tmproot, tagged):
    mutated = os.path.join(tmproot, "mutated.m4b")
    data = bytearray(open(tagged, "rb").read())
    data[100] ^= 0xFF
    open(mutated, "wb").write(data)
    assert content_id(mutated) != content_id(tagged)


def test_content_id_handles_file_smaller_than_window(tmproot):
    p = os.path.join(tmproot, "tiny.bin")
    open(p, "wb").write(b"abc")
    assert len(content_id(p)) == 64


# ------------------------------------------------------------------- parsing

def test_quicktime_chapters(tagged):
    book = parse_audiobook(tagged)
    assert book.chapter_source == "quicktime"
    assert [c.title for c in book.chapters] == [
        "Chapter One: The Beginning",
        "Chapter Two",
        "Chapter Three: \u00dcn\u00efcod\u00e9 T\u00eftle",
    ]
    assert [(c.start_ms, c.end_ms) for c in book.chapters] == [
        (0, 10000), (10000, 22000), (22000, 30000),
    ]
    assert [c.index for c in book.chapters] == [0, 1, 2]


def test_metadata_tags(tagged):
    book = parse_audiobook(tagged)
    assert book.title == "Test Book"
    assert book.author == "Test Author"
    assert book.album == "Test Series"
    assert 29_000 <= book.duration_ms <= 31_000


def test_no_cover_by_default(tagged):
    book = parse_audiobook(tagged)
    assert book.cover_format is None
    assert extract_cover(tagged) is None


def test_embedded_jpeg_cover(cover_jpeg):
    p, _source_image = cover_jpeg
    book = parse_audiobook(p)
    assert book.cover_format == "jpeg"
    data = extract_cover(p)
    assert data is not None and data[:3] == b"\xff\xd8\xff"


def test_nero_fallback_when_quicktime_track_absent(tmproot, tagged):
    """Strip the tref/chap reference so only 'chpl' remains."""
    data = bytearray(open(tagged, "rb").read())
    i = data.find(b"chap")
    assert i > 0
    data[i:i + 4] = b"xxxx"  # break the reference, leave sizes intact
    p = os.path.join(tmproot, "nero_only.m4b")
    open(p, "wb").write(data)

    book = parse_audiobook(p)
    assert book.chapter_source == "nero"
    assert [c.title for c in book.chapters] == [
        "Chapter One: The Beginning",
        "Chapter Two",
        "Chapter Three: \u00dcn\u00efcod\u00e9 T\u00eftle",
    ]
    # Nero stores only start times; ends must be derived from the next start.
    assert [(c.start_ms, c.end_ms) for c in book.chapters[:2]] == [
        (0, 10000), (10000, 22000),
    ]
    assert book.chapters[-1].end_ms == book.duration_ms


def test_no_chapters_yields_single_span(plain):
    book = parse_audiobook(plain)
    assert book.chapter_source == "none"
    assert len(book.chapters) == 1
    assert book.chapters[0].start_ms == 0
    assert book.chapters[0].end_ms == book.duration_ms
    assert book.chapters[0].title == "Chapter 1"  # fallback title


def test_faststart_moov_first(tmproot, plain):
    """Real audiobooks are often written with moov before mdat."""
    meta = os.path.join(tmproot, "c2.txt")
    open(meta, "w", encoding="utf-8").write(CHAPS)
    p = os.path.join(tmproot, "faststart.m4b")
    _run("-i", plain, "-i", meta, "-map_metadata", "1", "-c", "copy",
         "-movflags", "+faststart", p)
    book = parse_audiobook(p)
    assert len(book.chapters) == 3
    assert book.chapters[0].title == "Chapter One: The Beginning"


def test_long_duration_uses_64bit_fields(tmproot):
    p = os.path.join(tmproot, "long.m4a")
    _run("-f", "lavfi", "-i", "sine=frequency=100:duration=3600",
         "-c:a", "aac", "-b:a", "16k", "-movflags", "+use_metadata_tags", p)
    book = parse_audiobook(p)
    assert 3_590_000 <= book.duration_ms <= 3_610_000


# ------------------------------------------------------------- malformed input

def test_truncated_file_does_not_hang_or_crash(tmproot, tagged):
    data = open(tagged, "rb").read()
    for frac in (0.1, 0.5, 0.9, 0.99):
        p = os.path.join(tmproot, f"trunc_{frac}.m4b")
        open(p, "wb").write(data[: int(len(data) * frac)])
        try:
            book = parse_audiobook(p)
        except Mp4Error:
            continue  # acceptable: refused cleanly
        assert book.chapters, "must always return at least one chapter"


def test_garbage_is_rejected(tmproot):
    p = os.path.join(tmproot, "garbage.bin")
    open(p, "wb").write(os.urandom(4096))
    with pytest.raises(Mp4Error):
        parse_audiobook(p)


def test_empty_file_is_rejected(tmproot):
    p = os.path.join(tmproot, "empty.bin")
    open(p, "wb").write(b"")
    with pytest.raises(Mp4Error):
        parse_audiobook(p)


def test_zero_size_atom_does_not_loop_forever(tmproot):
    """size==0 means 'to end of file'; a nested one must not cause an infinite loop."""
    p = os.path.join(tmproot, "zeroatom.m4b")
    body = struct.pack(">I4s", 0, b"moov")
    open(p, "wb").write(struct.pack(">I4s", 16, b"ftyp") + b"M4A \x00\x00\x02\x00" + body)
    try:
        parse_audiobook(p)
    except Mp4Error:
        pass


def test_absurd_chapter_count_is_bounded(tmproot, tagged):
    """A crafted chpl count must not allocate unboundedly."""
    data = bytearray(open(tagged, "rb").read())
    i = data.find(b"chpl")
    assert i > 0
    data[i + 4] = 0x01          # version 1
    data[i + 12] = 0xFF         # claim 255 chapters
    data[data.find(b"chap"):data.find(b"chap") + 4] = b"xxxx"
    p = os.path.join(tmproot, "absurd.m4b")
    open(p, "wb").write(data)
    book = parse_audiobook(p)
    assert len(book.chapters) <= 255


# --------------------------------------------------------------- normalization

def test_chapters_are_contiguous_and_ordered(tagged):
    book = parse_audiobook(tagged)
    for a, b in zip(book.chapters, book.chapters[1:]):
        assert a.end_ms == b.start_ms, "gaps break the progress -> chapter lookup"
        assert a.start_ms < a.end_ms
    assert book.chapters[-1].end_ms <= book.duration_ms
