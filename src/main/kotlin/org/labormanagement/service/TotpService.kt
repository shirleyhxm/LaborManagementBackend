package org.labormanagement.service

import java.security.SecureRandom
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Service for generating and validating TOTP (Time-based One-Time Password) codes
 * Compatible with Google Authenticator, Authy, etc.
 */
class TotpService {
    companion object {
        private const val SECRET_LENGTH = 20 // bytes
        private const val TIME_STEP_SECONDS = 30L
        private const val DIGITS = 6
        private const val ALGORITHM = "HmacSHA1"
        private val secureRandom = SecureRandom()
    }

    /**
     * Generate a new TOTP secret key
     */
    fun generateSecret(): String {
        val bytes = ByteArray(SECRET_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base32.encode(bytes)
    }

    /**
     * Generate TOTP code for current time
     */
    fun generateCode(secret: String): String {
        val time = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS
        return generateCode(secret, time)
    }

    /**
     * Generate TOTP code for specific time counter
     */
    private fun generateCode(secret: String, timeCounter: Long): String {
        val secretBytes = Base32.decode(secret)
        val data = ByteArray(8)

        var value = timeCounter
        for (i in 7 downTo 0) {
            data[i] = value.toByte()
            value = value shr 8
        }

        val signKey = SecretKeySpec(secretBytes, ALGORITHM)
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(signKey)
        val hash = mac.doFinal(data)

        val offset = hash[hash.size - 1].toInt() and 0xf
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                    ((hash[offset + 1].toInt() and 0xff) shl 16) or
                    ((hash[offset + 2].toInt() and 0xff) shl 8) or
                    (hash[offset + 3].toInt() and 0xff)

        val otp = binary % 10.0.pow(DIGITS.toDouble()).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }

    /**
     * Verify TOTP code
     * Allows for time drift (checks current time +/- 1 time step)
     */
    fun verifyCode(secret: String, code: String): Boolean {
        val time = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS

        // Check current time and +/- 1 time step to allow for clock drift
        for (i in -1..1) {
            val testCode = generateCode(secret, time + i)
            if (testCode == code) {
                return true
            }
        }

        return false
    }

    /**
     * Generate QR code URL for setting up 2FA in authenticator apps
     */
    fun generateQrCodeUrl(secret: String, accountName: String, issuer: String = "LaborManagement"): String {
        val encodedIssuer = java.net.URLEncoder.encode(issuer, "UTF-8")
        val encodedAccountName = java.net.URLEncoder.encode(accountName, "UTF-8")
        val encodedSecret = secret // Base32 encoded secret

        return "otpauth://totp/$encodedIssuer:$encodedAccountName?secret=$encodedSecret&issuer=$encodedIssuer"
    }

    /**
     * Simple Base32 encoder/decoder
     */
    private object Base32 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val DECODE_TABLE = IntArray(256) { -1 }

        init {
            ALPHABET.forEachIndexed { index, c ->
                DECODE_TABLE[c.code] = index
            }
        }

        fun encode(data: ByteArray): String {
            val result = StringBuilder()
            var buffer = 0
            var bitsLeft = 0

            for (byte in data) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
                bitsLeft += 8

                while (bitsLeft >= 5) {
                    result.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                    bitsLeft -= 5
                }
            }

            if (bitsLeft > 0) {
                result.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
            }

            return result.toString()
        }

        fun decode(encoded: String): ByteArray {
            val cleanInput = encoded.uppercase().replace("=", "")
            val output = mutableListOf<Byte>()
            var buffer = 0
            var bitsLeft = 0

            for (char in cleanInput) {
                val value = DECODE_TABLE[char.code]
                if (value == -1) {
                    throw IllegalArgumentException("Invalid Base32 character: $char")
                }

                buffer = (buffer shl 5) or value
                bitsLeft += 5

                if (bitsLeft >= 8) {
                    output.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                    bitsLeft -= 8
                }
            }

            return output.toByteArray()
        }
    }
}
