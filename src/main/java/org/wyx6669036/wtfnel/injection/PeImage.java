package org.wyx6669036.wtfnel.injection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 托管 PE 解析器：头、节表、导出、导入、重定位、TLS，只读不写。
 * 移植自 WtfNel.Core/Injection/PeImage.cs。
 */
public final class PeImage {

    public static final int SCN_MEM_EXECUTE = 0x2000_0000;
    public static final int SCN_MEM_READ = 0x4000_0000;
    public static final int SCN_MEM_WRITE = 0x8000_0000;

    private static final int DOS_SIGNATURE = 0x5A4D;
    private static final long NT_SIGNATURE = 0x0000_4550L;
    private static final int PE32_MAGIC = 0x010B;
    private static final int PE32_PLUS_MAGIC = 0x020B;

    /** 一个节的元数据。 */
    public record PeSection(String name, long virtualAddress, long virtualSize, long rawOffset,
                            long rawSize, long characteristics) {
        public long fileSpan() {
            return rawSize != 0 ? rawSize : virtualSize;
        }

        public long mappedSpan() {
            return virtualSize != 0 ? virtualSize : rawSize;
        }

        public boolean isExecutable() {
            return (characteristics & SCN_MEM_EXECUTE) != 0;
        }

        public boolean isWritable() {
            return (characteristics & SCN_MEM_WRITE) != 0;
        }

        public boolean isReadable() {
            return (characteristics & SCN_MEM_READ) != 0;
        }
    }

    /** 一条导入符号。ordinal 为按序号导入时的序号，按名导入时 name 非空。 */
    public record PeImportSymbol(String name, int ordinal, boolean byOrdinal, long iatRva) {
    }

    /** 一个导入模块及其符号。 */
    public record PeImport(String module, List<PeImportSymbol> symbols) {
    }

    /** 一个重定位块。 */
    public record PeRelocationBlock(long pageRva, int[] entries) {
    }

    /** TLS 目录里注入器关心的字段。 */
    public record PeTlsInfo(long addressOfCallbacks, long addressOfCallbacksRva, long sizeOfZeroFill,
                            long characteristics) {
    }

    private final byte[] bytes;
    private final long ntOffset;
    private final int machine;
    private final int characteristics;
    private final int numberOfSections;
    private final int sizeOfOptionalHeader;
    private final boolean is64Bit;
    private final long imageBase;
    private final long entryPointRva;
    private final long sectionAlignment;
    private final long sizeOfImage;
    private final long sizeOfHeaders;
    private final PeSection[] sections;
    private final long[][] directories;

    private PeImage(byte[] bytes) {
        this.bytes = bytes;
        this.ntOffset = readU32(0x3C);

        long optional = ntOffset + 0x18;
        int magic = readU16(optional);
        this.is64Bit = magic == PE32_PLUS_MAGIC;
        this.machine = readU16(ntOffset + 4);
        this.characteristics = readU16(ntOffset + 0x16);
        this.numberOfSections = readU16(ntOffset + 6);
        this.sizeOfOptionalHeader = readU16(ntOffset + 0x14);

        this.entryPointRva = readU32(optional + 0x10);
        this.imageBase = is64Bit ? readU64(optional + 0x18) : readU32(optional + 0x1C);
        this.sectionAlignment = readU32(optional + 0x20);
        this.sizeOfImage = readU32(optional + 0x38);
        this.sizeOfHeaders = readU32(optional + 0x3C);

        long dirOffset = optional + (is64Bit ? 112 : 96);
        long[][] dirs = new long[16][];
        for (int i = 0; i < 16; i++) {
            long p = dirOffset + i * 8L;
            if (p + 8 > bytes.length) {
                dirs[i] = new long[]{0, 0};
                continue;
            }
            dirs[i] = new long[]{readU32(p), readU32(p + 4)};
        }
        this.directories = dirs;

        long sectionTable = optional + sizeOfOptionalHeader;
        PeSection[] parsed = new PeSection[numberOfSections];
        for (int i = 0; i < numberOfSections; i++) {
            long p = sectionTable + i * 40L;
            if (p + 40 > bytes.length) {
                break;
            }
            parsed[i] = new PeSection(
                    readName((int) p),
                    readU32(p + 12),
                    readU32(p + 8),
                    readU32(p + 20),
                    readU32(p + 16),
                    readU32(p + 36));
        }
        this.sections = parsed;
    }

    // ---------------------------------------------------------------- accessors

    public byte[] bytes() {
        return bytes;
    }

    public long ntOffset() {
        return ntOffset;
    }

    public int machine() {
        return machine;
    }

    public int characteristics() {
        return characteristics;
    }

    public int numberOfSections() {
        return numberOfSections;
    }

    public int sizeOfOptionalHeader() {
        return sizeOfOptionalHeader;
    }

    public boolean is64Bit() {
        return is64Bit;
    }

    public boolean isDll() {
        return (characteristics & 0x2000) != 0;
    }

    public long imageBase() {
        return imageBase;
    }

    public long entryPointRva() {
        return entryPointRva;
    }

    public long sectionAlignment() {
        return sectionAlignment;
    }

    public long sizeOfImage() {
        return sizeOfImage;
    }

    public long sizeOfHeaders() {
        return sizeOfHeaders;
    }

    public PeSection[] sections() {
        return sections;
    }

    public long[][] directories() {
        return directories;
    }

    public String archName() {
        return switch (machine) {
            case 0x8664 -> "x64 (64-bit)";
            case 0x014C -> "x86 (32-bit)";
            case 0xAA64 -> "arm64";
            case 0x01C4 -> "arm";
            default -> String.format("unknown(0x%x)", machine);
        };
    }

    // ---------------------------------------------------------------- parsing

    /** 解析结果：成功时 image 非空，失败时 error 说明原因。 */
    public record ParseResult(PeImage image, String error) {
        public boolean ok() {
            return image != null;
        }
    }

    public static ParseResult parse(byte[] file) {
        try {
            if (file.length < 0x40) {
                return new ParseResult(null, "文件太小，不是 PE");
            }
            if ((file[0] & 0xFF) != (DOS_SIGNATURE & 0xFF) || (file[1] & 0xFF) != ((DOS_SIGNATURE >> 8) & 0xFF)) {
                return new ParseResult(null, "缺少 MZ 头");
            }

            long nt = readU32(file, 0x3C);
            if (nt == 0 || nt + 0x18 + 2 > file.length) {
                return new ParseResult(null, "e_lfanew 越界");
            }
            if (readU32(file, (int) nt) != NT_SIGNATURE) {
                return new ParseResult(null, "缺少 PE 头");
            }

            int magic = readU16(file, (int) nt + 0x18);
            if (magic != PE32_MAGIC && magic != PE32_PLUS_MAGIC) {
                return new ParseResult(null, String.format("可选头 Magic 0x%x 不受支持", magic));
            }

            PeImage parsed = new PeImage(file);
            if (parsed.sizeOfImage == 0 || parsed.sections.length == 0) {
                return new ParseResult(null, "SizeOfImage/节表为空");
            }
            return new ParseResult(parsed, "");
        } catch (RuntimeException e) {
            return new ParseResult(null, "PE 解析异常: " + e.getMessage());
        }
    }

    public long rvaToOffset(long rva) {
        if (rva < sizeOfHeaders) {
            return rva;
        }
        for (PeSection s : sections) {
            if (s == null) {
                continue;
            }
            if (rva >= s.virtualAddress() && rva < s.virtualAddress() + s.fileSpan()) {
                return rva - s.virtualAddress() + s.rawOffset();
            }
        }
        return 0;
    }

    public PeSection sectionForRva(long rva) {
        for (PeSection s : sections) {
            if (s == null) {
                continue;
            }
            if (rva >= s.virtualAddress() && rva < s.virtualAddress() + Math.max(s.mappedSpan(), s.fileSpan())) {
                return s;
            }
        }
        return null;
    }

    /** 导出查找结果。forwarder 表示该导出其实是个转发字符串。 */
    public record Export(long rva, boolean forwarder) {
    }

    public Export findExport(String name) {
        long exportRva = directories[0][0];
        long exportSize = directories[0][1];
        if (exportRva == 0) {
            return null;
        }

        long dirOff = rvaToOffset(exportRva);
        if (dirOff == 0) {
            return null;
        }

        long numberOfNames = readU32(dirOff + 0x18);
        long addressOfFunctions = readU32(dirOff + 0x1C);
        long addressOfNames = readU32(dirOff + 0x20);
        long addressOfOrdinals = readU32(dirOff + 0x24);

        long namesOff = rvaToOffset(addressOfNames);
        long ordinalsOff = rvaToOffset(addressOfOrdinals);
        long funcsOff = rvaToOffset(addressOfFunctions);
        if (namesOff == 0 || ordinalsOff == 0 || funcsOff == 0) {
            return null;
        }

        for (long i = 0; i < numberOfNames; i++) {
            long nameRva = readU32(namesOff + i * 4);
            long nameOff = rvaToOffset(nameRva);
            if (nameOff == 0) {
                continue;
            }
            if (!readCString(nameOff).equals(name)) {
                continue;
            }

            int index = readU16(ordinalsOff + i * 2);
            long rva = readU32(funcsOff + (long) index * 4);
            boolean forwarder = rva >= exportRva && rva < exportRva + exportSize;
            return rva != 0 ? new Export(rva, forwarder) : null;
        }
        return null;
    }

    public List<PeImport> readImports() {
        List<PeImport> list = new ArrayList<>();
        long importRva = directories[1][0];
        if (importRva == 0) {
            return list;
        }

        long descOff = rvaToOffset(importRva);
        if (descOff == 0) {
            return list;
        }

        int thunkSize = is64Bit ? 8 : 4;
        long ordinalFlag = is64Bit ? 0x8000_0000_0000_0000L : 0x8000_0000L;

        for (long i = 0; ; i++) {
            long d = descOff + i * 20;
            if (d + 20 > bytes.length) {
                break;
            }
            long nameRva = readU32(d + 12);
            long firstThunk = readU32(d + 16);
            long originalThunk = readU32(d);
            if (nameRva == 0 && firstThunk == 0) {
                break;
            }

            long nameOff = rvaToOffset(nameRva);
            String module = nameOff != 0 ? readCString(nameOff) : String.format("<rva 0x%x>", nameRva);
            long thunkRva = originalThunk != 0 ? originalThunk : firstThunk;
            long thunkOff = rvaToOffset(thunkRva);

            List<PeImportSymbol> symbols = new ArrayList<>();
            if (thunkOff != 0) {
                for (long k = 0; ; k++) {
                    long value = is64Bit ? readU64(thunkOff + k * 8) : readU32(thunkOff + k * 4);
                    if (value == 0) {
                        break;
                    }

                    long iatRva = firstThunk + k * thunkSize;
                    if ((value & ordinalFlag) != 0) {
                        symbols.add(new PeImportSymbol(null, (int) (value & 0xFFFF), true, iatRva));
                    } else {
                        long hintNameOff = rvaToOffset(value & 0xFFFF_FFFFL);
                        String symbol = hintNameOff != 0 ? readCString(hintNameOff + 2)
                                : String.format("<rva 0x%x>", value & 0xFFFF_FFFFL);
                        symbols.add(new PeImportSymbol(symbol, 0, false, iatRva));
                    }
                    if (k > 0x10000) {
                        break;
                    }
                }
            }
            list.add(new PeImport(module, symbols));
            if (i > 0x1000) {
                break;
            }
        }
        return list;
    }

    public List<PeRelocationBlock> readRelocations() {
        List<PeRelocationBlock> list = new ArrayList<>();
        long relocRva = directories[5][0];
        long relocSize = directories[5][1];
        if (relocRva == 0 || relocSize == 0) {
            return list;
        }

        long off = rvaToOffset(relocRva);
        if (off == 0) {
            return list;
        }

        long end = off + relocSize;
        while (off + 8 <= end && off + 8 <= bytes.length) {
            long pageRva = readU32(off);
            long blockSize = readU32(off + 4);
            if (blockSize < 8 || off + blockSize > bytes.length) {
                break;
            }

            int count = (int) ((blockSize - 8) / 2);
            int[] entries = new int[count];
            for (int i = 0; i < count; i++) {
                entries[i] = readU16(off + 8 + (long) i * 2);
            }
            list.add(new PeRelocationBlock(pageRva, entries));
            off += blockSize;
        }
        return list;
    }

    public PeTlsInfo readTls() {
        long tlsRva = directories[9][0];
        long tlsSize = directories[9][1];
        if (tlsRva == 0 || tlsSize == 0) {
            return null;
        }

        long off = rvaToOffset(tlsRva);
        if (off == 0) {
            return null;
        }

        long callbacks = is64Bit ? readU64(off + 24) : readU32(off + 12);
        long callbacksRva = callbacks != 0 && callbacks >= imageBase ? callbacks - imageBase : 0;
        long zeroFill = readU32(off + (is64Bit ? 32 : 16));
        long characteristics = readU32(off + (is64Bit ? 36 : 20));
        return new PeTlsInfo(callbacks, callbacksRva, zeroFill, characteristics);
    }

    public static int protectionFor(PeSection section) {
        if (section.isExecutable()) {
            if (section.isWritable()) {
                return NativeApi.PAGE_EXECUTE_READWRITE;
            }
            return section.isReadable() ? NativeApi.PAGE_EXECUTE_READ : NativeApi.PAGE_EXECUTE_READWRITE;
        }
        return section.isWritable() ? NativeApi.PAGE_READWRITE : NativeApi.PAGE_READONLY;
    }

    // ---------------------------------------------------------------- low level

    private String readName(int offset) {
        int end = offset;
        while (end < offset + 8 && end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private String readCString(long offset) {
        if (offset >= bytes.length) {
            return "";
        }
        int start = (int) offset;
        int end = start;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, start, end - start, StandardCharsets.US_ASCII);
    }

    private int readU16(long offset) {
        return offset + 2 <= bytes.length ? readU16(bytes, (int) offset) : 0;
    }

    private long readU32(long offset) {
        return offset + 4 <= bytes.length ? readU32(bytes, (int) offset) : 0;
    }

    private long readU64(long offset) {
        return offset + 8 <= bytes.length ? readU64(bytes, (int) offset) : 0;
    }

    static int readU16(byte[] buffer, int offset) {
        if (offset < 0 || offset + 2 > buffer.length) {
            return 0;
        }
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    static long readU32(byte[] buffer, int offset) {
        if (offset < 0 || offset + 4 > buffer.length) {
            return 0;
        }
        return (buffer[offset] & 0xFFL)
                | ((buffer[offset + 1] & 0xFFL) << 8)
                | ((buffer[offset + 2] & 0xFFL) << 16)
                | ((buffer[offset + 3] & 0xFFL) << 24);
    }

    static long readU64(byte[] buffer, int offset) {
        if (offset < 0 || offset + 8 > buffer.length) {
            return 0;
        }
        long low = readU32(buffer, offset);
        long high = readU32(buffer, offset + 4);
        return low | (high << 32);
    }

    static void writeU32(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    static void writeU64(byte[] buffer, int offset, long value) {
        writeU32(buffer, offset, value & 0xFFFF_FFFFL);
        writeU32(buffer, offset + 4, (value >>> 32) & 0xFFFF_FFFFL);
    }
}
