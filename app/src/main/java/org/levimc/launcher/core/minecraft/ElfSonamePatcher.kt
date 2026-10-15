package org.levimc.launcher.core.minecraft

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gives an extracted patch-loader lib a DT_SONAME it was built without.
 *
 * MCPatch-style loader libs (libmtbinloader2.so) ship with no DT_SONAME,
 * so System.load'ing them by absolute path registers them under the full
 * path and libminecraftpe.so's DT_NEEDED lookup by name misses them - the
 * version lib dir is not a search path of the app namespace, unlike in the
 * installed patch app where the file sits in nativeLibraryDir. With a
 * DT_SONAME the preloaded lib satisfies the lookup and links exactly like
 * the installed app.
 *
 * In place, without growing the file: one redundant dynamic entry
 * (DT_RELACOUNT is an optimization hint bionic tolerates missing) is
 * rewritten as DT_SONAME, and the soname string goes into the zero padding
 * after the dynamic entries, which must lie inside a PT_LOAD so the linker
 * can read it. Idempotent; silently skipped when the layout does not allow
 * it, which leaves the previous behavior in place.
 */
internal object ElfSonamePatcher {

    private const val DT_NULL = 0L
    private const val DT_HASH = 4L
    private const val DT_STRTAB = 5L
    private const val DT_RELAENT = 9L
    private const val DT_SONAME = 14L
    private const val DT_RELENT = 19L
    private const val DT_GNU_HASH = 0x6ffffef5L
    private const val DT_RELACOUNT = 0x6ffffff9L

    fun ensureSoname(file: File, soname: String) {
        if (!file.isFile || file.length() < 64) return
        RandomAccessFile(file, "rw").use { raf -> patch(raf, soname) }
    }

    private fun patch(raf: RandomAccessFile, soname: String) {
        val ident = ByteArray(16)
        raf.seek(0)
        raf.readFully(ident)
        if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
            ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
        ) {
            return
        }
        val is64 = ident[4].toInt() == 2

        val phoff: Long
        val phentsize: Int
        val phnum: Int
        if (is64) {
            phoff = readU64(raf, 32)
            phentsize = readU16(raf, 54)
            phnum = readU16(raf, 56)
        } else {
            phoff = readU32(raf, 28).toLong()
            phentsize = readU16(raf, 42)
            phnum = readU16(raf, 44)
        }

        var dynOff = -1L
        var dynSize = -1L
        // offset, vaddr, filesz
        val loads = mutableListOf<Triple<Long, Long, Long>>()
        for (i in 0 until phnum) {
            val off = phoff + i.toLong() * phentsize
            val type = readU32(raf, off).toLong()
            if (type == 1L) { // PT_LOAD
                if (is64) {
                    loads.add(Triple(readU64(raf, off + 8), readU64(raf, off + 16), readU64(raf, off + 32)))
                } else {
                    loads.add(
                        Triple(
                            readU32(raf, off + 4).toLong(),
                            readU32(raf, off + 8).toLong(),
                            readU32(raf, off + 16).toLong()
                        )
                    )
                }
            } else if (type == 2L) { // PT_DYNAMIC
                dynOff = if (is64) readU64(raf, off + 8) else readU32(raf, off + 4).toLong()
                dynSize = if (is64) readU64(raf, off + 32) else readU32(raf, off + 16).toLong()
            }
        }
        if (dynOff < 0 || dynSize <= 0) return

        val entrySize = if (is64) 16 else 8
        var strtabVaddr = -1L
        var nullIndex = -1
        var hasSoname = false
        var gnuHashIndex = -1
        var hashIndex = -1
        var relaCountIndex = -1
        var relaEntIndex = -1
        var relEntIndex = -1
        val count = (dynSize / entrySize).toInt()
        for (i in 0 until count) {
            val slot = dynOff + i.toLong() * entrySize
            val tag = if (is64) readU64(raf, slot) else readU32(raf, slot).toLong()
            val value = if (is64) readU64(raf, slot + 8) else readU32(raf, slot + 4).toLong()
            when (tag) {
                DT_NULL -> { nullIndex = i; break }
                DT_SONAME -> { hasSoname = true; break }
                DT_STRTAB -> strtabVaddr = value
                DT_GNU_HASH -> gnuHashIndex = i
                DT_HASH -> hashIndex = i
                DT_RELACOUNT -> relaCountIndex = i
                DT_RELAENT -> relaEntIndex = i
                DT_RELENT -> relEntIndex = i
            }
        }
        if (hasSoname || nullIndex < 0 || strtabVaddr < 0) return
        // All of these are optional for bionic; keep the least risky one.
        val candidate = when {
            relaCountIndex >= 0 -> relaCountIndex
            relaEntIndex >= 0 -> relaEntIndex
            relEntIndex >= 0 -> relEntIndex
            hashIndex >= 0 && gnuHashIndex >= 0 -> hashIndex
            else -> return
        }

        // The soname string goes into the zero padding right after the
        // DT_NULL slot; it must be inside a PT_LOAD to be readable.
        val stringOffset = dynOff + (nullIndex + 1).toLong() * entrySize
        val load = loads.firstOrNull { (poff, _, pfilesz) ->
            poff <= stringOffset && stringOffset + soname.length + 1 <= poff + pfilesz
        } ?: return
        raf.seek(stringOffset)
        val padding = ByteArray(soname.length + 1)
        raf.readFully(padding)
        if (padding.any { it.toInt() != 0 }) return
        val stringVaddr = load.second + (stringOffset - load.first)
        if (stringVaddr <= strtabVaddr) return

        raf.seek(stringOffset)
        raf.write(soname.toByteArray(Charsets.US_ASCII))
        raf.write(0)

        val entrySlot = dynOff + candidate.toLong() * entrySize
        val sonameValue = stringVaddr - strtabVaddr
        if (is64) {
            writeU64(raf, entrySlot, DT_SONAME)
            writeU64(raf, entrySlot + 8, sonameValue)
        } else {
            writeU32(raf, entrySlot, DT_SONAME.toInt())
            writeU32(raf, entrySlot + 4, sonameValue.toInt())
        }
    }

    private fun readU16(raf: RandomAccessFile, at: Long): Int {
        raf.seek(at)
        val bytes = ByteArray(2)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    private fun readU32(raf: RandomAccessFile, at: Long): Int {
        raf.seek(at)
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readU64(raf: RandomAccessFile, at: Long): Long {
        raf.seek(at)
        val bytes = ByteArray(8)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun writeU32(raf: RandomAccessFile, at: Long, value: Int) {
        raf.seek(at)
        raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun writeU64(raf: RandomAccessFile, at: Long, value: Long) {
        raf.seek(at)
        raf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }
}
