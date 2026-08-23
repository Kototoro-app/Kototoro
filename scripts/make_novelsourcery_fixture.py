#!/usr/bin/env python3
"""Generate the NovelSourcery extension-store index protobuf fixtures.

Hand-encodes protobuf wire format using only the Python 3 standard library
(varint / length-delimited; tag = (field_number << 3) | wire_type) for a
trimmed copy of MihonExtensionStoreIndex, plus unknown top-level fields that
kotlinx.serialization.protobuf must skip while decoding (field 7000 / 8000 /
2000 / 6000).

Field numbers follow app/src/main/kotlin/org/skepsun/kototoro/extensions/repo/
MihonExtensionStoreIndex.kt.

Outputs (overwrites existing files; creates directories as needed):
  app/src/test/resources/fixtures/novelsourcery-index.protobuf
  app/src/test/resources/fixtures/novelsourcery-index.protobuf.gz
"""

import gzip
from pathlib import Path

WIRE_VARINT = 0
WIRE_LENGTH_DELIMITED = 2

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURE_DIR = REPO_ROOT / "app" / "src" / "test" / "resources" / "fixtures"
RAW_PATH = FIXTURE_DIR / "novelsourcery-index.protobuf"
GZ_PATH = FIXTURE_DIR / "novelsourcery-index.protobuf.gz"


def varint(value: int) -> bytes:
    """Encode a non-negative integer as a protobuf base-128 varint."""
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def tag(field_number: int, wire_type: int) -> bytes:
    return varint((field_number << 3) | wire_type)


def field_varint(field_number: int, value: int) -> bytes:
    return tag(field_number, WIRE_VARINT) + varint(value)


def field_bytes(field_number: int, payload: bytes) -> bytes:
    return tag(field_number, WIRE_LENGTH_DELIMITED) + varint(len(payload)) + payload


def field_string(field_number: int, value: str) -> bytes:
    return field_bytes(field_number, value.encode("utf-8"))


def build_index() -> bytes:
    # Source: id=1, name=2, language=3, homeUrl=4, mirrorUrls=5 (repeated).
    source = (
        field_varint(1, 9001)
        + field_string(2, "Example Novel")
        + field_string(3, "en")
        + field_string(4, "https://example.org")
        + field_string(5, "https://mirror1.example.org")
        + field_string(5, "https://mirror2.example.org")
    )

    # Resources: apkUrl=1, iconUrl=2, jarUrl=501.
    resources = (
        field_string(1, "https://repo.example.org/novel-example.apk")
        + field_string(2, "/icons/novel-example.png")
        + field_string(501, "https://repo.example.org/novel-example.jar")
    )

    # Extension: name=1, packageName=2, resources=3, extensionLib=4,
    # versionCode=5, versionName=6, contentWarning=7 (1 = SAFE), sources=8.
    extension = (
        field_string(1, "Example Novel")
        + field_string(2, "eu.kanade.tachiyomi.extension.en.novel-example")
        + field_bytes(3, resources)
        + field_string(4, "1.6")
        + field_varint(5, 12)
        + field_string(6, "1.6.12")
        + field_varint(7, 1)
        + field_bytes(8, source)
    )

    # ExtensionList: extensions=1 (a single Extension entry).
    extension_list = field_bytes(1, extension)

    # Contact: website=1, discord=2.
    contact = (
        field_string(1, "https://example.org/repo")
        + field_string(2, "https://example.org/discord")
    )

    # Unknown top-level nested message payload 0x0A 0x02 0x01 0x02
    # (field 1, length 2, payload 0x01 0x02).
    unknown_nested = bytes((0x0A, 0x02, 0x01, 0x02))

    return (
        field_string(1, "NovelSourcery Test")
        + field_string(2, "novel")
        + field_string(3, "test-signing-key-hex")
        + field_bytes(4, contact)
        + field_bytes(101, extension_list)
        # Unknown fields appended at the top level: 7000=varint(1),
        # 8000=length-delimited, 2000=varint(42), 6000=nested message.
        + field_varint(7000, 1)
        + field_string(8000, "novel-extensions-marker")
        + field_varint(2000, 42)
        + field_bytes(6000, unknown_nested)
    )


def read_varint(data: bytes, pos: int) -> tuple:
    """Decode a varint starting at pos; returns (value, next_pos)."""
    result = 0
    shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def check_well_formed(data: bytes) -> None:
    """Walk the top-level message and assert every field stays in bounds."""
    pos = 0
    while pos < len(data):
        key, pos = read_varint(data, pos)
        field_number = key >> 3
        wire_type = key & 0x07
        if wire_type == WIRE_VARINT:
            _, pos = read_varint(data, pos)
        elif wire_type == WIRE_LENGTH_DELIMITED:
            length, pos = read_varint(data, pos)
            if pos + length > len(data):
                raise ValueError("truncated field %d" % field_number)
            pos += length
        else:
            raise ValueError("unexpected wire type %d for field %d" % (wire_type, field_number))
    if pos != len(data):
        raise ValueError("trailing garbage at offset %d" % pos)


def main() -> None:
    payload = build_index()
    check_well_formed(payload)
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    RAW_PATH.write_bytes(payload)
    with gzip.open(GZ_PATH, "wb", compresslevel=9) as output:
        output.write(payload)
    print("wrote %s (%d bytes)" % (RAW_PATH, RAW_PATH.stat().st_size))
    print("wrote %s (%d bytes)" % (GZ_PATH, GZ_PATH.stat().st_size))


if __name__ == "__main__":
    main()
