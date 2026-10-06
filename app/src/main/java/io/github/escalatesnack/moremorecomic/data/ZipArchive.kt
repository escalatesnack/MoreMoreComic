package io.github.escalatesnack.moremorecomic.data

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/**
 * ZIPの中身を、好きな順番で1つずつ取り出すための読み取り口。
 *
 * Android標準の`ZipFile`は「ファイルの場所(パス)」が要るが、フォルダ選択画面で選んだファイルは
 * パスではなく「開いた状態のファイル」としてしか受け取れない。そこで、ZIPの末尾にある目次
 * (セントラルディレクトリ)を自分で読み、必要なページだけをその場で取り出す。
 * 無圧縮と通常の圧縮(Deflate)に対応。4GBを超えるZIP(Zip64)も読める。
 */
class ZipArchive(private val channel: FileChannel, private val owner: Closeable? = null) : Closeable {

    class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
        val encrypted: Boolean,
    )

    val entries: List<Entry> = readCentralDirectory()

    /** 1つ分の中身を、展開した状態で返す */
    fun read(entry: Entry): ByteArray {
        if (entry.encrypted) throw IOException("パスワード付きのZIPには対応していません")
        if (entry.size > MAX_ENTRY_BYTES || entry.compressedSize > MAX_ENTRY_BYTES) {
            throw IOException("ファイルが大きすぎます: ${entry.name}")
        }
        val header = readAt(entry.localHeaderOffset, 30)
        if (header.getInt(0) != SIG_LOCAL) throw IOException("ZIPが壊れています")
        val nameLen = header.getShort(26).toInt() and 0xFFFF
        val extraLen = header.getShort(28).toInt() and 0xFFFF
        val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
        val raw = readAt(dataOffset, entry.compressedSize.toInt()).array()
        return when (entry.method) {
            0 -> raw
            8 -> {
                val out = ByteArray(entry.size.toInt())
                val inflater = Inflater(true)
                try {
                    inflater.setInput(raw)
                    var done = 0
                    while (done < out.size) {
                        val n = inflater.inflate(out, done, out.size - done)
                        if (n == 0 && (inflater.finished() || inflater.needsInput())) break
                        done += n
                    }
                    if (done != out.size) throw IOException("ZIPが壊れています: ${entry.name}")
                } finally {
                    inflater.end()
                }
                out
            }
            else -> throw IOException("対応していない圧縮方式です: ${entry.name}")
        }
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            owner?.close()
        }
    }

    private fun readAt(position: Long, length: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw IOException("ZIPが途中で切れています")
            pos += n
        }
        buf.flip()
        return buf
    }

    private fun readCentralDirectory(): List<Entry> {
        val fileSize = channel.size()
        if (fileSize < 22) throw IOException("ZIPではありません")
        // 目次の場所は末尾の「終端レコード」に書いてある。後ろにコメントが付くことがあるので、末尾から探す
        val tailLen = minOf(fileSize, 65557L).toInt()
        val tailStart = fileSize - tailLen
        val tail = readAt(tailStart, tailLen)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail.getInt(i) == SIG_EOCD) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw IOException("ZIPではありません")
        var count = (tail.getShort(eocd + 10).toInt() and 0xFFFF).toLong()
        var cdSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            // Zip64: 本当の値は別のレコードにある
            val locatorPos = tailStart + eocd - 20
            if (locatorPos >= 0) {
                val locator = readAt(locatorPos, 20)
                if (locator.getInt(0) == SIG_ZIP64_LOCATOR) {
                    val record = readAt(locator.getLong(8), 56)
                    if (record.getInt(0) == SIG_ZIP64_EOCD) {
                        count = record.getLong(32)
                        cdSize = record.getLong(40)
                        cdOffset = record.getLong(48)
                    }
                }
            }
        }
        if (cdSize > Int.MAX_VALUE || cdOffset + cdSize > fileSize) throw IOException("ZIPが壊れています")

        val cd = readAt(cdOffset, cdSize.toInt())
        val result = ArrayList<Entry>(count.coerceAtMost(100_000).toInt())
        var p = 0
        while (p + 46 <= cd.limit() && cd.getInt(p) == SIG_CENTRAL) {
            val flags = cd.getShort(p + 8).toInt() and 0xFFFF
            val method = cd.getShort(p + 10).toInt() and 0xFFFF
            var compressedSize = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var size = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            var localOffset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
            if (p + 46 + nameLen + extraLen > cd.limit()) break
            val nameBytes = ByteArray(nameLen)
            cd.position(p + 46)
            cd.get(nameBytes)

            // Zip64の追加情報: 上の値が0xFFFFFFFFのものだけ、この順で入っている
            var e = p + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = cd.getShort(e).toInt() and 0xFFFF
                val len = cd.getShort(e + 2).toInt() and 0xFFFF
                if (id == 0x0001) {
                    var q = e + 4
                    if (size == 0xFFFFFFFFL && q + 8 <= extraEnd) { size = cd.getLong(q); q += 8 }
                    if (compressedSize == 0xFFFFFFFFL && q + 8 <= extraEnd) { compressedSize = cd.getLong(q); q += 8 }
                    if (localOffset == 0xFFFFFFFFL && q + 8 <= extraEnd) { localOffset = cd.getLong(q) }
                }
                e += 4 + len
            }

            result += Entry(
                name = decodeName(nameBytes, utf8Flag = flags and 0x800 != 0),
                method = method,
                compressedSize = compressedSize,
                size = size,
                localHeaderOffset = localOffset,
                encrypted = flags and 0x1 != 0,
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private companion object {
        const val SIG_LOCAL = 0x04034b50
        const val SIG_CENTRAL = 0x02014b50
        const val SIG_EOCD = 0x06054b50
        const val SIG_ZIP64_LOCATOR = 0x07064b50
        const val SIG_ZIP64_EOCD = 0x06064b50
        const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

        /**
         * ファイル名の文字コード。UTF-8の印が無いZIPでも中身がUTF-8のことがあるので、まずUTF-8として
         * 読んでみて、読めなければWindowsで作った日本語のZIP(Shift_JIS)として読む。
         */
        fun decodeName(bytes: ByteArray, utf8Flag: Boolean): String {
            if (utf8Flag) return String(bytes, Charsets.UTF_8)
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                try {
                    String(bytes, Charset.forName("windows-31j"))
                } catch (_: Exception) {
                    String(bytes, Charsets.ISO_8859_1)
                }
            }
        }
    }
}
