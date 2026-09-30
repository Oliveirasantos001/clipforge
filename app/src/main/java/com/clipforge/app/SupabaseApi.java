package com.clipforge.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class SupabaseApi {
    private static final long MAX_VIDEO_BYTES=5L*1024L*1024L*1024L;
    private static final String RAW_HOST="njrxnswmlaxzhoatgzlm.supabase.co";
    private static final String CLIPS_HOST="yjwtmuljitfykhhrphkn.supabase.co";

    public static final class AuthResult {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresInSeconds;
        public final String email;

        AuthResult(String accessToken,String refreshToken,long expiresInSeconds,String email){
            this.accessToken=accessToken;
            this.refreshToken=refreshToken;
            this.expiresInSeconds=expiresInSeconds;
            this.email=email;
        }
    }

    public static final class UploadTicket {
        public final String uploadUrl;
        public final String path;
        public final long expiresAtEpochMs;

        UploadTicket(String uploadUrl,String path,long expiresAtEpochMs){
            this.uploadUrl=uploadUrl;
            this.path=path;
            this.expiresAtEpochMs=expiresAtEpochMs;
        }
    }

    public interface VideoUploadProgress { void onProgress(int percent); }

    private SupabaseApi(){}

    public static AuthResult signIn(String email,String password)throws Exception{
        email=InputGuard.normalizeEmail(email);
        if(!InputGuard.validEmail(email)||password==null||password.isEmpty()||password.length()>128){
            throw new IllegalArgumentException("E-mail ou senha inválidos.");
        }
        JSONObject body=new JSONObject().put("email",email).put("password",password);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-login",BuildConfig.MAIN_SUPABASE_KEY,null,body);
        return authResult(json,email);
    }

    public static AuthResult signUp(String name,String email,String password)throws Exception{
        email=InputGuard.normalizeEmail(email);
        if(!InputGuard.validEmail(email))throw new IllegalArgumentException("Informe um e-mail válido.");
        if(!InputGuard.validPassword(password))throw new IllegalArgumentException("A senha deve ter entre 8 e 128 caracteres.");
        if(!InputGuard.validName(name))throw new IllegalArgumentException("Nome inválido.");
        JSONObject body=new JSONObject().put("name",name.trim()).put("email",email).put("password",password);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-signup",BuildConfig.MAIN_SUPABASE_KEY,null,body);
        return authResult(json,email);
    }

    public static AuthResult refreshSession(String refreshToken)throws Exception{
        if(refreshToken==null||refreshToken.length()<16)throw new IllegalArgumentException("Sessão inválida.");
        JSONObject body=new JSONObject().put("refresh_token",refreshToken);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/auth/v1/token?grant_type=refresh_token",BuildConfig.MAIN_SUPABASE_KEY,null,body);
        String email=json.optJSONObject("user")!=null?json.optJSONObject("user").optString("email",""):"";
        return authResult(json,email);
    }

    public static void resetPassword(String email)throws Exception{
        email=InputGuard.normalizeEmail(email);
        if(!InputGuard.validEmail(email))throw new IllegalArgumentException("Informe um e-mail válido.");
        requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-reset-password",BuildConfig.MAIN_SUPABASE_KEY,null,new JSONObject().put("email",email));
    }

    public static UploadTicket requestUploadTicket(String accessToken,String target,String fileName,String contentType,long sizeBytes)throws Exception{
        if(accessToken==null||accessToken.isEmpty())throw new IllegalStateException("Faça login para usar o processamento em nuvem.");
        if(!"raw".equals(target)&&!"clips".equals(target))throw new IllegalArgumentException("Destino de upload inválido.");
        if(sizeBytes<=0||sizeBytes>MAX_VIDEO_BYTES)throw new IllegalArgumentException("Tamanho de vídeo não permitido.");
        String safeName=InputGuard.safeFileName(fileName);
        String mime=normalizeVideoMime(contentType);
        JSONObject body=new JSONObject()
            .put("target",target)
            .put("fileName",safeName)
            .put("contentType",mime)
            .put("sizeBytes",sizeBytes);
        JSONObject json=requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-storage-ticket",BuildConfig.MAIN_SUPABASE_KEY,accessToken,body);
        String url=json.optString("uploadUrl",null),path=json.optString("path",null);
        long expires=json.optLong("expiresAt",System.currentTimeMillis()+15*60_000L);
        if(url==null||path==null)throw new IllegalStateException("Upload em nuvem indisponível.");
        validateUploadUrl(url,target);
        return new UploadTicket(url,path,expires);
    }

    public static void uploadSigned(Context context,Uri uri,UploadTicket ticket,String contentType,long sizeBytes,VideoUploadProgress progress)throws Exception{
        validateUploadUrl(ticket.uploadUrl,"raw");
        if(sizeBytes<=0||sizeBytes>MAX_VIDEO_BYTES)throw new IllegalArgumentException("Tamanho de vídeo não permitido.");
        HttpURLConnection c=openUpload(ticket.uploadUrl,normalizeVideoMime(contentType),sizeBytes);
        try(InputStream in=new BufferedInputStream(context.getContentResolver().openInputStream(uri),256*1024);
            OutputStream out=c.getOutputStream()){
            if(in==null)throw new IllegalStateException("Não foi possível abrir o vídeo.");
            copyWithProgress(in,out,sizeBytes,progress);
        }
        ensure2xx(c,"Falha no upload seguro");
    }

    public static void uploadSignedFile(File file,UploadTicket ticket,String contentType,VideoUploadProgress progress)throws Exception{
        if(file==null||!file.isFile()||file.length()<=0||file.length()>MAX_VIDEO_BYTES)throw new IllegalArgumentException("Arquivo de clip inválido.");
        validateUploadUrl(ticket.uploadUrl,"clips");
        HttpURLConnection c=openUpload(ticket.uploadUrl,normalizeVideoMime(contentType),file.length());
        try(InputStream in=new BufferedInputStream(new FileInputStream(file),256*1024);
            OutputStream out=c.getOutputStream()){
            copyWithProgress(in,out,file.length(),progress);
        }
        ensure2xx(c,"Falha no upload seguro do clip");
    }

    public static void finalizeCloud(String accessToken,String rawPath,String clipPath)throws Exception{
        if(accessToken==null||accessToken.isEmpty())return;
        JSONObject body=new JSONObject();
        if(rawPath!=null)body.put("rawPath",rawPath);
        if(clipPath!=null)body.put("clipPath",clipPath);
        body.put("clipTtlMinutes",30);
        requestJson("POST",BuildConfig.MAIN_SUPABASE_URL+"/functions/v1/clipforge-finalize",BuildConfig.MAIN_SUPABASE_KEY,accessToken,body);
    }

    private static AuthResult authResult(JSONObject json,String fallbackEmail){
        JSONObject user=json.optJSONObject("user");
        return new AuthResult(
            json.optString("access_token",null),
            json.optString("refresh_token",null),
            json.optLong("expires_in",3600L),
            user!=null?user.optString("email",fallbackEmail):fallbackEmail
        );
    }

    private static String normalizeVideoMime(String contentType){
        String mime=contentType==null?"video/mp4":contentType.toLowerCase(Locale.ROOT).trim();
        if(!mime.equals("video/mp4")&&!mime.equals("video/quicktime")&&!mime.equals("video/x-matroska")){
            throw new IllegalArgumentException("Formato de vídeo não permitido.");
        }
        return mime;
    }

    private static void validateUploadUrl(String value,String target)throws Exception{
        URI uri=new URI(value);
        if(!"https".equalsIgnoreCase(uri.getScheme()))throw new SecurityException("URL de upload insegura.");
        String host=uri.getHost();
        String expected="raw".equals(target)?RAW_HOST:CLIPS_HOST;
        if(host==null||!host.equalsIgnoreCase(expected))throw new SecurityException("Destino de upload não autorizado.");
        if(uri.getUserInfo()!=null||uri.getPort()!=-1)throw new SecurityException("URL de upload inválida.");
    }

    private static HttpURLConnection openUpload(String url,String mime,long size)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(120_000);
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("PUT");
        c.setRequestProperty("Content-Type",mime);
        c.setRequestProperty("x-upsert","false");
        c.setFixedLengthStreamingMode(size);
        return c;
    }

    private static void copyWithProgress(InputStream in,OutputStream out,long size,VideoUploadProgress progress)throws Exception{
        byte[] buf=new byte[256*1024];
        long sent=0;
        int n;
        while((n=in.read(buf))>=0){
            if(n==0)continue;
            out.write(buf,0,n);
            sent+=n;
            if(progress!=null&&size>0)progress.onProgress((int)Math.min(100,sent*100L/size));
        }
    }

    private static void ensure2xx(HttpURLConnection c,String message)throws Exception{
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new IllegalStateException(message+" (HTTP "+code+").");
    }

    private static JSONObject requestJson(String method,String endpoint,String apiKey,String accessToken,JSONObject body)throws Exception{
        URL u=new URL(endpoint);
        if(!"https".equalsIgnoreCase(u.getProtocol())||!u.getHost().equalsIgnoreCase("numasmkmeigyhwhqeffa.supabase.co")){
            throw new SecurityException("Endpoint não autorizado.");
        }
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("apikey",apiKey);
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("Cache-Control","no-store");
        if(accessToken!=null&&!accessToken.isEmpty())c.setRequestProperty("Authorization","Bearer "+accessToken);

        if(body!=null){
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>8192)throw new IllegalArgumentException("Solicitação muito grande.");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        }

        int code=c.getResponseCode();
        InputStream stream=code>=200&&code<400?c.getInputStream():c.getErrorStream();
        String text=readAllLimited(stream,64*1024);
        if(code<200||code>=300){
            String message="Solicitação recusada.";
            try{
                JSONObject err=new JSONObject(text);
                message=err.optString("message",err.optString("msg",message));
            }catch(Exception ignored){}
            throw new IllegalStateException(message);
        }
        return text==null||text.trim().isEmpty()?new JSONObject():new JSONObject(text);
    }

    private static String readAllLimited(InputStream in,int limit)throws Exception{
        if(in==null)return "";
        StringBuilder sb=new StringBuilder();
        int read=0;
        try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String line;
            while((line=br.readLine())!=null){
                read+=line.length();
                if(read>limit)throw new IllegalStateException("Resposta do servidor excedeu o limite.");
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
