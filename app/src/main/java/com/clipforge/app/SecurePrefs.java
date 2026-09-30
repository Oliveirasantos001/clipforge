package com.clipforge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecurePrefs {
    private static final String STORE="clipforge_secure";
    private static final String ALIAS="clipforge_session_aes_v1";
    private final SharedPreferences prefs;

    public SecurePrefs(Context context){
        prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
    }

    public void putString(String key,String value){
        if(value==null){remove(key);return;}
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,getOrCreateKey());
            byte[] iv=cipher.getIV();
            byte[] enc=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed=new byte[1+iv.length+enc.length];
            packed[0]=(byte)iv.length;
            System.arraycopy(iv,0,packed,1,iv.length);
            System.arraycopy(enc,0,packed,1+iv.length,enc.length);
            prefs.edit().putString(key,Base64.encodeToString(packed,Base64.NO_WRAP)).apply();
        }catch(Exception e){
            throw new IllegalStateException("Não foi possível proteger os dados locais.",e);
        }
    }

    public String getString(String key,String fallback){
        String raw=prefs.getString(key,null);
        if(raw==null)return fallback;
        try{
            byte[] packed=Base64.decode(raw,Base64.NO_WRAP);
            int ivLen=packed[0]&0xff;
            if(ivLen<12||packed.length<=1+ivLen)return fallback;
            byte[] iv=new byte[ivLen];
            byte[] enc=new byte[packed.length-1-ivLen];
            System.arraycopy(packed,1,iv,0,ivLen);
            System.arraycopy(packed,1+ivLen,enc,0,enc.length);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(enc),StandardCharsets.UTF_8);
        }catch(Exception e){
            prefs.edit().remove(key).apply();
            return fallback;
        }
    }

    public void remove(String key){prefs.edit().remove(key).apply();}

    private SecretKey getOrCreateKey() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry=ks.getEntry(ALIAS,null);
        if(entry instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)entry).getSecretKey();

        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setRandomizedEncryptionRequired(true)
         .build());
        return kg.generateKey();
    }
}
