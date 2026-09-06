package ar.fausto.weil

import android.content.Context
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) { block() }

actual suspend fun captureSessionCookie(response: HttpResponse, cookieName: String, store: SecureStore) {
    val cookies = response.headers.getAll(HttpHeaders.SetCookie) ?: return
    for (raw in cookies) {
        val first = raw.substringBefore(';').trim()
        val name = first.substringBefore('=').trim()
        if (name == cookieName) {
            store.write(COOKIE_STORE_PREFIX + cookieName, "$name=${first.substringAfter('=', "")}")
            return
        }
    }
}

actual fun storedCookieHeader(cookieName: String, store: SecureStore): String? =
    store.read(COOKIE_STORE_PREFIX + cookieName)

actual fun epochMillis(): Long = System.currentTimeMillis()

class AndroidSecureStore(context: Context) : SecureStore {
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "finance_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        context.getSharedPreferences("finance_auth_fallback", Context.MODE_PRIVATE)
    }

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
}

class AndroidPasskeys(private val activity: android.app.Activity) : PasskeyCeremony {
    private val manager = CredentialManager.create(activity)

    override suspend fun create(optionsJson: String): String {
        return try {
            val result = manager.createCredential(
                activity,
                CreatePublicKeyCredentialRequest(optionsJson),
            )
            (result as CreatePublicKeyCredentialResponse).registrationResponseJson
        } catch (e: CreateCredentialCancellationException) {
            throw PasskeyCancelled()
        } catch (e: Throwable) {
            Log.e("AndroidPasskeys", "create failed: ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
    }

    override suspend fun assert(optionsJson: String): String {
        return try {
            val result = manager.getCredential(
                activity,
                GetCredentialRequest(listOf(GetPublicKeyCredentialOption(optionsJson))),
            )
            (result.credential as PublicKeyCredential).authenticationResponseJson
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyCancelled()
        } catch (e: NoCredentialException) {
            throw PasskeyNotFound()
        } catch (e: Throwable) {
            Log.e("AndroidPasskeys", "assert failed: ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
    }
}
