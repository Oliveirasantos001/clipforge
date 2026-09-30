package com.clipforge.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class VideoAnalyzer {
    public interface ProgressListener { void onProgress(int percent, String stage); }
    private static final class Peak { final long timeMs; final double delta; Peak(long t,double d){timeMs=t;delta=d;} }
    private VideoAnalyzer(){}

    public static List<AppState.Candidate> analyze(Context context, Uri uri, ProgressListener listener) throws Exception {
        MediaMetadataRetriever mmr=new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context,uri);
            String ds=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs=ds==null?0:Long.parseLong(ds);
            if(durationMs<=0)throw new IllegalArgumentException("Não foi possível ler a duração do vídeo.");
            int sampleCount=(int)Math.max(24,Math.min(140,durationMs/2500L));
            long stepMs=Math.max(1000L,durationMs/sampleCount);
            List<Peak> peaks=new ArrayList<>();
            double[] previous=null; int done=0;
            for(long t=0;t<durationMs;t+=stepMs){
                Bitmap frame=null,scaled=null;
                try{
                    if(Build.VERSION.SDK_INT>=27) frame=mmr.getScaledFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,96,54);
                    else { frame=mmr.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC); if(frame!=null) scaled=Bitmap.createScaledBitmap(frame,96,54,true); }
                    Bitmap use=scaled!=null?scaled:frame;
                    if(use!=null){double[] sig=signature(use);if(previous!=null)peaks.add(new Peak(t,distance(previous,sig)));previous=sig;}
                } finally {
                    if(scaled!=null&&scaled!=frame&&!scaled.isRecycled())scaled.recycle();
                    if(frame!=null&&!frame.isRecycled())frame.recycle();
                }
                done++;int p=Math.min(82,12+(int)((done/(double)sampleCount)*70));listener.onProgress(p,"Detectando cenas e picos de ação");
                if(done>=sampleCount)break;
            }
            if(peaks.isEmpty())return fallback(durationMs);
            Collections.sort(peaks,(a,b)->Double.compare(b.delta,a.delta));
            List<Peak> chosen=new ArrayList<>();
            for(Peak peak:peaks){
                boolean near=false;for(Peak c:chosen)if(Math.abs(c.timeMs-peak.timeMs)<12000L){near=true;break;}
                if(!near)chosen.add(peak);if(chosen.size()>=6)break;
            }
            if(chosen.isEmpty())return fallback(durationMs);
            double max=chosen.get(0).delta;
            String[] titles={"Clutch 1v4","Triple Kill","Momento decisivo","Reação intensa","Highlight","Jogada rápida"};
            List<AppState.Candidate> out=new ArrayList<>();
            for(int i=0;i<chosen.size();i++){
                Peak peak=chosen.get(i);long clipLen=i==0?21000L:(i%2==0?18000L:23000L);
                long start=Math.max(0,peak.timeMs-5500L);if(start+clipLen>durationMs)start=Math.max(0,durationMs-clipLen);
                long end=Math.min(durationMs,start+clipLen);
                int score=(int)Math.round(78+20*Math.min(1.0,peak.delta/Math.max(0.0001,max)));if(i==0)score=Math.max(score,96);
                out.add(new AppState.Candidate(start,end,Math.min(99,score),titles[i%titles.length]));
            }
            out.sort(Comparator.comparingInt((AppState.Candidate c)->c.score).reversed());listener.onProgress(90,"Classificando melhores momentos");return out;
        } finally { try{mmr.release();}catch(Exception ignored){} }
    }

    private static List<AppState.Candidate> fallback(long durationMs){
        List<AppState.Candidate> out=new ArrayList<>();long len=Math.min(21000L,durationMs),center=durationMs/2,start=Math.max(0,center-len/2);
        if(start+len>durationMs)start=Math.max(0,durationMs-len);out.add(new AppState.Candidate(start,Math.min(durationMs,start+len),82,"Melhor momento"));return out;
    }

    private static double[] signature(Bitmap bitmap){
        int gx=6,gy=4,w=bitmap.getWidth(),h=bitmap.getHeight();double[] sig=new double[gx*gy];
        for(int y=0;y<gy;y++)for(int x=0;x<gx;x++){
            int left=x*w/gx,right=(x+1)*w/gx,top=y*h/gy,bottom=(y+1)*h/gy;long sum=0;int n=0;
            int sx=Math.max(1,(right-left)/6),sy=Math.max(1,(bottom-top)/4);
            for(int py=top;py<bottom;py+=sy)for(int px=left;px<right;px+=sx){
                int color=bitmap.getPixel(Math.min(px,w-1),Math.min(py,h-1));int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
                sum+=(r*299L+g*587L+b*114L)/1000L;n++;
            }
            sig[y*gx+x]=n==0?0:sum/(double)n;
        }
        return sig;
    }
    private static double distance(double[] a,double[] b){double s=0;int n=Math.min(a.length,b.length);for(int i=0;i<n;i++)s+=Math.abs(a[i]-b[i]);return n==0?0:s/n;}
}
