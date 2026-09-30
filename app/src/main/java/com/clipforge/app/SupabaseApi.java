package com.clipforge.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class SupabaseApi {
    public static final class AuthResult {
        public final String accessToken; public final String email;
        AuthResult(String token,String email){this.accessToken=token;this.email=email;}
    }
    public static final class UploadTicket {
        public final String uploadUrl; public final String path; public final long expiresAtEpochMs;
        UploadTicket(String uploadUrl,String path,long expiresAtEpochMs){this.uploadUrl=uploadUrl;this.path=path;this.expiresAtEpochMs=expiresAtEpochMs;}
    }
    public interface VideoUploadProgress { void onProgress(int percent); }
    private SupabaseApi(){}

    public static AuthResult signIn(String email,String password)throws Exception{
        JSONObject body=new JSONObject().put("email",email).put("password",password);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-login",BuildConfig.MAIN_SUPABASE_KEY,null,body);
        return new AuthResult(json.optString("access_token",null),json.optJSONObject("user")!=null?json.optJSONObject("user").optString("email",email):email);
    }
    public static AuthResult signUp(String name,String email,String password)throws Exception{
        JSONObject body=new JSONObject().put("name",name).put("email",email).put("password",password);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-signup",BuildConfig.MAIN_SUPABASE_KEY,null,body);
        String token=json.optString("access_token",null);
        String returnedEmail=json.optJSONObject("user")!=null?json.optJSONObject("user").optString("email",email):email;
        return new AuthResult(token,returnedEmail);
    }
    public static void resetPassword(String email)throws Exception{
        requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/auth/v1/recover",BuildConfig.MAIN_SUPABASE_KEY,null,new JSONObject().put("email",email));
    }
    public static String googleOAuthUrl(){
        return BuildConfig.MAIN_SUPABASE_URL+"/auth/v1/authorize?provider=google&redirect_to=clipforge%3A%2F%2Fauth-callback";
    }

    public static UploadTicket requestUploadTicket(String accessToken,String target,String fileName,String contentType,long sizeBytes)throws Exception{
        if(accessToken==null||accessToken.isEmpty())throw new IllegalStateException("Faça login para usar o processamento em nuvem.");
        JSONObject body=new JSONObject().put("target",target).put("fileName",fileName).put("contentType",contentType==null?"video/mp4":contentType).put("sizeBytes",sizeBytes);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-storage-ticket",BuildConfig.MAIN_SUPABASE_KEY,accessToken,body);
        String url=json.optString("uploadUrl",null),path=json.optString("path",null);
        long expires=json.optLong("expiresAt",System.currentTimeMillis()+15*60_000L);
        if(url==null||path==null)throw new IllegalStateException("Broker de upload ainda não foi configurado.");
        return new UploadTicket(url,path,expires);
    }

    public static void uploadSigned(Context context,Uri uri,UploadTicket ticket,String contentType,long sizeBytes,VideoUploadProgress progress)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(ticket.uploadUrl).openConnection();
        c.setConnectTimeout(20_000);c.setReadTimeout(120_000);c.setDoOutput(true);c.setRequestMethod("PUT");
        c.setRequestProperty("Content-Type",contentType==null?"video/mp4":contentType);c.setRequestProperty("x-upsert","false");
        if(sizeBytes>0)c.setFixedLengthStreamingMode(sizeBytes);else c.setChunkedStreamingMode(6*1024*1024);
        try(InputStream in=new BufferedInputStream(context.getContentResolver().openInputStream(uri),256*1024);OutputStream out=c.getOutputStream()){
            byte[] buf=new byte[256*1024];long sent=0;int n;
            while((n=in.read(buf))>=0){if(n==0)continue;out.write(buf,0,n);sent+=n;if(sizeBytes>0)progress.onProgress((int)Math.min(100,sent*100L/sizeBytes));}
        }
        int code=c.getResponseCode();if(code<200||code>=300)throw new IllegalStateException("Falha no upload seguro (HTTP "+code+").");
    }

    public static void uploadSignedFile(java.io.File file,UploadTicket ticket,String contentType,VideoUploadProgress progress)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(ticket.uploadUrl).openConnection();
        c.setConnectTimeout(20_000);c.setReadTimeout(120_000);c.setDoOutput(true);c.setRequestMethod("PUT");
        c.setRequestProperty("Content-Type",contentType==null?"video/mp4":contentType);c.setRequestProperty("x-upsert","false");c.setFixedLengthStreamingMode(file.length());
        try(InputStream in=new BufferedInputStream(new java.io.FileInputStream(file),256*1024);OutputStream out=c.getOutputStream()){
            byte[] buf=new byte[256*1024];long sent=0;int n;
            while((n=in.read(buf))>=0){if(n==0)continue;out.write(buf,0,n);sent+=n;progress.onProgress((int)Math.min(100,sent*100L/Math.max(1,file.length())));}
        }
        int code=c.getResponseCode();if(code<200||code>=300)throw new IllegalStateException("Falha no upload seguro do clip (HTTP "+code+").");
    }

    public static void finalizeCloud(String accessToken,String rawPath,String clipPath)throws Exception{
        if(accessToken==null||accessToken.isEmpty())return;
        JSONObject body=new JSONObject();if(rawPath!=null)body.put("rawPath",rawPath);if(clipPath!=null)body.put("clipPath",clipPath);body.put("clipTtlHours",24);
        requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-finalize",BuildConfig.MAIN_SUPABASE_KEY,accessToken,body);
    }

    private static JSONObject requestJson(String method,String endpoint,String apikey,String accessToken,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(15_000);c.setReadTimeout(30_000);c.setRequestMethod(method);
        c.setRequestProperty("apikey",apikey);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        if(accessToken!=null&&!accessToken.isEmpty())c.setRequestProperty("Authorization","Bearer "+accessToken);
        if(body!=null){c.setDoOutput(true);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
        int code=c.getResponseCode();InputStream stream=code>=200&&code<400?c.getInputStream():c.getErrorStream();String text=readAll(stream);
        if(code<200||code>=300){String message=text;try{JSONObject err=new JSONObject(text);message=err.optString("msg",err.optString("message",text));}catch(Exception ignored){}throw new IllegalStateException(message==null||message.isEmpty()?"Erro HTTP "+code:message);}
        return text==null||text.trim().isEmpty()?new JSONObject():new JSONObject(text);
    }
    private static String readAll(InputStream in)throws Exception{
        if(in==null)return "";StringBuilder sb=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)sb.append(line);}
        return sb.toString();
    }
}
