package com.hexated

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KisskhKey {

    // ── Video & Sub kkey generation ────────────────────────────────

    private const val APP_VER = "2.8.10"
    private const val VI_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"
    private const val SUB_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"
    private const val PLATFORM_VER = 4830201

    fun videoKey(episodeId: Int) = generateKey(episodeId, VI_GUID)
    fun subKey(episodeId: Int) = generateKey(episodeId, SUB_GUID)

    private fun generateKey(
        episodeId: Int,
        guid: String,
        p2: String? = null,
        appVer: String = APP_VER,
        platformVer: Int = PLATFORM_VER,
        url: String = "kisskh",
        userAgent: String = "kisskh",
        platform: String = "kisskh",
        referrer: String = "kisskh",
        appName: String = "kisskh",
        appCodeName: String = "kisskh",
    ): String {
        val arr = mutableListOf(
            "", episodeId.toString(), p2 ?: "", "mg3c3b04ba",
            appVer, guid, platformVer.toString(),
            url.take(48),
            userAgent.lowercase().take(48),
            platform.take(48),
            referrer, appName, appCodeName, "00", "",
        )
        arr.add(1, jsHashCode(arr.joinToString("|")).toString())

        val padded = pkcs16Pad(arr.joinToString("|"))
        val (words, len) = stringToWords(padded)
        aesEncrypt(words)
        return wordsToHex(words, len).uppercase()
    }

    // ── Subtitle Decrypt (V1 & V2) ─────────────────────────────────

    private const val V1_KEY = "8056483646328763"
    private const val V1_IV = "6852612370185273"

    private const val V2_KEY = "AmSmZVcH93UQUezi"
    private const val V2_IV = "ReBKWW8cqdjPEnF6"

    fun decryptSubtitleContent(raw: String, url: String = ""): String {
        // Deteksi dari URL extension dulu
        val ext = url.substringAfterLast('.').substringBefore('?').lowercase()

        val (key, iv) = when {
            ext == "txt1" -> V2_KEY to V2_IV              // .srt.txt1 → V2
            ext == "txt" -> V1_KEY to V1_IV               // .srt.txt → V1
            ext == "srt" || ext.isEmpty() -> {
                // .srt: deteksi dari content
                val firstTextLine = raw.lines().firstOrNull { line ->
                    val t = line.trim()
                    t.isNotBlank() && !t.matches(Regex("\\d+")) && !t.contains("-->")
                }
                if (firstTextLine?.isBase64Cipher() == true) {
                    V1_KEY to V1_IV  // encrypted .srt pakai V1 (default)
                } else {
                    return raw  // plain, return as-is
                }
            }
            else -> return raw  // unknown ext
        }

        // Decrypt per-line kalau base64
        return raw.lines().joinToString("\n") { line ->
            if (line.trim().isBase64Cipher()) {
                try {
                    decryptAesCbc(line.trim(), key, iv)
                } catch (e: Exception) {
                    line  // fallback
                }
            } else {
                line
            }
        }
    }

    private fun decryptAesCbc(encryptedBase64: String, keyStr: String, ivStr: String): String {
        val key = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)

        val decoded = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun String.isBase64Cipher(): Boolean {
        if (isBlank()) return false
        if (matches(Regex("\\d+"))) return false          // nomor urut
        if (contains("-->")) return false                 // timestamp SRT
        if (length < 8) return false                      // terlalu pendek
        if (contains(" ")) return false                   // plain text punya spasi
        return matches(Regex("[A-Za-z0-9+/=]+"))
    }

    // ── AES Encrypt (for kkey generation) ──────────────────────────

    private fun jsHashCode(s: String): Long {
        var h: Long = 0
        for (c in s) {
            val shifted = (h.toInt() shl 5).toLong()
            h = shifted - h + c.code
        }
        return h
    }

    private fun pkcs16Pad(s: String): String {
        val padLen = 16 - s.length % 16
        return s + String(CharArray(padLen) { padLen.toChar() })
    }

    private fun stringToWords(s: String): Pair<IntArray, Int> {
        val words = IntArray((s.length + 3) / 4)
        for (i in s.indices)
            words[i ushr 2] = words[i ushr 2] or ((0xff and s[i].code) shl (24 - (i % 4) * 8))
        return Pair(words, s.length)
    }

    private fun wordsToHex(words: IntArray, len: Int): String {
        val sb = StringBuilder()
        for (i in 0 until len)
            sb.append(((words[i ushr 2] ushr (24 - (i % 4) * 8)) and 0xff).toString(16).padStart(2, '0'))
        return sb.toString()
    }

    private val INIT_KEY = intArrayOf(0x01504af3, 0x56e619cf, 0x2e42bba6, -0x73c08f07)
    private val RCON = intArrayOf(
        0x4f6bdaa3.toInt(), -0x61d07350, 0x7f5e722d, -0x61210cec,
        0x536620a8.toInt(), -0x32b653e8, -0x4de821cb, 0x2cc92d21,
        -0x73412227, 0x41f771c1, -0xc1f500c, -0x20d67d2b,
        0x2dadde47, 0x6c5aaf86, -0x6045ff8e, 0x409382a7,
        -0x6417db2, -0x6a1bd238, 0xa5e2dba, 0x4acdaf1d,
        0x54c72698.toInt(), -0x3edcf4b0, -0x3482d916, -0x7e4f7609,
        -0x6c9fb16c, 0x524345c4.toInt(), -0x66c19cd2, 0x188eead9,
        -0x351884c7, -0x675bc103, 0x19a5dd3, 0x1914b70a,
        -0x4fb1e313, 0x28ea2210, 0x29707fc3, 0x3064c8c9,
        -0x17593e17, -0x3fb31c07, -0x16c363c6, -0x26a7ab0d,
        -0x4b793324, 0x74ca2f25, -0x62094ce1, 0x44aee7ec,
    )

    private val T0 = IntArray(256); private val T1 = IntArray(256)
    private val T2 = IntArray(256); private val T3 = IntArray(256)
    private val SBOX = IntArray(256)

    init {
        val xtime = IntArray(256) { i -> if (i < 0x80) i shl 1 else (i shl 1) xor 0x11b }
        var x = 0; var xi = 0
        for (i in 0 until 256) {
            val sx = xi xor (xi shl 1) xor (xi shl 2) xor (xi shl 3) xor (xi shl 4)
            val s = (sx ushr 8) xor (0xff and sx) xor 0x63
            SBOX[x] = s
            val tVal = (0x101 * xtime[s]) xor (0x1010100 * s)
            T0[x] = (tVal shl 24) or (tVal ushr 8)
            T1[x] = (tVal shl 16) or (tVal ushr 16)
            T2[x] = (tVal shl 8) or (tVal ushr 24)
            T3[x] = tVal
            if (x == 0) { x = 1; xi = 1 } else {
                val x2 = xtime[x]; val x4 = xtime[x2]; val x8 = xtime[x4]
                x = x2 xor xtime[xtime[xtime[x8 xor x2]]]
                xi = xi xor xtime[xtime[xi]]
            }
        }
    }

    private fun aesBlock(w: IntArray, offset: Int) {
        val prev = if (offset == 0) INIT_KEY else w.sliceArray(offset - 4 until offset)
        for (i in 0 until 4) w[offset + i] = w[offset + i] xor prev[i]
        var a0 = w[offset] xor RCON[0]; var a1 = w[offset + 1] xor RCON[1]
        var a2 = w[offset + 2] xor RCON[2]; var a3 = w[offset + 3] xor RCON[3]
        var ki = 4
        for (r in 1 until 10) {
            val b0 = T0[a0 ushr 24] xor T1[(a1 ushr 16) and 0xff] xor T2[(a2 ushr 8) and 0xff] xor T3[0xff and a3] xor RCON[ki++]
            val b1 = T0[a1 ushr 24] xor T1[(a2 ushr 16) and 0xff] xor T2[(a3 ushr 8) and 0xff] xor T3[0xff and a0] xor RCON[ki++]
            val b2 = T0[a2 ushr 24] xor T1[(a3 ushr 16) and 0xff] xor T2[(a0 ushr 8) and 0xff] xor T3[0xff and a1] xor RCON[ki++]
            val b3 = T0[a3 ushr 24] xor T1[(a0 ushr 16) and 0xff] xor T2[(a1 ushr 8) and 0xff] xor T3[0xff and a2] xor RCON[ki++]
            a0 = b0; a1 = b1; a2 = b2; a3 = b3
        }
        w[offset] = ((SBOX[a0 ushr 24] shl 24) or (SBOX[(a1 ushr 16) and 0xff] shl 16) or (SBOX[(a2 ushr 8) and 0xff] shl 8) or SBOX[0xff and a3]) xor RCON[ki++]
        w[offset + 1] = ((SBOX[a1 ushr 24] shl 24) or (SBOX[(a2 ushr 16) and 0xff] shl 16) or (SBOX[(a3 ushr 8) and 0xff] shl 8) or SBOX[0xff and a0]) xor RCON[ki++]
        w[offset + 2] = ((SBOX[a2 ushr 24] shl 24) or (SBOX[(a3 ushr 16) and 0xff] shl 16) or (SBOX[(a0 ushr 8) and 0xff] shl 8) or SBOX[0xff and a1]) xor RCON[ki++]
        w[offset + 3] = ((SBOX[a3 ushr 24] shl 24) or (SBOX[(a0 ushr 16) and 0xff] shl 16) or (SBOX[(a1 ushr 8) and 0xff] shl 8) or SBOX[0xff and a2]) xor RCON[ki++]
    }

    private fun aesEncrypt(words: IntArray) {
        var i = 0
        while (i < words.size) { aesBlock(words, i); i += 4 }
    }
}
