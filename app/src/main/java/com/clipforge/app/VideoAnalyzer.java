package com.clipforge.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VideoAnalyzer {
    public interface ProgressListener { void onProgress(int percent,String stage); }

    private static final class Point {
        final int index;
        final long timeMs;
        double visual;
        double audio;
        double score;
        Point(int index,long timeMs){this.index=index;this.timeMs=timeMs;}
    }

    private VideoAnalyzer(){}

    public static List<AppState.Candidate> analyze(Context context,Uri uri,ProgressListener listener)throws Exception{
        MediaMetadataRetriever mmr=new MediaMetadataRetriever();
        try{
            mmr.setDataSource(context,uri);
            long durationMs=parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if(durationMs<=0)throw new IllegalArgumentException("Não foi possível ler a duração do vídeo.");

            int pointCount=(int)Math.max(24,Math.min(180,Math.ceil(durationMs/2500.0)));
            long stepMs=Math.max(500L,durationMs/pointCount);
            List<Point> points=new ArrayList<>(pointCount);
            for(int i=0;i<pointCount;i++)points.add(new Point(i,Math.min(durationMs-1,i*stepMs)));

            analyzeVisual(mmr,points,listener);
            analyzeAudio(context,uri,durationMs,points,listener);
            listener.onProgress(92,"Classificando os melhores momentos");

            normalize(points);
            return buildCandidates(points,durationMs);
        }finally{
            try{mmr.release();}catch(Exception ignored){}
        }
    }

    private static void analyzeVisual(MediaMetadataRetriever mmr,List<Point> points,ProgressListener listener){
        double[] previous=null;
        for(int i=0;i<points.size();i++){
            Bitmap frame=null;
            try{
                Point point=points.get(i);
                if(Build.VERSION.SDK_INT>=27){
                    frame=mmr.getScaledFrameAtTime(point.timeMs*1000L,MediaMetadataRetriever.OPTION_CLOSEST,128,72);
                }else{
                    Bitmap raw=mmr.getFrameAtTime(point.timeMs*1000L,MediaMetadataRetriever.OPTION_CLOSEST);
                    if(raw!=null){
                        frame=Bitmap.createScaledBitmap(raw,128,72,true);
                        if(frame!=raw&&!raw.isRecycled())raw.recycle();
                    }
                }
                if(frame!=null){
                    double[] sig=signature(frame);
                    if(previous!=null)point.visual=distance(previous,sig);
                    previous=sig;
                }
            }catch(Exception ignored){
                // Um frame ruim não invalida o vídeo inteiro.
            }finally{
                if(frame!=null&&!frame.isRecycled())frame.recycle();
            }
            int p=10+(int)Math.round((i+1)*55.0/Math.max(1,points.size()));
            listener.onProgress(Math.min(65,p),"Analisando movimento e mudanças de cena");
        }
    }

    private static void analyzeAudio(Context context,Uri uri,long durationMs,List<Point> points,ProgressListener listener){
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try{
            extractor.setDataSource(context,uri,null);
            int track=-1;
            MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){
                    track=i;
                    format=f;
                    break;
                }
            }
            if(track<0||format==null){
                listener.onProgress(88,"Vídeo sem faixa de áudio: usando análise visual");
                return;
            }

            extractor.selectTrack(track);
            String mime=format.getString(MediaFormat.KEY_MIME);
            if(mime==null)return;
            codec=MediaCodec.createDecoderByType(mime);
            codec.configure(format,null,null,0);
            codec.start();

            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputDone=false,outputDone=false;
            int pcmEncoding=AudioFormat.ENCODING_PCM_16BIT;
            double[] energySum=new double[points.size()];
            int[] energyCount=new int[points.size()];
            long lastProgressUs=0;

            while(!outputDone){
                if(!inputDone){
                    int inIndex=codec.dequeueInputBuffer(10_000);
                    if(inIndex>=0){
                        ByteBuffer in=codec.getInputBuffer(inIndex);
                        if(in!=null){
                            in.clear();
                            int size=extractor.readSampleData(in,0);
                            long pts=extractor.getSampleTime();
                            if(size<0||pts<0){
                                codec.queueInputBuffer(inIndex,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone=true;
                            }else{
                                codec.queueInputBuffer(inIndex,0,size,pts,extractor.getSampleFlags());
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex=codec.dequeueOutputBuffer(info,10_000);
                if(outIndex==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat out=codec.getOutputFormat();
                    if(out.containsKey(MediaFormat.KEY_PCM_ENCODING))pcmEncoding=out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }else if(outIndex>=0){
                    ByteBuffer out=codec.getOutputBuffer(outIndex);
                    if(out!=null&&info.size>0){
                        out.position(info.offset);
                        out.limit(info.offset+info.size);
                        double energy=pcmEnergy(out,pcmEncoding);
                        int idx=(int)Math.min(points.size()-1,Math.max(0,(info.presentationTimeUs/1000L)*points.size()/Math.max(1L,durationMs)));
                        energySum[idx]+=energy;
                        energyCount[idx]++;
                    }
                    if(info.presentationTimeUs-lastProgressUs>1_000_000L){
                        lastProgressUs=info.presentationTimeUs;
                        int p=65+(int)Math.min(22,Math.max(0,info.presentationTimeUs*22L/Math.max(1L,durationMs*1000L)));
                        listener.onProgress(p,"Analisando atividade sonora");
                    }
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(outIndex,false);
                }
            }

            for(int i=0;i<points.size();i++){
                if(energyCount[i]>0)points.get(i).audio=energySum[i]/energyCount[i];
            }
            listener.onProgress(88,"Combinando imagem e áudio");
        }catch(Exception ignored){
            listener.onProgress(88,"Áudio indisponível: usando análise visual");
        }finally{
            if(codec!=null){
                try{codec.stop();}catch(Exception ignored){}
                try{codec.release();}catch(Exception ignored){}
            }
            try{extractor.release();}catch(Exception ignored){}
        }
    }

    private static double pcmEnergy(ByteBuffer source,int encoding){
        ByteBuffer b=source.slice().order(ByteOrder.LITTLE_ENDIAN);
        if(encoding==AudioFormat.ENCODING_PCM_FLOAT){
            double sum=0;
            int n=0;
            while(b.remaining()>=4){
                float v=b.getFloat();
                if(Float.isFinite(v)){sum+=Math.abs(v);n++;}
            }
            return n==0?0:sum/n;
        }

        double sum=0;
        int n=0;
        int skip=0;
        while(b.remaining()>=2){
            short v=b.getShort();
            if((skip++&1)==0){
                sum+=Math.abs((int)v)/32768.0;
                n++;
            }
        }
        return n==0?0:sum/n;
    }

    private static void normalize(List<Point> points){
        double visualMax=0,audioMax=0;
        for(Point p:points){
            visualMax=Math.max(visualMax,p.visual);
            audioMax=Math.max(audioMax,p.audio);
        }
        visualMax=Math.max(visualMax,0.000001);
        audioMax=Math.max(audioMax,0.000001);

        for(int i=0;i<points.size();i++){
            Point p=points.get(i);
            double v=Math.min(1.0,p.visual/visualMax);
            double a=Math.min(1.0,p.audio/audioMax);
            double neighbor=0;
            int count=0;
            for(int j=Math.max(0,i-1);j<=Math.min(points.size()-1,i+1);j++){
                if(j==i)continue;
                Point n=points.get(j);
                neighbor+=0.65*Math.min(1.0,n.visual/visualMax)+0.35*Math.min(1.0,n.audio/audioMax);
                count++;
            }
            double continuity=count==0?0:neighbor/count;
            p.score=0.58*v+0.32*a+0.10*continuity;
        }
    }

    private static List<AppState.Candidate> buildCandidates(List<Point> points,long durationMs){
        List<Point> ranked=new ArrayList<>(points);
        ranked.sort(Comparator.comparingDouble((Point p)->p.score).reversed());

        List<Point> chosen=new ArrayList<>();
        for(Point p:ranked){
            if(p.timeMs<2000)continue;
            boolean near=false;
            for(Point c:chosen){
                if(Math.abs(c.timeMs-p.timeMs)<12_000L){near=true;break;}
            }
            if(!near)chosen.add(p);
            if(chosen.size()>=8)break;
        }

        if(chosen.isEmpty())return fallback(durationMs);

        double best=Math.max(0.000001,chosen.get(0).score);
        List<AppState.Candidate> out=new ArrayList<>();
        for(int i=0;i<chosen.size();i++){
            Point p=chosen.get(i);
            long clipLen=18_000L+(i%4)*2_000L;
            long lead=Math.min(6_000L,clipLen/3);
            long start=Math.max(0,p.timeMs-lead);
            if(start+clipLen>durationMs)start=Math.max(0,durationMs-clipLen);
            long end=Math.min(durationMs,start+clipLen);
            int score=(int)Math.round(70+28*Math.min(1.0,p.score/best));
            String title=i==0?"Melhor momento":"Highlight "+(i+1);
            out.add(new AppState.Candidate(start,end,Math.max(70,Math.min(98,score)),title));
        }

        out.sort(Comparator.comparingInt((AppState.Candidate c)->c.score).reversed());
        return out;
    }

    private static List<AppState.Candidate> fallback(long durationMs){
        List<AppState.Candidate> out=new ArrayList<>();
        long len=Math.min(20_000L,durationMs);
        long start=Math.max(0,durationMs/2-len/2);
        if(start+len>durationMs)start=Math.max(0,durationMs-len);
        out.add(new AppState.Candidate(start,Math.min(durationMs,start+len),75,"Melhor momento"));
        return out;
    }

    private static double[] signature(Bitmap bitmap){
        int gx=8,gy=5,w=bitmap.getWidth(),h=bitmap.getHeight();
        double[] sig=new double[gx*gy];
        for(int y=0;y<gy;y++){
            for(int x=0;x<gx;x++){
                int left=x*w/gx,right=(x+1)*w/gx,top=y*h/gy,bottom=(y+1)*h/gy;
                long sum=0;
                int n=0;
                int sx=Math.max(1,(right-left)/5),sy=Math.max(1,(bottom-top)/4);
                for(int py=top;py<bottom;py+=sy){
                    for(int px=left;px<right;px+=sx){
                        int color=bitmap.getPixel(Math.min(px,w-1),Math.min(py,h-1));
                        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
                        sum+=(r*299L+g*587L+b*114L)/1000L;
                        n++;
                    }
                }
                sig[y*gx+x]=n==0?0:sum/(double)n;
            }
        }
        return sig;
    }

    private static double distance(double[] a,double[] b){
        double sum=0;
        int n=Math.min(a.length,b.length);
        for(int i=0;i<n;i++)sum+=Math.abs(a[i]-b[i]);
        return n==0?0:sum/n;
    }

    private static long parseLong(String value){
        try{return value==null?0:Long.parseLong(value);}catch(Exception ignored){return 0;}
    }
}
