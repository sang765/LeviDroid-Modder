package org.levimc.launcher.core.minecraft

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the DT_NEEDED entries of an extracted native library (ELF32/ELF64)
 * so launch code can discover extra libs injected by patch builds (MCPatch
 * and friends link libminecraftpe.so against their own loader libs) without
 * hardcoding their names.
 */
internal object ElfNeededReader {
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L
    private const val DT_NULL = 0L
    private const val MAX_DYNAMIC_BYTES = 1 shl 20
    private const val MAX_STRTAB_BYTES = 1 shl 20

    private data class Segment(val vaddr: Long, val offset: Long, val size: Long)

    fun readNeeded(libFile: File): List<String> {
        if (!libFile.isFile) return emptyList()
        return try {
            RandomAccessFile(libFile, "r").use { raf ->
                val ident = ByteArray(16)
                raf.readFully(ident)
                if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                    ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
                ) {
                    return emptyList()
                }
                val is64 = ident[4].toInt() == 2
                val phoff: Long
                val phentsize: Int
                val phnum: Int
                if (is64) {
                    // e_ident is 16 bytes; the rest of the ELF64 header is 48
                    // (e_phoff@16, e_phentsize@38, e_phnum@40 within this buffer).
                    val hdr = ByteArray(48)
                    raf.readFully(hdr)
                    val buf = le(hdr)
                    phoff = buf.getLong(16)
                    phentsize = buf.getShort(38).toInt() and 0xffff
                    phnum = buf.getShort(40).toInt() and 0xffff
                } else {
                    // Rest of the ELF32 header is 36 bytes (e_phoff@12,
                    // e_phentsize@26, e_phnum@28 within this buffer).
                    val hdr = ByteArray(32)
                    raf.readFully(hdr)
                    val buf = le(hdr)
                    phoff = buf.getInt(12).toLong() and 0xffffffffL
                    phentsize = buf.getShort(26).toInt() and 0xffff
                    phnum = buf.getShort(28).toInt() and 0xffff
                }
                if (phnum == 0 || phentsize !in 1..1024) return emptyList()

                data class Segment(val vaddr: Long, val offset: Long, val size: Long)
                val loads = ArrayList<Segment>()
                var dynOffset = -1L
                var dynSize = 0L
                for (i in 0 until phnum) {
                    raf.seek(phoff + i.toLong() * phentsize)
                    val ph = ByteArray(phentsize)
                    raf.readFully(ph)
                    val buf = le(ph)
                    if (is64) {
                        when (buf.getInt(0)) {
                            PT_LOAD -> loads.add(Segment(buf.getLong(16), buf.getLong(8), buf.getLong(32)))
                            PT_DYNAMIC -> {
                                dynOffset = buf.getLong(8)
                                dynSize = buf.getLong(32)
                            }
                        }
                    } else {
                        when (buf.getInt(0)) {
                            PT_LOAD -> loads.add(
                                Segment(
                                    buf.getInt(8).toLong() and 0xffffffffL,
                                    buf.getInt(4).toLong() and 0xffffffffL,
                                    buf.getInt(16).toLong() and 0xffffffffL,
                                )
                            )
                            PT_DYNAMIC -> {
                                dynOffset = buf.getInt(4).toLong() and 0xffffffffL
                                dynSize = buf.getInt(16).toLong() and 0xffffffffL
                            }
                        }
                    }
                }
                if (dynOffset < 0 || dynSize <= 0) return emptyList()

                raf.seek(dynOffset)
                val dyn = ByteArray(dynSize.toInt().coerceAtMost(MAX_DYNAMIC_BYTES))
                raf.readFully(dyn)
                val dynBuf = le(dyn)
                val entrySize = if (is64) 16 else 8
                var strtabVaddr = -1L
                var strtabSize = 0L
                val needed = ArrayList<Long>()
                var pos = 0
                while (pos + entrySize <= dyn.size) {
                    val tag: Long
                    val value: Long
                    if (is64) {
                        tag = dynBuf.getLong(pos)
                        value = dynBuf.getLong(pos + 8)
                    } else {
                        tag = dynBuf.getInt(pos).toLong()
                        value = dynBuf.getInt(pos + 4).toLong() and 0xffffffffL
                    }
                    when (tag) {
                        DT_NULL -> break
                        DT_NEEDED -> needed.add(value)
                        DT_STRTAB -> strtabVaddr = value
                        DT_STRSZ -> strtabSize = value
                    }
                    pos += entrySize
                }
                if (strtabVaddr < 0 || needed.isEmpty()) return emptyList()

                var strtabOffset = -1L
                for (segment in loads) {
                    if (segment.vaddr <= strtabVaddr && strtabVaddr < segment.vaddr + segment.size) {
                        strtabOffset = segment.offset + (strtabVaddr - segment.vaddr)
                        break
                    }
                }
                if (strtabOffset < 0) return emptyList()

                val strings = ByteArray(strtabSize.toInt().coerceIn(1, MAX_STRTAB_BYTES))
                raf.seek(strtabOffset)
                raf.readFully(strings)
                needed.mapNotNull { offset ->
                    if (offset >= strings.size) return@mapNotNull null
                    val start = offset.toInt()
                    var end = start
                    while (end < strings.size && strings[end].toInt() != 0) end++
                    if (end == start) null else String(strings, start, end - start, Charsets.US_ASCII)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun le(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
