package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Settings-worker encryption only. Unsupported/unavailable Keystore never falls back to plaintext. */
interface CaptionSecretCipher {
    String seal(String secret, String binding) throws GeneralSecurityException;
    String open(String encrypted, String binding) throws GeneralSecurityException;

    static CaptionSecretCipher create(String namespace) {
        if (Build.VERSION.SDK_INT >= 23) return new Api23(namespace);
        return new CaptionSecretCipher() {
            public String seal(String secret, String binding) throws GeneralSecurityException { throw new GeneralSecurityException(); }
            public String open(String encrypted, String binding) throws GeneralSecurityException { throw new GeneralSecurityException(); }
        };
    }

    final class Api23 implements CaptionSecretCipher {
        private static final Charset UTF8 = Charset.forName("UTF-8");
        private final String mAlias;
        Api23(String namespace) { mAlias = namespace; }

        private SecretKey key(boolean create) throws GeneralSecurityException {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            try { store.load(null); }
            catch (java.io.IOException ignored) { throw new GeneralSecurityException(); }
            if (store.containsAlias(mAlias)) return (SecretKey) store.getKey(mAlias, null);
            if (!create) throw new GeneralSecurityException();
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(mAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build());
            return generator.generateKey();
        }

        public String seal(String secret, String binding) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(binding.getBytes(UTF8));
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) throw new GeneralSecurityException();
            byte[] encrypted = cipher.doFinal(secret.getBytes(UTF8));
            return "1:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        }

        public String open(String encrypted, String binding) throws GeneralSecurityException {
            try {
                String[] parts = encrypted.split(":", -1);
                if (parts.length != 3 || !"1".equals(parts[0]) || encrypted.length() > 32768) throw new GeneralSecurityException();
                byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP), bytes = Base64.decode(parts[2], Base64.NO_WRAP);
                if (iv.length != 12 || bytes.length < 16 || bytes.length > 16384) throw new GeneralSecurityException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
                cipher.updateAAD(binding.getBytes(UTF8));
                return new String(cipher.doFinal(bytes), UTF8);
            } catch (RuntimeException ignored) { throw new GeneralSecurityException(); }
        }
    }
}
