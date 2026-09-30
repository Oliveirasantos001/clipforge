package com.clipforge.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.Locale;

public final class IntegrityGuard {
    private IntegrityGuard(){}

    public static boolean isTrusted(Context context){
        if(!BuildConfig.RELEASE_INTEGRITY_ENFORCED)return true;
        return isPackageTrusted(context) && isRuntimeTrusted(context);
    }

    public static boolean isRuntimeTrusted(Context context){
        if(!BuildConfig.RELEASE_INTEGRITY_ENFORCED)return true;
        if(BuildConfig.DEBUG)return false;
        if(isDebuggable(context))return false;
        if(Debug.isDebuggerConnected()||Debug.waitingForDebugger())return false;
        if(hasTracer())return false;
        if(hasHookingLibrary())return false;
        return true;
    }

    public static boolean isPackageTrusted(Context context){
        if(!BuildConfig.RELEASE_INTEGRITY_ENFORCED)return true;
        if(!BuildConfig.APPLICATION_ID.equals(context.getPackageName()))return false;
        try{
            PackageManager pm=context.getPackageManager();
            Signature[] signatures;
            if(Build.VERSION.SDK_INT>=28){
                PackageInfo info=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
                if(info.signingInfo==null)return false;
                signatures=info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            }else{
                @SuppressWarnings("deprecation")
                PackageInfo info=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                Signature[] old=info.signatures;
                signatures=old;
            }
            if(signatures==null||signatures.length==0)return false;
            for(Signature signature:signatures){
                if(signature==null)continue;
                String digest=sha256(signature.toByteArray());
                if(constantTimeEquals(digest,BuildConfig.OFFICIAL_CERT_SHA256))return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    private static boolean isDebuggable(Context context){
        try{
            ApplicationInfo info=context.getApplicationInfo();
            return (info.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        }catch(Exception e){
            return true;
        }
    }

    private static boolean hasTracer(){
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/self/status"))){
            String line;
            while((line=reader.readLine())!=null){
                if(line.startsWith("TracerPid:")){
                    String value=line.substring("TracerPid:".length()).trim();
                    return !"0".equals(value);
                }
            }
        }catch(Exception ignored){}
        return false;
    }

    private static boolean hasHookingLibrary(){
        final String[] markers={"frida","xposed","lsposed","substrate","zygisk"};
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/self/maps"))){
            String line;
            while((line=reader.readLine())!=null){
                String lower=line.toLowerCase(Locale.ROOT);
                for(String marker:markers){
                    if(lower.contains(marker))return true;
                }
            }
        }catch(Exception ignored){}
        return false;
    }

    private static String sha256(byte[] input)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] out=digest.digest(input);
        StringBuilder sb=new StringBuilder(out.length*2);
        for(byte b:out)sb.append(String.format(Locale.ROOT,"%02x",b&0xff));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a,String b){
        if(a==null||b==null)return false;
        byte[] x=a.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] y=b.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if(x.length!=y.length)return false;
        int diff=0;
        for(int i=0;i<x.length;i++)diff|=x[i]^y[i];
        return diff==0;
    }
}
