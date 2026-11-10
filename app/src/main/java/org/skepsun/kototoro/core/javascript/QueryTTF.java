package org.skepsun.kototoro.core.javascript;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;

@Keep
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class QueryTTF implements LegadoQueryTtf {
    private static class Header {
        public long sfntVersion;
        public int numTables;
        public int searchRange;
        public int entrySelector;
        public int rangeShift;
    }

    private static class Directory {
        public String tableTag;
        public int checkSum;
        public int offset;
        public int length;
    }

    private static class NameLayout {
        public int format;
        public int count;
        public int stringOffset;
        public LinkedList<NameRecord> records = new LinkedList<>();
    }

    private static class NameRecord {
        public int platformID;
        public int encodingID;
        public int languageID;
        public int nameID;
        public int length;
        public int offset;
    }

    private static class HeadLayout {
        public int majorVersion;
        public int minorVersion;
        public int fontRevision;
        public int checkSumAdjustment;
        public int magicNumber;
        public int flags;
        public int unitsPerEm;
        public long created;
        public long modified;
        public short xMin;
        public short yMin;
        public short xMax;
        public short yMax;
        public int macStyle;
        public int lowestRecPPEM;
        public short fontDirectionHint;
        public short indexToLocFormat;
        public short glyphDataFormat;
    }

    private static class MaxpLayout {
        public int version;
        public int numGlyphs;
        public int maxPoints;
        public int maxContours;
        public int maxCompositePoints;
        public int maxCompositeContours;
        public int maxZones;
        public int maxTwilightPoints;
        public int maxStorage;
        public int maxFunctionDefs;
        public int maxInstructionDefs;
        public int maxStackElements;
        public int maxSizeOfInstructions;
        public int maxComponentElements;
        public int maxComponentDepth;
    }

    private static class CmapLayout {
        public int version;
        public int numTables;
        public LinkedList<CmapRecord> records = new LinkedList<>();
        public HashMap<Integer, CmapFormat> tables = new HashMap<>();
    }

    private static class CmapRecord {
        public int platformID;
        public int encodingID;
        public int offset;
    }

    private static class CmapFormat {
        public int format;
        public int length;
        public int language;
        public int[] subHeaderKeys;
        public int[] subHeaders;
        public int segCountX2;
        public int searchRange;
        public int entrySelector;
        public int rangeShift;
        public int[] endCode;
        public int reservedPad;
        public int[] startCode;
        public int[] idDelta;
        public int[] idRangeOffsets;
        public int firstCode;
        public int entryCount;
        public int[] glyphIdArray;
    }

    private static class GlyfLayout {
        public short numberOfContours;
        public short xMin;
        public short yMin;
        public short xMax;
        public short yMax;
        public GlyphTableBySimple glyphSimple;
        public LinkedList<GlyphTableComponent> glyphComponent;
    }

    private static class GlyphTableBySimple {
        int[] endPtsOfContours;
        int instructionLength;
        int[] instructions;
        int[] flags;
        int[] xCoordinates;
        int[] yCoordinates;
    }

    private static class GlyphTableComponent {
        int flags;
        int glyphIndex;
        int argument1;
        int argument2;
        float xScale;
        float scale01;
        float scale10;
        float yScale;
    }

    private static class BufferReader {
        private final ByteBuffer byteBuffer;

        BufferReader(byte[] buffer, int index) {
            this.byteBuffer = ByteBuffer.wrap(buffer);
            this.byteBuffer.order(ByteOrder.BIG_ENDIAN);
            this.byteBuffer.position(index);
        }

        void position(int index) {
            byteBuffer.position(index);
        }

        long ReadUInt64() {
            return byteBuffer.getLong();
        }

        int ReadUInt32() {
            return byteBuffer.getInt();
        }

        int ReadUInt16() {
            return byteBuffer.getShort() & 0xFFFF;
        }

        short ReadInt16() {
            return byteBuffer.getShort();
        }

        short ReadUInt8() {
            return (short) (byteBuffer.get() & 0xFF);
        }

        byte ReadInt8() {
            return byteBuffer.get();
        }

        byte[] ReadByteArray(int len) {
            byte[] result = new byte[len];
            byteBuffer.get(result);
            return result;
        }

        int[] ReadUInt8Array(int len) {
            int[] result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.get() & 0xFF;
            return result;
        }

        int[] ReadInt16Array(int len) {
            int[] result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.getShort();
            return result;
        }

        int[] ReadUInt16Array(int len) {
            int[] result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.getShort() & 0xFFFF;
            return result;
        }

        int[] ReadInt32Array(int len) {
            int[] result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.getInt();
            return result;
        }
    }

    private final Header fileHeader = new Header();
    private final HashMap<String, Directory> directorys = new HashMap<>();
    private final NameLayout name = new NameLayout();
    private final HeadLayout head = new HeadLayout();
    private final MaxpLayout maxp = new MaxpLayout();
    private final CmapLayout Cmap = new CmapLayout();

    private int[] loca;
    private GlyfLayout[] glyfArray;

    public final HashMap<Integer, String> unicodeToGlyph = new HashMap<>();
    public final HashMap<String, Integer> glyphToUnicode = new HashMap<>();
    public final HashMap<Integer, Integer> unicodeToGlyphId = new HashMap<>();

    private void readNameTable(byte[] buffer) {
        Directory dataTable = directorys.get("name");
        if (dataTable == null) return;
        BufferReader reader = new BufferReader(buffer, dataTable.offset);
        name.format = reader.ReadUInt16();
        name.count = reader.ReadUInt16();
        name.stringOffset = reader.ReadUInt16();
        for (int i = 0; i < name.count; ++i) {
            NameRecord record = new NameRecord();
            record.platformID = reader.ReadUInt16();
            record.encodingID = reader.ReadUInt16();
            record.languageID = reader.ReadUInt16();
            record.nameID = reader.ReadUInt16();
            record.length = reader.ReadUInt16();
            record.offset = reader.ReadUInt16();
            name.records.add(record);
        }
    }

    private void readHeadTable(byte[] buffer) {
        Directory dataTable = directorys.get("head");
        if (dataTable == null) return;
        BufferReader reader = new BufferReader(buffer, dataTable.offset);
        head.majorVersion = reader.ReadUInt16();
        head.minorVersion = reader.ReadUInt16();
        head.fontRevision = reader.ReadUInt32();
        head.checkSumAdjustment = reader.ReadUInt32();
        head.magicNumber = reader.ReadUInt32();
        head.flags = reader.ReadUInt16();
        head.unitsPerEm = reader.ReadUInt16();
        head.created = reader.ReadUInt64();
        head.modified = reader.ReadUInt64();
        head.xMin = reader.ReadInt16();
        head.yMin = reader.ReadInt16();
        head.xMax = reader.ReadInt16();
        head.yMax = reader.ReadInt16();
        head.macStyle = reader.ReadUInt16();
        head.lowestRecPPEM = reader.ReadUInt16();
        head.fontDirectionHint = reader.ReadInt16();
        head.indexToLocFormat = reader.ReadInt16();
        head.glyphDataFormat = reader.ReadInt16();
    }

    private void readLocaTable(byte[] buffer) {
        Directory dataTable = directorys.get("loca");
        if (dataTable == null) return;
        BufferReader reader = new BufferReader(buffer, dataTable.offset);
        if (head.indexToLocFormat == 0) {
            loca = reader.ReadUInt16Array(dataTable.length / 2);
            for (int i = 0; i < loca.length; i++) loca[i] *= 2;
        } else {
            loca = reader.ReadInt32Array(dataTable.length / 4);
        }
    }

    private void readCmapTable(byte[] buffer) {
        Directory dataTable = directorys.get("cmap");
        if (dataTable == null) return;
        BufferReader reader = new BufferReader(buffer, dataTable.offset);
        Cmap.version = reader.ReadUInt16();
        Cmap.numTables = reader.ReadUInt16();
        for (int i = 0; i < Cmap.numTables; ++i) {
            CmapRecord record = new CmapRecord();
            record.platformID = reader.ReadUInt16();
            record.encodingID = reader.ReadUInt16();
            record.offset = reader.ReadUInt32();
            Cmap.records.add(record);
        }

        for (CmapRecord formatTable : Cmap.records) {
            int fmtOffset = formatTable.offset;
            if (Cmap.tables.containsKey(fmtOffset)) continue;
            reader.position(dataTable.offset + fmtOffset);

            CmapFormat f = new CmapFormat();
            f.format = reader.ReadUInt16();
            f.length = reader.ReadUInt16();
            f.language = reader.ReadUInt16();
            switch (f.format) {
                case 0:
                    f.glyphIdArray = reader.ReadUInt8Array(f.length - 6);
                    for (int unicode = 0; unicode < f.glyphIdArray.length; unicode++) {
                        if (f.glyphIdArray[unicode] == 0) continue;
                        unicodeToGlyphId.put(unicode, f.glyphIdArray[unicode]);
                    }
                    break;
                case 4:
                    f.segCountX2 = reader.ReadUInt16();
                    int segCount = f.segCountX2 / 2;
                    f.searchRange = reader.ReadUInt16();
                    f.entrySelector = reader.ReadUInt16();
                    f.rangeShift = reader.ReadUInt16();
                    f.endCode = reader.ReadUInt16Array(segCount);
                    f.reservedPad = reader.ReadUInt16();
                    f.startCode = reader.ReadUInt16Array(segCount);
                    f.idDelta = reader.ReadInt16Array(segCount);
                    f.idRangeOffsets = reader.ReadUInt16Array(segCount);
                    int glyphIdArrayLength = (f.length - 16 - (segCount * 8)) / 2;
                    f.glyphIdArray = reader.ReadUInt16Array(glyphIdArrayLength);

                    for (int segmentIndex = 0; segmentIndex < segCount; segmentIndex++) {
                        int unicodeInclusive = f.startCode[segmentIndex];
                        int unicodeExclusive = f.endCode[segmentIndex];
                        int idDelta = f.idDelta[segmentIndex];
                        int idRangeOffset = f.idRangeOffsets[segmentIndex];
                        for (int unicode = unicodeInclusive; unicode <= unicodeExclusive; unicode++) {
                            int glyphId;
                            if (idRangeOffset == 0) {
                                glyphId = (unicode + idDelta) & 0xFFFF;
                            } else {
                                int gIndex = (idRangeOffset / 2) + unicode - unicodeInclusive + segmentIndex - segCount;
                                glyphId = gIndex < glyphIdArrayLength ? f.glyphIdArray[gIndex] + idDelta : 0;
                            }
                            if (glyphId == 0) continue;
                            unicodeToGlyphId.put(unicode, glyphId);
                        }
                    }
                    break;
                case 6:
                    f.firstCode = reader.ReadUInt16();
                    f.entryCount = reader.ReadUInt16();
                    f.glyphIdArray = reader.ReadUInt16Array(f.entryCount);
                    int unicodeIndex = f.firstCode;
                    for (int gIndex = 0; gIndex < f.entryCount; gIndex++) {
                        unicodeToGlyphId.put(unicodeIndex, f.glyphIdArray[gIndex]);
                        unicodeIndex++;
                    }
                    break;
                default:
                    break;
            }
            Cmap.tables.put(fmtOffset, f);
        }
    }

    private void readMaxpTable(byte[] buffer) {
        Directory dataTable = directorys.get("maxp");
        if (dataTable == null) return;
        BufferReader reader = new BufferReader(buffer, dataTable.offset);
        maxp.version = reader.ReadUInt32();
        maxp.numGlyphs = reader.ReadUInt16();
        maxp.maxPoints = reader.ReadUInt16();
        maxp.maxContours = reader.ReadUInt16();
        maxp.maxCompositePoints = reader.ReadUInt16();
        maxp.maxCompositeContours = reader.ReadUInt16();
        maxp.maxZones = reader.ReadUInt16();
        maxp.maxTwilightPoints = reader.ReadUInt16();
        maxp.maxStorage = reader.ReadUInt16();
        maxp.maxFunctionDefs = reader.ReadUInt16();
        maxp.maxInstructionDefs = reader.ReadUInt16();
        maxp.maxStackElements = reader.ReadUInt16();
        maxp.maxSizeOfInstructions = reader.ReadUInt16();
        maxp.maxComponentElements = reader.ReadUInt16();
        maxp.maxComponentDepth = reader.ReadUInt16();
    }

    private void readGlyfTable(byte[] buffer) {
        Directory dataTable = directorys.get("glyf");
        if (dataTable == null) return;
        int glyfCount = maxp.numGlyphs;
        glyfArray = new GlyfLayout[glyfCount];
        BufferReader reader = new BufferReader(buffer, 0);
        for (int index = 0; index < glyfCount; index++) {
            if (loca[index] == loca[index + 1]) continue;
            int offset = dataTable.offset + loca[index];
            GlyfLayout glyph = new GlyfLayout();
            reader.position(offset);
            glyph.numberOfContours = reader.ReadInt16();
            if (glyph.numberOfContours > maxp.maxContours) continue;
            glyph.xMin = reader.ReadInt16();
            glyph.yMin = reader.ReadInt16();
            glyph.xMax = reader.ReadInt16();
            glyph.yMax = reader.ReadInt16();
            if (glyph.numberOfContours == 0) continue;
            if (glyph.numberOfContours > 0) {
                glyph.glyphSimple = new GlyphTableBySimple();
                glyph.glyphSimple.endPtsOfContours = reader.ReadUInt16Array(glyph.numberOfContours);
                glyph.glyphSimple.instructionLength = reader.ReadUInt16();
                glyph.glyphSimple.instructions = reader.ReadUInt8Array(glyph.glyphSimple.instructionLength);
                int flagLength = glyph.glyphSimple.endPtsOfContours[glyph.glyphSimple.endPtsOfContours.length - 1] + 1;
                glyph.glyphSimple.flags = new int[flagLength];
                for (int n = 0; n < flagLength; ++n) {
                    int glyphSimpleFlag = reader.ReadUInt8();
                    glyph.glyphSimple.flags[n] = glyphSimpleFlag;
                    if ((glyphSimpleFlag & 0x08) == 0x08) {
                        for (int m = reader.ReadUInt8(); m > 0; --m) {
                            glyph.glyphSimple.flags[++n] = glyphSimpleFlag;
                        }
                    }
                }
                glyph.glyphSimple.xCoordinates = new int[flagLength];
                for (int n = 0; n < flagLength; ++n) {
                    switch (glyph.glyphSimple.flags[n] & 0x12) {
                        case 0x02:
                            glyph.glyphSimple.xCoordinates[n] = -1 * reader.ReadUInt8();
                            break;
                        case 0x12:
                            glyph.glyphSimple.xCoordinates[n] = reader.ReadUInt8();
                            break;
                        case 0x10:
                            glyph.glyphSimple.xCoordinates[n] = 0;
                            break;
                        case 0x00:
                            glyph.glyphSimple.xCoordinates[n] = reader.ReadInt16();
                            break;
                        default:
                            break;
                    }
                }
                glyph.glyphSimple.yCoordinates = new int[flagLength];
                for (int n = 0; n < flagLength; ++n) {
                    switch (glyph.glyphSimple.flags[n] & 0x24) {
                        case 0x04:
                            glyph.glyphSimple.yCoordinates[n] = -1 * reader.ReadUInt8();
                            break;
                        case 0x24:
                            glyph.glyphSimple.yCoordinates[n] = reader.ReadUInt8();
                            break;
                        case 0x20:
                            glyph.glyphSimple.yCoordinates[n] = 0;
                            break;
                        case 0x00:
                            glyph.glyphSimple.yCoordinates[n] = reader.ReadInt16();
                            break;
                        default:
                            break;
                    }
                }
            } else {
                glyph.glyphComponent = new LinkedList<>();
                while (true) {
                    GlyphTableComponent glyphTableComponent = new GlyphTableComponent();
                    glyphTableComponent.flags = reader.ReadUInt16();
                    glyphTableComponent.glyphIndex = reader.ReadUInt16();
                    switch (glyphTableComponent.flags & 0b11) {
                        case 0b00:
                            glyphTableComponent.argument1 = reader.ReadUInt8();
                            glyphTableComponent.argument2 = reader.ReadUInt8();
                            break;
                        case 0b10:
                            glyphTableComponent.argument1 = reader.ReadInt8();
                            glyphTableComponent.argument2 = reader.ReadInt8();
                            break;
                        case 0b01:
                            glyphTableComponent.argument1 = reader.ReadUInt16();
                            glyphTableComponent.argument2 = reader.ReadUInt16();
                            break;
                        case 0b11:
                            glyphTableComponent.argument1 = reader.ReadInt16();
                            glyphTableComponent.argument2 = reader.ReadInt16();
                            break;
                        default:
                            break;
                    }
                    switch (glyphTableComponent.flags & 0b11001000) {
                        case 0b00001000:
                            glyphTableComponent.yScale = glyphTableComponent.xScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            break;
                        case 0b01000000:
                            glyphTableComponent.xScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            glyphTableComponent.yScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            break;
                        case 0b10000000:
                            glyphTableComponent.xScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            glyphTableComponent.scale01 = ((float) reader.ReadUInt16()) / 16384.0f;
                            glyphTableComponent.scale10 = ((float) reader.ReadUInt16()) / 16384.0f;
                            glyphTableComponent.yScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            break;
                        default:
                            break;
                    }
                    glyph.glyphComponent.add(glyphTableComponent);
                    if ((glyphTableComponent.flags & 0x20) == 0) break;
                }
            }
            glyfArray[index] = glyph;
        }
    }

    public String getGlyfById(int glyfId) {
        GlyfLayout glyph = glyfArray[glyfId];
        if (glyph == null) return null;
        if (glyph.numberOfContours >= 0) {
            int dataCount = glyph.glyphSimple.flags.length;
            String[] coordinateArray = new String[dataCount];
            for (int i = 0; i < dataCount; i++) {
                coordinateArray[i] = glyph.glyphSimple.xCoordinates[i] + "," + glyph.glyphSimple.yCoordinates[i];
            }
            return String.join("|", coordinateArray);
        } else {
            LinkedList<String> glyphIdList = new LinkedList<>();
            for (GlyphTableComponent g : glyph.glyphComponent) {
                glyphIdList.add("{" +
                        "flags:" + g.flags + "," +
                        "glyphIndex:" + g.glyphIndex + "," +
                        "arg1:" + g.argument1 + "," +
                        "arg2:" + g.argument2 + "," +
                        "xScale:" + g.xScale + "," +
                        "scale01:" + g.scale01 + "," +
                        "scale10:" + g.scale10 + "," +
                        "yScale:" + g.yScale + "}");
            }
            return "[" + String.join(",", glyphIdList) + "]";
        }
    }

    public QueryTTF(final byte[] buffer) {
        BufferReader fontReader = new BufferReader(buffer, 0);
        fileHeader.sfntVersion = fontReader.ReadUInt32();
        fileHeader.numTables = fontReader.ReadUInt16();
        fileHeader.searchRange = fontReader.ReadUInt16();
        fileHeader.entrySelector = fontReader.ReadUInt16();
        fileHeader.rangeShift = fontReader.ReadUInt16();
        for (int i = 0; i < fileHeader.numTables; ++i) {
            Directory d = new Directory();
            d.tableTag = new String(fontReader.ReadByteArray(4), StandardCharsets.US_ASCII);
            d.checkSum = fontReader.ReadUInt32();
            d.offset = fontReader.ReadUInt32();
            d.length = fontReader.ReadUInt32();
            directorys.put(d.tableTag, d);
        }

        readNameTable(buffer);
        readHeadTable(buffer);
        readCmapTable(buffer);
        readLocaTable(buffer);
        readMaxpTable(buffer);
        readGlyfTable(buffer);
        int glyfArrayLength = glyfArray.length;
        for (var item : unicodeToGlyphId.entrySet()) {
            int key = item.getKey();
            int val = item.getValue();
            if (val >= glyfArrayLength) continue;
            String glyfString = getGlyfById(val);
            unicodeToGlyph.put(key, glyfString);
            if (glyfString == null) continue;
            glyphToUnicode.put(glyfString, key);
        }
    }

    public int getGlyfIdByUnicode(int unicode) {
        Integer result = unicodeToGlyphId.get(unicode);
        return result == null ? 0 : result;
    }

    public String getGlyfByUnicode(int unicode) {
        return unicodeToGlyph.get(unicode);
    }

    public int getUnicodeByGlyf(String glyph) {
        Integer result = glyphToUnicode.get(glyph);
        return result == null ? 0 : result;
    }

    public boolean isBlankUnicode(int unicode) {
        switch (unicode) {
            case 0x0009:
            case 0x0020:
            case 0x00A0:
            case 0x2002:
            case 0x2003:
            case 0x2007:
            case 0x200A:
            case 0x200B:
            case 0x200C:
            case 0x200D:
            case 0x202F:
            case 0x205F:
                return true;
            default:
                return false;
        }
    }
}
