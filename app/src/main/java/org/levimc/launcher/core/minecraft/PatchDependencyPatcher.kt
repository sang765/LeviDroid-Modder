package org.levimc.launcher.core.minecraft

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Makes an extracted patch build (MCPatch etc.) link in the launcher.
 *
 * Those builds inject a loader lib (libmtbinloader2.so) that ships without
 * DT_SONAME, so libminecraftpe.so's by-name DT_NEEDED lookup never resolves:
 * System.load registers it under the full path, and the version lib dir is
 * not a search path of the app namespace (in the installed patch app the
 * loader sits in nativeLibraryDir next to libminecraftpe.so, where the
 * search succeeds). The loader is preloaded via System.load before
 * libminecraftpe.so anyway - the same init order the installed app gets from
 * the package manager - and the game imports no symbol it defines, so the
 * dependency edge is dropped: bionic never resolves the name.
 *
 * Also repairs loader libs damaged by the previous DT_SONAME patch attempt,
 * which wrote the soname string outside the .dynstr section window bionic
 * bounds checks get_string against (loader_phdr.cpp), aborting the loader:
 * the out-of-window entry is neutralized and the padding bytes after the
 * dynamic entries - always zero before the patch - are zeroed again.
 *
 * Everything scans the dynamic entries as they are and writes single fields,
 * so it works on any file layout and is idempotent.
 */
internal object PatchDependencyPatcher {

    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_SONAME = 14L
    private const val SHT_DYNSYM = 11L
    private const val SHT_STRTAB = 3L
    private const val SH_OFFSET = 0
    private const val SH_SIZE = 1
    private const val SH_ENTSIZE = 2
    // Outside every DT_* range: bionic logs and ignores unknown entries.
    private const val DT_DROPPED = 0x7abc0001L

    /**
     * Returns null when the DT_NEEDED entry is gone (dropped now or on an
     * earlier launch), else the reason it was kept.
     */
    fun dropUnusedNeeded(game: File, loader: File, neededName: String): String? {
        if (!game.isFile || game.length() < 64) return "game lib missing"
        if (!loader.isFile || loader.length() < 64) return "loader lib missing"
        val shared = try {
            val loaderExports = RandomAccessFile(loader, "r").use { dynsymNames(it, defined = true) }
            val gameImports = RandomAccessFile(game, "r").use { dynsymNames(it, defined = false) }
            loaderExports.intersect(gameImports)
        } catch (error: Throwable) {
            return "symbol scan failed: ${error.message ?: error.javaClass.simpleName}"
        }
        if (shared.isNotEmpty()) return "game imports ${shared.first()} from the loader"
        return dropNeeded(game, neededName)
    }

    /** True when an out-of-window DT_SONAME from the previous patch was neutralized. */
    fun repairInjectedSoname(file: File, soname: String): Boolean {
        if (!file.isFile || file.length() < 64) return false
        val wasWritable = file.canWrite()
        if (!wasWritable) file.setWritable(true, true)
        try {
            RandomAccessFile(file, "rw").use { raf -> return repair(raf, soname) }
        } finally {
            if (!wasWritable) file.setReadOnly()
        }
    }

    private fun dropNeeded(raf: RandomAccessFile, neededName: String): String? {
        val ctx = readDynamic(raf) ?: return "no dynamic section"
        var offset = ctx.dynOff
        while (offset + ctx.entrySize <= ctx.dynOff + ctx.dynSize) {
            val tag = ctx.readTag(offset)
            if (tag == DT_NULL) return "DT_NEEDED entry not found"
            if (tag == DT_NEEDED || tag == DT_DROPPED) {
                val nameOffset = ctx.readValue(offset)
                if (nameOffset >= 0 && readString(raf, ctx.strtabOff + nameOffset) == neededName) {
                    if (tag == DT_DROPPED) return null
                    ctx.writeTag(offset, DT_DROPPED)
                    return null
                }
            }
            offset += ctx.entrySize
        }
        return "dynamic entries run past the section"
    }

    private fun repair(raf: RandomAccessFile, soname: String): Boolean {
        val ctx = readDynamic(raf) ?: return false
        // A legit soname is a dynstr index; the injected one is not.
        val dynstrSize = sectionSizeOfType(raf, SHT_STRTAB, ctx.strtabOff)
        var offset = ctx.dynOff
        var repaired = false
        while (offset + ctx.entrySize <= ctx.dynOff + ctx.dynSize) {
            val tag = ctx.readTag(offset)
            if (tag == DT_NULL) break
            if (tag == DT_SONAME && dynstrSize >= 0 && ctx.readValue(offset) >= dynstrSize) {
                ctx.writeTag(offset, DT_DROPPED)
                repaired = true
            }
            offset += ctx.entrySize
        }
        // .18 wrote the string into the zero padding right after the entries.
        val at = ctx.dynEnd
        if (readString(raf, at) == soname) {
            zeroString(raf, at, soname.length + 1)
            repaired = true
        }
        return repaired
    }

    private fun dynsymNames(raf: RandomAccessFile, defined: Boolean): Set<String> {
        val is64 = readIdent(raf) ?: throw IOException("not an ELF file")
        val shOff: Long
        val shEntSize: Int
        val shNum: Int
        if (is64) {
            shOff = readU64(raf, 40)
            shEntSize = readU16(raf, 58)
            shNum = readU16(raf, 60)
        } else {
            shOff = readU32(raf, 32).toLong()
            shEntSize = readU16(raf, 46)
            shNum = readU16(raf, 48)
        }
        if (shOff <= 0 || shNum <= 0 || shEntSize < if (is64) 64 else 40) {
            throw IOException("no section headers")
        }
        var symOff = -1L
        var symSize = 0L
        var strIndex = -1
        var symEnt = if (is64) 24L else 16L
        for (i in 0 until shNum) {
            val off = shOff + i.toLong() * shEntSize
            if (readU32(raf, off + 4) == SHT_DYNSYM && symOff < 0) {
                symOff = sectionField(raf, is64, off, SH_OFFSET)
                symSize = sectionField(raf, is64, off, SH_SIZE)
                strIndex = (if (is64) readU32(raf, off + 40) else readU32(raf, off + 24)).toInt()
                val entsize = sectionField(raf, is64, off, SH_ENTSIZE)
                if (entsize > 0) symEnt = entsize
            }
        }
        if (symOff < 0 || strIndex < 0 || strIndex >= shNum) throw IOException("no .dynsym")
        val strdr = shOff + strIndex.toLong() * shEntSize
        if (readU32(raf, strdr + 4) != SHT_STRTAB) throw IOException("dynsym does not link to .dynstr")
        val strOff = sectionField(raf, is64, strdr, SH_OFFSET)

        val count = symSize / symEnt
        val names = HashSet<String>()
        for (i in 1 until count) {
            val off = symOff + i * symEnt
            val stName = readU32(raf, off)
            if (stName == 0L) continue
            val infoOffset = if (is64) 4L else 12L
            val shndxOffset = if (is64) 6L else 14L
            val bind = readU8(raf, off + infoOffset) shr 4
            val shndx = readU16(raf, off + shndxOffset)
            val isDefined = shndx != 0
            if (isDefined == defined && (bind == 1 || bind == 2)) {
                names.add(readString(raf, strOff + stName))
            }
        }
        return names
    }

    private class Dynamic(
        val raf: RandomAccessFile,
        val is64: Boolean,
        val dynOff: Long,
        val dynSize: Long,
        val entrySize: Int,
        val dynEnd: Long,
        val strtabOff: Long,
    ) {
        fun readTag(offset: Long): Long = if (is64) readU64(raf, offset) else readU32(raf, offset)
        fun readValue(offset: Long): Long = if (is64) {
            readU64(raf, offset + 8)
        } else {
            readU32(raf, offset + 4)
        }

        fun writeTag(offset: Long, tag: Long) {
            if (is64) {
                writeU64(raf, offset, tag)
            } else {
                writeU32(raf, offset, tag)
            }
        }
    }

    /**
     * Parses the ELF identity, PT_DYNAMIC and the file offset of dynstr.
     * Returns null when the file has no usable dynamic section.
     */
    private fun readDynamic(raf: RandomAccessFile): Dynamic? {
        val is64 = readIdent(raf) ?: return null
        val phOff: Long
        val phEntSize: Int
        val phNum: Int
        if (is64) {
            phOff = readU64(raf, 32)
            phEntSize = readU16(raf, 54)
            phNum = readU16(raf, 56)
        } else {
            phOff = readU32(raf, 28)
            phEntSize = readU16(raf, 42)
            phNum = readU16(raf, 44)
        }
        if (phOff <= 0 || phNum <= 0 || phEntSize < if (is64) 56 else 32) return null
        var dynOff = -1L
        var dynSize = 0L
        val loads = ArrayList<Triple<Long, Long, Long>>()
        for (i in 0 until phNum) {
            val off = phOff + i.toLong() * phEntSize
            when (readU32(raf, off)) {
                2L -> { // PT_DYNAMIC
                    dynOff = if (is64) readU64(raf, off + 8) else readU32(raf, off + 4)
                    dynSize = if (is64) readU64(raf, off + 32) else readU32(raf, off + 16)
                }
                1L -> { // PT_LOAD
                    val fileOff = if (is64) readU64(raf, off + 8) else readU32(raf, off + 4)
                    val vaddr = if (is64) readU64(raf, off + 16) else readU32(raf, off + 8)
                    val filesz = if (is64) readU64(raf, off + 32) else readU32(raf, off + 16)
                    loads.add(Triple(vaddr, fileOff, filesz))
                }
            }
        }
        if (dynOff < 0 || dynSize < if (is64) 16 else 8) return null
        val entrySize = if (is64) 16 else 8
        var strtabVaddr = -1L
        var offset = dynOff
        while (offset + entrySize <= dynOff + dynSize) {
            val tag = if (is64) readU64(raf, offset) else readU32(raf, offset)
            if (tag == DT_NULL) break
            if (tag == DT_STRTAB) {
                strtabVaddr = if (is64) readU64(raf, offset + 8) else readU32(raf, offset + 4)
            }
            offset += entrySize
        }
        if (strtabVaddr < 0) return null
        for ((vaddr, fileOff, filesz) in loads) {
            if (strtabVaddr in vaddr until vaddr + filesz) {
                return Dynamic(raf, is64, dynOff, dynSize, entrySize, dynOff + dynSize, fileOff + (strtabVaddr - vaddr))
            }
        }
        return null
    }

    /** sh_size of the first section of the given type whose sh_offset matches, or -1. */
    private fun sectionSizeOfType(raf: RandomAccessFile, type: Long, fileOffset: Long): Long {
        val is64 = readIdent(raf) ?: return -1
        val shOff: Long
        val shEntSize: Int
        val shNum: Int
        if (is64) {
            shOff = readU64(raf, 40)
            shEntSize = readU16(raf, 58)
            shNum = readU16(raf, 60)
        } else {
            shOff = readU32(raf, 32)
            shEntSize = readU16(raf, 46)
            shNum = readU16(raf, 48)
        }
        if (shOff <= 0 || shNum <= 0 || shEntSize < if (is64) 64 else 40) return -1
        for (i in 0 until shNum) {
            val off = shOff + i.toLong() * shEntSize
            if (readU32(raf, off + 4) == type && sectionField(raf, is64, off, SH_OFFSET) == fileOffset) {
                return sectionField(raf, is64, off, SH_SIZE)
            }
        }
        return -1
    }

    /** [field] of an Elf64/Elf32 section header at [off]. */
    private fun sectionField(raf: RandomAccessFile, is64: Boolean, off: Long, field: Int): Long {
        val delta = if (is64) {
            when (field) {
                SH_OFFSET -> 24L
                SH_SIZE -> 32L
                else -> 56L
            }
        } else {
            when (field) {
                SH_OFFSET -> 16L
                SH_SIZE -> 20L
                else -> 36L
            }
        }
        return if (is64) readU64(raf, off + delta) else readU32(raf, off + delta)
    }

    private fun readIdent(raf: RandomAccessFile): Boolean? {
        raf.seek(0)
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() ||
            magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()
        ) {
            return null
        }
        raf.seek(4)
        return when (raf.read()) {
            1 -> false // ELF32
            2 -> true // ELF64
            else -> null
        }
    }

    private fun readString(raf: RandomAccessFile, at: Long): String {
        if (at < 0 || at >= raf.length()) return ""
        val max = minOf(256L, raf.length() - at).toInt()
        raf.seek(at)
        val buf = ByteArray(max)
        raf.readFully(buf)
        val end = buf.indexOf(0)
        return String(buf, 0, if (end >= 0) end else buf.size, Charsets.US_ASCII)
    }

    private fun zeroString(raf: RandomAccessFile, at: Long, length: Int) {
        if (at < 0 || at + length > raf.length()) return
        raf.seek(at)
        raf.write(ByteArray(length))
    }

    private fun readU8(raf: RandomAccessFile, at: Long): Int {
        raf.seek(at)
        return raf.read()
    }

    private fun readU16(raf: RandomAccessFile, at: Long): Int {
        raf.seek(at)
        return raf.read() or (raf.read() shl 8)
    }

    private fun readU32(raf: RandomAccessFile, at: Long): Long {
        raf.seek(at)
        var value = 0L
        for (shift in 0..24 step 8) {
            value = value or (raf.read().toLong() shl shift)
        }
        return value
    }

    private fun readU64(raf: RandomAccessFile, at: Long): Long {
        raf.seek(at)
        var value = 0L
        for (shift in 0..56 step 8) {
            value = value or (raf.read().toLong() shl shift)
        }
        return value
    }

    private fun writeU32(raf: RandomAccessFile, at: Long, value: Long) {
        raf.seek(at)
        for (shift in 0..24 step 8) {
            raf.write(((value ushr shift) and 0xff).toInt())
        }
    }

    private fun writeU64(raf: RandomAccessFile, at: Long, value: Long) {
        raf.seek(at)
        for (shift in 0..56 step 8) {
            raf.write(((value ushr shift) and 0xff).toInt())
        }
    }
}
