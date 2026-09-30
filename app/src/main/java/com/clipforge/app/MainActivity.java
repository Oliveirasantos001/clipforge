package com.clipforge.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements ClipForgeView.ActionListener {
    private static final int PICK=2001, NOTIF=2002;
    private static final long MAX_LOCAL_VIDEO_BYTES=5L*1024L*1024L*1024L;

    private final AppState s=new AppState();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService cloud=Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ClipForgeView ui;
    private VideoView video;
    private SecurePrefs securePrefs;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private AlertDialog vpnDialog;
    private volatile boolean authBusy=false;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(8,9,16));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8,9,16));

        securePrefs=new SecurePrefs(this);
        migrateLegacyPreferences();
        s.authToken=securePrefs.getString("access_token",null);
        s.refreshToken=securePrefs.getString("refresh_token",null);
        s.email=securePrefs.getString("email","");
        s.userName=securePrefs.getString("name","Daniel");
        try{s.sessionExpiresAtMs=Long.parseLong(securePrefs.getString("expires_at","0"));}catch(Exception ignored){s.sessionExpiresAtMs=0L;}

        root=new FrameLayout(this);
        ui=new ClipForgeView(this,s,this);
        root.addView(ui,new FrameLayout.LayoutParams(-1,-1));
        video=new VideoView(this);
        video.setVisibility(View.GONE);
        root.addView(video,new FrameLayout.LayoutParams(1,1));
        setContentView(root);

        ClipCleanupJobService.cleanupNow(this);
        ClipCleanupJobService.scheduleNext(this);
        registerNetworkGuard();

        if(s.refreshToken!=null&&!s.refreshToken.isEmpty()){
            worker.execute(()->{
                try{
                    SupabaseApi.AuthResult refreshed=SupabaseApi.refreshSession(s.refreshToken);
                    storeSession(refreshed);
                    runOnUiThread(()->go(AppState.Screen.HOME));
                }catch(Exception ignored){
                    clearSession();
                }
            });
        }
    }

    @Override protected void onResume(){
        super.onResume();
        enforceVpnPolicy();
    }

    private void migrateLegacyPreferences(){
        SharedPreferences legacy=getSharedPreferences("clipforge",MODE_PRIVATE);
        String oldToken=legacy.getString("access_token",null);
        String oldEmail=legacy.getString("email",null);
        String oldName=legacy.getString("name",null);
        if(oldToken!=null&&securePrefs.getString("access_token",null)==null)securePrefs.putString("access_token",oldToken);
        if(oldEmail!=null&&securePrefs.getString("email",null)==null)securePrefs.putString("email",oldEmail);
        if(oldName!=null&&securePrefs.getString("name",null)==null)securePrefs.putString("name",oldName);
        legacy.edit().clear().apply();
    }

    private void registerNetworkGuard(){
        connectivityManager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivityManager==null)return;
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){runOnUiThread(MainActivity.this::enforceVpnPolicy);}
            @Override public void onLost(Network network){runOnUiThread(MainActivity.this::enforceVpnPolicy);}
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){runOnUiThread(MainActivity.this::enforceVpnPolicy);}
        };
        try{connectivityManager.registerDefaultNetworkCallback(networkCallback);}catch(Exception ignored){}
    }

    private void enforceVpnPolicy(){
        if(!NetworkGuard.isVpnActive(this)){
            if(vpnDialog!=null&&vpnDialog.isShowing())vpnDialog.dismiss();
            return;
        }
        if(vpnDialog!=null&&vpnDialog.isShowing())return;
        vpnDialog=new AlertDialog.Builder(this)
            .setTitle("VPN detectada")
            .setMessage("O Android informou que a conexão ativa está usando VPN. Desative a VPN para continuar usando o ClipForge.")
            .setCancelable(false)
            .setPositiveButton("Verificar novamente",(d,w)->enforceVpnPolicy())
            .setNegativeButton("Fechar app",(d,w)->finishAffinity())
            .create();
        vpnDialog.show();
    }

    private boolean blockedByVpn(){
        if(NetworkGuard.isVpnActive(this)){
            enforceVpnPolicy();
            return true;
        }
        return false;
    }

    @Override public void onAction(String a){
        if(blockedByVpn())return;
        switch(a){
            case "back":back();break;
            case "home":go(AppState.Screen.HOME);break;
            case "projects":go(AppState.Screen.PROJECTS);break;
            case "new_project":go(AppState.Screen.NEW_PROJECT);break;
            case "analytics":go(AppState.Screen.ANALYTICS);break;
            case "profile":go(AppState.Screen.PROFILE);break;
            case "email_login":s.password="";s.confirmPassword="";go(AppState.Screen.EMAIL_LOGIN);break;
            case "signup":s.password="";s.confirmPassword="";go(AppState.Screen.SIGN_UP);break;
            case "reset":go(AppState.Screen.RESET_PASSWORD);break;
            case "pro":go(AppState.Screen.PRO);break;
            case "account":go(AppState.Screen.ACCOUNT);break;
            case "security":go(AppState.Screen.SECURITY_PRIVACY);break;
            case "storage":go(AppState.Screen.STORAGE);break;
            case "project_details":go(AppState.Screen.PROJECT_DETAILS);break;
            case "processing":go(AppState.Screen.PROCESSING);break;
            case "editor":go(AppState.Screen.EDITOR);break;
            case "export":go(AppState.Screen.EXPORT);break;

            case "edit_email":
                prompt("E-mail",s.email,false,v->{
                    String e=InputGuard.normalizeEmail(v);
                    if(!InputGuard.validEmail(e)){toast("Informe um e-mail válido.");return;}
                    s.email=e;ui.refresh();
                });
                break;
            case "edit_password":
                prompt("Senha","",true,v->{s.password=v;ui.refresh();});
                break;
            case "edit_confirm_password":
                prompt("Confirmar senha","",true,v->{s.confirmPassword=v;ui.refresh();});
                break;
            case "edit_name":
                prompt("Nome",s.userName,false,v->{
                    if(!InputGuard.validName(v)){toast("Use apenas um nome válido com até 80 caracteres.");return;}
                    s.userName=v.trim();securePrefs.putString("name",s.userName);ui.refresh();
                });
                break;

            case "google_login":toast("Login com Google permanece desativado até o OAuth seguro ser configurado.");break;
            case "do_login":login();break;
            case "do_signup":signup();break;
            case "do_reset":reset();break;
            case "pick_video":pick();break;

            case "style_comp":s.selectedStyle="Competitivo";ui.refresh();break;
            case "style_fun":s.selectedStyle="Engraçado";ui.refresh();break;
            case "style_high":s.selectedStyle="Highlights";ui.refresh();break;
            case "style_react":s.selectedStyle="Reações";ui.refresh();break;
            case "style_cine":s.selectedStyle="Cinematográfico";ui.refresh();break;
            case "plat_tiktok":s.selectedPlatform="TikTok";ui.refresh();break;
            case "plat_youtube":s.selectedPlatform="YouTube Shorts";ui.refresh();break;
            case "plat_instagram":s.selectedPlatform="Instagram Reels";ui.refresh();break;

            case "analyze":analyze();break;
            case "watch":s.selectedCandidate=0;go(AppState.Screen.VIEW_CLIP);break;
            case "play_clip":play();break;
            case "generate_clips":generate();break;
            case "save_edit":toast("Ajustes salvos.");break;
            case "caption":toast("Legendas avançadas ainda não são aplicadas ao arquivo exportado.");break;
            case "smart_crop":toast("Smart Crop avançado ainda não é aplicado ao arquivo exportado.");break;

            case "do_export":export();break;
            case "open_export":openExport();break;
            case "share":share();break;
            case "grant_files":pick();break;
            case "grant_notifications":notificationPermission();break;
            case "cleanup_local":
                toast(ClipCleanupJobService.cleanupNow(this)+" arquivo(s) temporário(s) removido(s).");
                ClipCleanupJobService.scheduleNext(this);
                break;
            case "delete_account":toast("A exclusão definitiva exige confirmação adicional.");break;
            case "subscribe":toast("Plano ainda não integrado ao Google Play Billing.");break;
            case "prefs":case "quality":case "notifications":case "help":case "about":
                toast("Opção ainda não implementada.");
                break;
        }
    }

    private void go(AppState.Screen x){
        hideVideo();
        s.navigate(x);
        s.statusMessage="";
        ui.refresh();
    }

    private void back(){
        hideVideo();
        if(s.screen==AppState.Screen.LOGIN){finish();return;}
        if(s.screen==AppState.Screen.HOME){go(AppState.Screen.LOGIN);return;}
        AppState.Screen p=s.previousScreen;
        if(p==s.screen)p=AppState.Screen.HOME;
        s.screen=p;
        ui.refresh();
    }

    @Override public void onBackPressed(){
        if(video.getVisibility()==View.VISIBLE){hideVideo();return;}
        back();
    }

    private interface V{void set(String x);}
    private void prompt(String title,String initial,boolean pass,V cb){
        EditText e=new EditText(this);
        e.setText(initial==null?"":initial);
        e.setSingleLine();
        if(pass)e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(e)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",(d,w)->cb.set(e.getText().toString()))
            .show();
    }

    private void login(){
        if(authBusy){toast("Aguarde a tentativa atual.");return;}
        s.email=InputGuard.normalizeEmail(s.email);
        if(!InputGuard.validEmail(s.email)||s.password==null||s.password.isEmpty()||s.password.length()>128){
            s.statusMessage="Informe e-mail e senha válidos.";
            ui.refresh();
            return;
        }
        s.statusMessage="Entrando…";
        authBusy=true;
        ui.refresh();
        worker.execute(()->{
            try{
                SupabaseApi.AuthResult r=SupabaseApi.signIn(s.email,s.password);
                if(r.accessToken==null||r.refreshToken==null)throw new IllegalStateException("Resposta de sessão inválida.");
                storeSession(r);
                s.password="";
                s.confirmPassword="";
                runOnUiThread(()->{authBusy=false;go(AppState.Screen.HOME);});
            }catch(Exception e){
                runOnUiThread(()->{authBusy=false;s.statusMessage=err(e);ui.refresh();});
            }
        });
    }

    private void signup(){
        if(authBusy){toast("Aguarde a tentativa atual.");return;}
        s.email=InputGuard.normalizeEmail(s.email);
        if(!InputGuard.validName(s.userName)){s.statusMessage="Informe um nome válido.";ui.refresh();return;}
        if(!InputGuard.validEmail(s.email)){s.statusMessage="Informe um e-mail válido.";ui.refresh();return;}
        if(!InputGuard.validPassword(s.password)){s.statusMessage="Use uma senha entre 8 e 128 caracteres.";ui.refresh();return;}
        if(!s.password.equals(s.confirmPassword)){s.statusMessage="As senhas não coincidem.";ui.refresh();return;}

        s.statusMessage="Criando conta…";
        authBusy=true;
        ui.refresh();
        worker.execute(()->{
            try{
                SupabaseApi.AuthResult r=SupabaseApi.signUp(s.userName,s.email,s.password);
                if(r.accessToken==null||r.refreshToken==null)throw new IllegalStateException("Resposta de sessão inválida.");
                storeSession(r);
                s.password="";
                s.confirmPassword="";
                runOnUiThread(()->{
                    authBusy=false;
                    toast("Conta criada e conectada.");
                    go(AppState.Screen.HOME);
                });
            }catch(Exception e){
                runOnUiThread(()->{authBusy=false;s.statusMessage=err(e);ui.refresh();});
            }
        });
    }

    private void reset(){
        if(authBusy){toast("Aguarde a tentativa atual.");return;}
        s.email=InputGuard.normalizeEmail(s.email);
        if(!InputGuard.validEmail(s.email)){s.statusMessage="Informe um e-mail válido.";ui.refresh();return;}
        authBusy=true;
        worker.execute(()->{
            try{
                SupabaseApi.resetPassword(s.email);
                runOnUiThread(()->{
                    authBusy=false;
                    s.statusMessage="Se a conta existir, as instruções serão enviadas.";
                    ui.refresh();
                });
            }catch(Exception e){
                runOnUiThread(()->{authBusy=false;s.statusMessage=err(e);ui.refresh();});
            }
        });
    }

    private void storeSession(SupabaseApi.AuthResult r){
        s.authToken=r.accessToken;
        s.refreshToken=r.refreshToken;
        s.email=r.email==null?"":r.email;
        s.sessionExpiresAtMs=System.currentTimeMillis()+Math.max(60,r.expiresInSeconds)*1000L;
        securePrefs.putString("access_token",s.authToken);
        securePrefs.putString("refresh_token",s.refreshToken);
        securePrefs.putString("expires_at",Long.toString(s.sessionExpiresAtMs));
        securePrefs.putString("email",s.email);
        securePrefs.putString("name",s.userName);
    }

    private void clearSession(){
        s.authToken=null;
        s.refreshToken=null;
        s.sessionExpiresAtMs=0L;
        securePrefs.remove("access_token");
        securePrefs.remove("refresh_token");
        securePrefs.remove("expires_at");
    }

    private void refreshSessionBlocking()throws Exception{
        if(s.refreshToken==null||s.refreshToken.isEmpty())throw new IllegalStateException("Sessão expirada.");
        if(s.authToken!=null&&System.currentTimeMillis()<s.sessionExpiresAtMs-60_000L)return;
        storeSession(SupabaseApi.refreshSession(s.refreshToken));
    }

    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(i,PICK);
    }

    @Override protected void onActivityResult(int rq,int rc,Intent data){
        super.onActivityResult(rq,rc,data);
        if(rq==PICK&&rc==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri u=data.getData();
            try{getContentResolver().takePersistableUriPermission(u,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            try{
                info(u);
                s.selectedVideoUri=u;
                go(AppState.Screen.NEW_PROJECT);
            }catch(Exception e){
                toast(err(e));
            }
        }
    }

    private void info(Uri u)throws Exception{
        s.selectedVideoName="gameplay.mp4";
        s.selectedVideoSizeBytes=0;
        s.selectedVideoDurationMs=0;

        Cursor c=null;
        try{
            c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null);
            if(c!=null&&c.moveToFirst()){
                int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int z=c.getColumnIndex(OpenableColumns.SIZE);
                if(n>=0)s.selectedVideoName=InputGuard.safeFileName(c.getString(n));
                if(z>=0&&!c.isNull(z))s.selectedVideoSizeBytes=c.getLong(z);
            }
        }finally{if(c!=null)c.close();}

        if(s.selectedVideoSizeBytes>MAX_LOCAL_VIDEO_BYTES)throw new IllegalArgumentException("O vídeo excede o limite de 5 GB.");

        String mime=getContentResolver().getType(u);
        if(mime!=null&&!mime.startsWith("video/"))throw new IllegalArgumentException("O arquivo selecionado não é um vídeo.");

        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try{
            m.setDataSource(this,u);
            String d=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(d!=null)s.selectedVideoDurationMs=Long.parseLong(d);
        }finally{
            try{m.release();}catch(Exception ignored){}
        }
        if(s.selectedVideoDurationMs<=0)throw new IllegalArgumentException("Não foi possível validar a duração do vídeo.");
    }

    private void analyze(){
        if(s.selectedVideoUri==null){toast("Escolha uma gameplay primeiro.");return;}
        s.candidates.clear();
        s.progress=2;
        s.progressStage="Preparando análise real do vídeo";
        go(AppState.Screen.PROCESSING);
        if(BuildConfig.CLOUD_PIPELINE_ENABLED)cloudRaw();

        worker.execute(()->{
            try{
                List<AppState.Candidate> r=VideoAnalyzer.analyze(this,s.selectedVideoUri,(pct,stage)->runOnUiThread(()->{
                    s.progress=pct;
                    s.progressStage=stage;
                    ui.refresh();
                }));
                s.candidates.clear();
                s.candidates.addAll(r);
                s.progress=100;
                runOnUiThread(()->go(AppState.Screen.RESULTS));
            }catch(Exception e){
                runOnUiThread(()->{
                    s.statusMessage=err(e);
                    go(AppState.Screen.ERROR);
                });
            }
        });
    }

    private void cloudRaw(){
        if(!BuildConfig.CLOUD_PIPELINE_ENABLED||s.authToken==null)return;
        s.rawUploadComplete=false;
        s.cloudRawPath=null;
        s.cloudEnabled=false;
        cloud.execute(()->{
            try{
                refreshSessionBlocking();
                String mime=getContentResolver().getType(s.selectedVideoUri);
                if(mime==null)mime="video/mp4";
                SupabaseApi.UploadTicket t=SupabaseApi.requestUploadTicket(s.authToken,"raw",s.selectedVideoName,mime,s.selectedVideoSizeBytes);
                s.cloudRawPath=t.path;
                SupabaseApi.uploadSigned(this,s.selectedVideoUri,t,mime,s.selectedVideoSizeBytes,p->{});
                s.rawUploadComplete=true;
                s.cloudEnabled=true;
            }catch(Exception ignored){
                s.cloudRawPath=null;
                s.rawUploadComplete=false;
            }
        });
    }

    private File clips(){return ClipCleanupJobService.clipsDir(this);}

    private File ensure(AppState.Candidate c)throws Exception{
        if(c==null)throw new IllegalStateException("Nenhum clip selecionado.");
        if(c.localClip!=null&&c.localClip.exists())return c.localClip;
        File f=new File(clips(),"clip-"+System.currentTimeMillis()+".mp4");
        VideoClipper.cut(this,s.selectedVideoUri,c.startMs,c.endMs,f,p->{});
        if(!f.isFile()||f.length()<1024)throw new IllegalStateException("O clip gerado é inválido.");
        c.localClip=f;
        ClipCleanupJobService.scheduleNext(this);
        return f;
    }

    private void play(){
        AppState.Candidate c=s.currentCandidate();
        worker.execute(()->{
            try{
                File f=ensure(c);
                runOnUiThread(()->show(Uri.fromFile(f)));
                if(BuildConfig.CLOUD_PIPELINE_ENABLED)cloudClip(f);
            }catch(Exception e){
                runOnUiThread(()->toast("Falha na prévia: "+err(e)));
            }
        });
    }

    private void generate(){
        if(s.candidates.isEmpty())return;
        toast("Gerando clips reais do vídeo selecionado…");
        worker.execute(()->{
            try{
                int n=Math.min(3,s.candidates.size());
                for(int i=0;i<n;i++){
                    File f=ensure(s.candidates.get(i));
                    if(i==0&&BuildConfig.CLOUD_PIPELINE_ENABLED)cloudClip(f);
                }
                runOnUiThread(()->{
                    toast("Clips gerados. Os temporários expiram em 30 minutos.");
                    go(AppState.Screen.PROJECT_DETAILS);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    s.statusMessage=err(e);
                    go(AppState.Screen.ERROR);
                });
            }
        });
    }

    private void cloudClip(File f){
        if(!BuildConfig.CLOUD_PIPELINE_ENABLED||s.authToken==null)return;
        cloud.execute(()->{
            try{
                refreshSessionBlocking();
                SupabaseApi.UploadTicket t=SupabaseApi.requestUploadTicket(s.authToken,"clips",f.getName(),"video/mp4",f.length());
                SupabaseApi.uploadSignedFile(f,t,"video/mp4",p->{});
                s.cloudClipPath=t.path;
                long until=System.currentTimeMillis()+120000;
                while(s.cloudRawPath!=null&&!s.rawUploadComplete&&System.currentTimeMillis()<until)Thread.sleep(1000);
                SupabaseApi.finalizeCloud(s.authToken,s.rawUploadComplete?s.cloudRawPath:null,s.cloudClipPath);
                s.cloudRawPath=null;
                s.rawUploadComplete=false;
            }catch(Exception ignored){}
        });
    }

    private void show(Uri u){
        root.post(()->{
            int w=root.getWidth();
            float k=w/360f;
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams((int)(320*k),(int)(296*k));
            lp.leftMargin=(int)(20*k);
            lp.topMargin=(int)(58*k);
            video.setLayoutParams(lp);
            MediaController mc=new MediaController(this);
            mc.setAnchorView(video);
            video.setMediaController(mc);
            video.setVideoURI(u);
            video.setVisibility(View.VISIBLE);
            video.setOnPreparedListener(m->{m.setLooping(false);video.start();});
        });
    }

    private void hideVideo(){
        if(video!=null){
            try{video.stopPlayback();}catch(Exception ignored){}
            video.setVisibility(View.GONE);
        }
    }

    private void export(){
        AppState.Candidate c=s.currentCandidate();
        worker.execute(()->{
            try{
                File f=ensure(c);
                Uri u=save(f);
                s.lastExportedFile=f;
                s.lastExportedUri=u;
                runOnUiThread(()->go(AppState.Screen.EXPORT_DONE));
            }catch(Exception e){
                runOnUiThread(()->toast("Falha ao exportar: "+err(e)));
            }
        });
    }

    private Uri save(File f)throws Exception{
        String name="ClipForge_"+System.currentTimeMillis()+".mp4";
        if(Build.VERSION.SDK_INT>=29){
            ContentValues v=new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME,name);
            v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/ClipForge");
            v.put(MediaStore.Video.Media.IS_PENDING,1);
            Uri u=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
            if(u==null)throw new java.io.IOException("Falha ao criar arquivo.");
            try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(u,"w")){
                if(out==null)throw new java.io.IOException("Falha ao abrir destino.");
                cp(in,out);
            }
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING,0);
            getContentResolver().update(u,v,null,null);
            return u;
        }

        File d=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"ClipForge");
        if(!d.exists()&&!d.mkdirs())throw new java.io.IOException("Falha ao criar pasta de exportação.");
        File o=new File(d,name);
        try(InputStream in=new FileInputStream(f);OutputStream out=new FileOutputStream(o)){cp(in,out);}
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(o)));
        return Uri.fromFile(o);
    }

    private void cp(InputStream in,OutputStream out)throws Exception{
        byte[] b=new byte[256*1024];
        int n;
        while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);
    }

    private void openExport(){
        if(s.lastExportedUri==null)return;
        Intent i=new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(s.lastExportedUri,"video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(i);}catch(Exception e){toast("Nenhum player disponível.");}
    }

    private void share(){
        if(s.lastExportedUri==null)return;
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM,s.lastExportedUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"Compartilhar clip"));
    }

    private void notificationPermission(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIF);
        }else toast("Notificações permitidas.");
    }

    private String err(Exception e){
        String m=e.getMessage();
        if(m==null||m.isEmpty())m="Operação não concluída.";
        m=m.replace('\n',' ').replace('\r',' ');
        return m.length()>96?m.substring(0,96)+"…":m;
    }

    private void toast(String x){Toast.makeText(this,x,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){
        if(connectivityManager!=null&&networkCallback!=null){
            try{connectivityManager.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        }
        if(vpnDialog!=null&&vpnDialog.isShowing())vpnDialog.dismiss();
        worker.shutdownNow();
        cloud.shutdownNow();
        super.onDestroy();
    }
}
