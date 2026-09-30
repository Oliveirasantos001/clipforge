package com.clipforge.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements ClipForgeView.ActionListener {
    private static final int PICK=2001, NOTIF=2002;
    private final AppState s=new AppState();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(), cloud=Executors.newSingleThreadExecutor();
    private FrameLayout root; private ClipForgeView ui; private VideoView video; private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(android.graphics.Color.rgb(8,9,16));getWindow().setNavigationBarColor(android.graphics.Color.rgb(8,9,16));
        prefs=getSharedPreferences("clipforge",MODE_PRIVATE);s.authToken=prefs.getString("access_token",null);s.email=prefs.getString("email","");s.userName=prefs.getString("name","Daniel");
        root=new FrameLayout(this);ui=new ClipForgeView(this,s,this);root.addView(ui,new FrameLayout.LayoutParams(-1,-1));video=new VideoView(this);video.setVisibility(View.GONE);root.addView(video,new FrameLayout.LayoutParams(1,1));setContentView(root);
        cleanup();oauth(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);oauth(i);}
    private void oauth(Intent i){
        Uri u=i==null?null:i.getData();if(u==null||!"clipforge".equals(u.getScheme())||u.getFragment()==null)return;
        String token=null;for(String pair:u.getFragment().split("&")){String[] kv=pair.split("=",2);if(kv.length==2&&"access_token".equals(kv[0]))token=Uri.decode(kv[1]);}
        if(token!=null){s.authToken=token;prefs.edit().putString("access_token",token).apply();go(AppState.Screen.HOME);toast("Login concluído.");}
    }

    @Override public void onAction(String a){
        switch(a){
            case "back":back();break;case "home":go(AppState.Screen.HOME);break;case "projects":go(AppState.Screen.PROJECTS);break;case "new_project":go(AppState.Screen.NEW_PROJECT);break;
            case "analytics":go(AppState.Screen.ANALYTICS);break;case "profile":go(AppState.Screen.PROFILE);break;case "email_login":s.password="";s.confirmPassword="";go(AppState.Screen.EMAIL_LOGIN);break;case "signup":s.password="";s.confirmPassword="";go(AppState.Screen.SIGN_UP);break;
            case "reset":go(AppState.Screen.RESET_PASSWORD);break;case "pro":go(AppState.Screen.PRO);break;case "account":go(AppState.Screen.ACCOUNT);break;case "security":go(AppState.Screen.SECURITY_PRIVACY);break;
            case "storage":go(AppState.Screen.STORAGE);break;case "project_details":go(AppState.Screen.PROJECT_DETAILS);break;case "processing":go(AppState.Screen.PROCESSING);break;case "editor":go(AppState.Screen.EDITOR);break;
            case "export":go(AppState.Screen.EXPORT);break;case "edit_email":prompt("E-mail",s.email,false,v->{s.email=v.trim();ui.refresh();});break;case "edit_password":prompt("Senha","",true,v->{s.password=v;ui.refresh();});break;
            case "edit_name":prompt("Nome",s.userName,false,v->{s.userName=v.trim();prefs.edit().putString("name",s.userName).apply();ui.refresh();});break;case "edit_confirm_password":prompt("Confirmar senha","",true,v->{s.confirmPassword=v;ui.refresh();});break;case "google_login":toast("Login com Google ainda não está configurado no servidor.");break;
            case "do_login":login();break;case "do_signup":signup();break;case "do_reset":reset();break;case "pick_video":pick();break;case "style_comp":s.selectedStyle="Competitivo";ui.refresh();break;
            case "style_fun":s.selectedStyle="Engraçado";ui.refresh();break;case "style_high":s.selectedStyle="Highlights";ui.refresh();break;case "style_react":s.selectedStyle="Reações";ui.refresh();break;
            case "style_cine":s.selectedStyle="Cinematográfico";ui.refresh();break;case "plat_tiktok":s.selectedPlatform="TikTok";ui.refresh();break;case "plat_youtube":s.selectedPlatform="YouTube Shorts";ui.refresh();break;
            case "plat_instagram":s.selectedPlatform="Instagram Reels";ui.refresh();break;case "analyze":analyze();break;case "watch":s.selectedCandidate=0;go(AppState.Screen.VIEW_CLIP);break;case "play_clip":play();break;
            case "generate_clips":generate();break;case "save_edit":toast("Ajustes salvos.");break;case "caption":toast("Legenda inteligente preparada.");break;case "smart_crop":toast("Smart Crop ativado.");break;
            case "do_export":export();break;case "open_export":openExport();break;case "share":share();break;case "grant_files":pick();break;case "grant_notifications":notificationPermission();break;
            case "cleanup_local":toast(cleanup()+" arquivo(s) temporário(s) removido(s).");break;case "delete_account":toast("A exclusão definitiva exige confirmação adicional.");break;case "subscribe":toast("Plano pronto para integração com Google Play Billing.");break;
            case "prefs":case "quality":case "notifications":case "help":case "about":toast("Opção prevista na próxima revisão.");break;
        }
    }

    private void go(AppState.Screen x){hideVideo();s.navigate(x);s.statusMessage="";ui.refresh();}
    private void back(){hideVideo();if(s.screen==AppState.Screen.LOGIN){finish();return;}if(s.screen==AppState.Screen.HOME){go(AppState.Screen.LOGIN);return;}AppState.Screen p=s.previousScreen;if(p==s.screen)p=AppState.Screen.HOME;s.screen=p;ui.refresh();}
    @Override public void onBackPressed(){if(video.getVisibility()==View.VISIBLE){hideVideo();return;}back();}

    private interface V{void set(String x);}
    private void prompt(String title,String initial,boolean pass,V cb){EditText e=new EditText(this);e.setText(initial==null?"":initial);e.setSingleLine();if(pass)e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);new AlertDialog.Builder(this).setTitle(title).setView(e).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",(d,w)->cb.set(e.getText().toString())).show();}
    private void login(){if(s.email.isEmpty()||s.password.isEmpty()){s.statusMessage="Informe e-mail e senha.";ui.refresh();return;}s.statusMessage="Entrando…";ui.refresh();worker.execute(()->{try{SupabaseApi.AuthResult r=SupabaseApi.signIn(s.email,s.password);if(r.accessToken==null)throw new IllegalStateException("Resposta inválida.");s.authToken=r.accessToken;s.email=r.email;prefs.edit().putString("access_token",r.accessToken).putString("email",r.email).apply();runOnUiThread(()->go(AppState.Screen.HOME));}catch(Exception e){runOnUiThread(()->{s.statusMessage=err(e);ui.refresh();});}});}
    private void signup(){if(s.email.isEmpty()||s.password.length()<8){s.statusMessage="Use e-mail válido e senha com 8+ caracteres.";ui.refresh();return;}if(!s.password.equals(s.confirmPassword)){s.statusMessage="As senhas não coincidem.";ui.refresh();return;}s.statusMessage="Criando conta…";ui.refresh();worker.execute(()->{try{SupabaseApi.AuthResult r=SupabaseApi.signUp(s.userName,s.email,s.password);prefs.edit().putString("name",s.userName).putString("email",s.email).apply();if(r.accessToken!=null&&!r.accessToken.isEmpty()){s.authToken=r.accessToken;s.email=r.email;prefs.edit().putString("access_token",r.accessToken).putString("email",r.email).apply();runOnUiThread(()->{toast("Conta criada. Você já está conectado.");go(AppState.Screen.HOME);});}else{runOnUiThread(()->{s.statusMessage="Conta criada. Entre com seu e-mail e senha.";toast("Conta criada.");go(AppState.Screen.EMAIL_LOGIN);});}}catch(Exception e){runOnUiThread(()->{s.statusMessage=err(e);ui.refresh();});}});}
    private void reset(){if(s.email.isEmpty()){s.statusMessage="Informe seu e-mail.";ui.refresh();return;}worker.execute(()->{try{SupabaseApi.resetPassword(s.email);runOnUiThread(()->{s.statusMessage="Instruções enviadas.";ui.refresh();});}catch(Exception e){runOnUiThread(()->{s.statusMessage=err(e);ui.refresh();});}});}
    private void browser(String x){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(x)));}catch(Exception e){toast("Não foi possível abrir o navegador.");}}

    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("video/*");startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int rq,int rc,Intent data){super.onActivityResult(rq,rc,data);if(rq==PICK&&rc==RESULT_OK&&data!=null&&data.getData()!=null){Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}s.selectedVideoUri=u;info(u);go(AppState.Screen.NEW_PROJECT);}}
    private void info(Uri u){
        s.selectedVideoName="gameplay.mp4";s.selectedVideoSizeBytes=0;s.selectedVideoDurationMs=0;Cursor c=null;
        try{c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null);if(c!=null&&c.moveToFirst()){int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),z=c.getColumnIndex(OpenableColumns.SIZE);if(n>=0)s.selectedVideoName=c.getString(n);if(z>=0&&!c.isNull(z))s.selectedVideoSizeBytes=c.getLong(z);}}finally{if(c!=null)c.close();}
        MediaMetadataRetriever m=new MediaMetadataRetriever();try{m.setDataSource(this,u);String d=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(d!=null)s.selectedVideoDurationMs=Long.parseLong(d);}catch(Exception ignored){}finally{try{m.release();}catch(Exception ignored){}}
    }

    private void analyze(){
        if(s.selectedVideoUri==null){toast("Escolha uma gameplay primeiro.");return;}s.candidates.clear();s.progress=2;s.progressStage="Preparando análise";go(AppState.Screen.PROCESSING);cloudRaw();
        worker.execute(()->{try{List<AppState.Candidate> r=VideoAnalyzer.analyze(this,s.selectedVideoUri,(pct,stage)->runOnUiThread(()->{s.progress=pct;s.progressStage=stage;ui.refresh();}));s.candidates.clear();s.candidates.addAll(r);s.progress=100;runOnUiThread(()->go(AppState.Screen.RESULTS));}catch(Exception e){runOnUiThread(()->{s.statusMessage=err(e);go(AppState.Screen.ERROR);});}});
    }
    private void cloudRaw(){
        s.rawUploadComplete=false;s.cloudRawPath=null;s.cloudEnabled=false;if(s.authToken==null)return;
        cloud.execute(()->{try{String mime=getContentResolver().getType(s.selectedVideoUri);if(mime==null)mime="video/mp4";SupabaseApi.UploadTicket t=SupabaseApi.requestUploadTicket(s.authToken,"raw",s.selectedVideoName,mime,s.selectedVideoSizeBytes);s.cloudRawPath=t.path;SupabaseApi.uploadSigned(this,s.selectedVideoUri,t,mime,s.selectedVideoSizeBytes,p->{});s.rawUploadComplete=true;s.cloudEnabled=true;}catch(Exception ignored){s.cloudRawPath=null;s.rawUploadComplete=false;}});
    }
    private File clips(){File d=new File(getFilesDir(),"clips");if(!d.exists())d.mkdirs();return d;}
    private File ensure(AppState.Candidate c)throws Exception{if(c==null)throw new IllegalStateException("Nenhum clip selecionado.");if(c.localClip!=null&&c.localClip.exists())return c.localClip;File f=new File(clips(),"clip-"+System.currentTimeMillis()+".mp4");VideoClipper.cut(this,s.selectedVideoUri,c.startMs,c.endMs,f,p->{});c.localClip=f;return f;}
    private void play(){AppState.Candidate c=s.currentCandidate();worker.execute(()->{try{File f=ensure(c);runOnUiThread(()->show(Uri.fromFile(f)));cloudClip(f);}catch(Exception e){runOnUiThread(()->toast("Falha na prévia: "+err(e)));}});}
    private void generate(){if(s.candidates.isEmpty())return;toast("Gerando clips sem reencodar…");worker.execute(()->{try{int n=Math.min(3,s.candidates.size());for(int i=0;i<n;i++){File f=ensure(s.candidates.get(i));if(i==0)cloudClip(f);}runOnUiThread(()->{toast("Clips gerados.");go(AppState.Screen.PROJECT_DETAILS);});}catch(Exception e){runOnUiThread(()->go(AppState.Screen.ERROR));}});}
    private void cloudClip(File f){if(s.authToken==null)return;cloud.execute(()->{try{SupabaseApi.UploadTicket t=SupabaseApi.requestUploadTicket(s.authToken,"clips",f.getName(),"video/mp4",f.length());SupabaseApi.uploadSignedFile(f,t,"video/mp4",p->{});s.cloudClipPath=t.path;long until=System.currentTimeMillis()+120000;while(s.cloudRawPath!=null&&!s.rawUploadComplete&&System.currentTimeMillis()<until)Thread.sleep(1000);SupabaseApi.finalizeCloud(s.authToken,s.rawUploadComplete?s.cloudRawPath:null,s.cloudClipPath);s.cloudRawPath=null;s.rawUploadComplete=false;}catch(Exception ignored){} });}

    private void show(Uri u){root.post(()->{int w=root.getWidth();float k=w/360f;FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams((int)(320*k),(int)(296*k));lp.leftMargin=(int)(20*k);lp.topMargin=(int)(58*k);video.setLayoutParams(lp);MediaController mc=new MediaController(this);mc.setAnchorView(video);video.setMediaController(mc);video.setVideoURI(u);video.setVisibility(View.VISIBLE);video.setOnPreparedListener(m->{m.setLooping(false);video.start();});});}
    private void hideVideo(){if(video!=null){try{video.stopPlayback();}catch(Exception ignored){}video.setVisibility(View.GONE);}}

    private void export(){AppState.Candidate c=s.currentCandidate();worker.execute(()->{try{File f=ensure(c);Uri u=save(f);s.lastExportedFile=f;s.lastExportedUri=u;runOnUiThread(()->go(AppState.Screen.EXPORT_DONE));}catch(Exception e){runOnUiThread(()->toast("Falha ao exportar: "+err(e)));}});}
    private Uri save(File f)throws Exception{
        String name="ClipForge_"+System.currentTimeMillis()+".mp4";
        if(Build.VERSION.SDK_INT>=29){ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,name);v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/ClipForge");v.put(MediaStore.Video.Media.IS_PENDING,1);Uri u=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new IOException("Falha ao criar arquivo.");try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(u,"w")){cp(in,out);}v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);getContentResolver().update(u,v,null,null);return u;}
        File d=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"ClipForge");if(!d.exists())d.mkdirs();File o=new File(d,name);try(InputStream in=new FileInputStream(f);OutputStream out=new FileOutputStream(o)){cp(in,out);}sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(o)));return Uri.fromFile(o);
    }
    private void cp(InputStream in,OutputStream out)throws Exception{byte[] b=new byte[256*1024];int n;while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);}
    private void openExport(){if(s.lastExportedUri==null)return;Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(s.lastExportedUri,"video/mp4");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(i);}catch(Exception e){toast("Nenhum player disponível.");}}
    private void share(){if(s.lastExportedUri==null)return;Intent i=new Intent(Intent.ACTION_SEND);i.setType("video/mp4");i.putExtra(Intent.EXTRA_STREAM,s.lastExportedUri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Compartilhar clip"));}
    private void notificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIF);else toast("Notificações permitidas.");}
    private int cleanup(){File[] a=clips().listFiles();if(a==null)return 0;long cut=System.currentTimeMillis()-86400000L;int n=0;for(File f:a)if(f.isFile()&&f.lastModified()<cut&&f.delete())n++;return n;}
    private String err(Exception e){String m=e.getMessage();if(m==null||m.isEmpty())m=e.getClass().getSimpleName();m=m.replace('\n',' ');return m.length()>72?m.substring(0,72)+"…":m;}
    private void toast(String x){Toast.makeText(this,x,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();cloud.shutdownNow();}
}
