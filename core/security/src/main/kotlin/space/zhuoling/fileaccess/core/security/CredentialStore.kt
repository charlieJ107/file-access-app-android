package space.zhuoling.fileaccess.core.security

import space.zhuoling.fileaccess.core.model.Credentials

/** Credentials are short-lived; callers must close the returned object after authenticating. */
interface CredentialStore {
    suspend fun get(reference: String): Credentials?
    suspend fun put(reference: String, credentials: Credentials)
    suspend fun delete(reference: String)
}

/** Never includes plaintext, ciphertext, a username, or a platform exception in its message. */
class CredentialUnavailableException : java.io.IOException(
    "Saved credentials are unavailable. Sign in to this connection again.",
)
