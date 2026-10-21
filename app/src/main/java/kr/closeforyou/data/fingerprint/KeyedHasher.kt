package kr.closeforyou.data.fingerprint

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * D: 기기 내부 키로 만드는 HMAC. 같은 화면이라도 기기·키가 다르면 지문이 달라
 * DB가 밖으로 나가도 다른 기기에서 화면을 대조할 수 없다.
 */
fun interface KeyedHasher {
    fun mac(data: ByteArray): ByteArray
}

/**
 * Android Keystore의 내보낼 수 없는 HMAC-SHA256 키. 키 원본은 앱 메모리로 나오지 않는다.
 * [deleteKey] 후에는 새 키가 만들어지므로 이전 지문은 다시는 일치하지 않는다(전체 삭제 시 사용).
 */
class AndroidKeystoreHasher(
    private val alias: String = DEFAULT_ALIAS,
) : KeyedHasher {

    override fun mac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(loadOrCreateKey())
        return mac.doFinal(data)
    }

    fun deleteKey() {
        synchronized(LOCK) {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(alias)) {
                store.deleteEntry(alias)
            }
        }
    }

    private fun loadOrCreateKey(): SecretKey = synchronized(LOCK) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
            .apply { init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build()) }
            .generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "closeforyou_popup_rule_fingerprint_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private val LOCK = Any()
    }
}

/**
 * 필드 경계가 모호하지 않도록 길이를 앞에 붙여 직렬화한다("ab"+"c"와 "a"+"bc"가 달라야 함).
 * 결과 바이트는 해시 입력으로만 쓰고 저장하거나 로그에 남기지 않는다.
 */
internal class CanonicalWriter(domain: String) {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    init { text(domain) }

    fun text(value: String?): CanonicalWriter = apply {
        if (value == null) {
            out.writeInt(-1)
        } else {
            val encoded = value.toByteArray(Charsets.UTF_8)
            out.writeInt(encoded.size)
            out.write(encoded)
        }
    }

    fun number(value: Long): CanonicalWriter = apply { out.writeLong(value) }

    fun flag(value: Boolean): CanonicalWriter = apply { out.writeBoolean(value) }

    fun toByteArray(): ByteArray {
        out.flush()
        return bytes.toByteArray()
    }
}
