package space.zhuoling.fileaccess.core.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import space.zhuoling.fileaccess.core.model.Credentials

/** Versioned local credential envelope. This is not an end-to-end encryption format. */
internal object CredentialEnvelope {
    private const val MAGIC = 0x46414331 // FAC1: AES-256-GCM, 96-bit nonce, 128-bit tag.
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    const val MAX_FILE_BYTES = 64 * 1024

    fun encrypt(reference: String, credentials: Credentials, key: SecretKey): ByteArray {
        val plaintext = encode(credentials)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // The provider generates a fresh random nonce; callers cannot accidentally reuse one.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == NONCE_BYTES)
            cipher.updateAAD(aad(reference))
            val encrypted = cipher.doFinal(plaintext)
            ByteBuffer.allocate(4 + NONCE_BYTES + encrypted.size)
                .putInt(MAGIC).put(cipher.iv).put(encrypted).array()
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(reference: String, envelope: ByteArray, key: SecretKey): Credentials {
        require(envelope.size in (4 + NONCE_BYTES + TAG_BYTES)..MAX_FILE_BYTES)
        val buffer = ByteBuffer.wrap(envelope)
        require(buffer.int == MAGIC)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad(reference))
        val plaintext = cipher.doFinal(ciphertext)
        return try { decode(plaintext) } finally { plaintext.fill(0) }
    }

    private fun aad(reference: String): ByteArray =
        "fileaccess/local-credentials/v1/$reference".toByteArray(Charsets.UTF_8)

    private fun encode(credentials: Credentials): ByteArray {
        val user = credentials.username.toByteArray(Charsets.UTF_8)
        val domain = credentials.domain.toByteArray(Charsets.UTF_8)
        val encodedPassword = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(credentials.password))
        val password = ByteArray(encodedPassword.remaining()).also(encodedPassword::get)
        return try {
            val size = 12L + user.size + domain.size + password.size
            require(size <= MAX_FILE_BYTES - 32) { "Credential is too large" }
            ByteBuffer.allocate(size.toInt())
                .putInt(user.size).put(user)
                .putInt(domain.size).put(domain)
                .putInt(password.size).put(password).array()
        } finally {
            password.fill(0)
            if (encodedPassword.hasArray()) encodedPassword.array().fill(0)
        }
    }

    private fun decode(plaintext: ByteArray): Credentials {
        val input = ByteBuffer.wrap(plaintext)
        fun readPart(): ByteArray {
            require(input.remaining() >= 4)
            val size = input.int
            require(size >= 0 && size <= input.remaining())
            return ByteArray(size).also(input::get)
        }
        val user = readPart().toString(Charsets.UTF_8)
        val domain = readPart().toString(Charsets.UTF_8)
        val passwordBytes = readPart()
        try {
            require(!input.hasRemaining())
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(passwordBytes))
            val password = CharArray(decoded.remaining()).also(decoded::get)
            if (decoded.hasArray()) decoded.array().fill('\u0000')
            return Credentials(user, password, domain)
        } finally {
            passwordBytes.fill(0)
        }
    }
}
