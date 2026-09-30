package com.clipforge.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class VideoClipper {
    public interface ProgressListener { void onProgress(int percent); }

    private VideoClipper(){}

    public static File cut(Context context,Uri source,long requestedStartMs,long requestedEndMs,File output,ProgressListener progress)throws Exception{
        if(source==null)throw new IllegalArgumentException("Vídeo de origem ausente.");
        if(output==null)throw new IllegalArgumentException("Destino de clip ausente.");
        if(requestedStartMs<0||requestedEndMs<=requestedStartMs)throw new IllegalArgumentException("Intervalo de clip inválido.");
        if(requestedEndMs-requestedStartMs>120_000L)throw new IllegalArgumentException("O clip excede o limite de 2 minutos.");

        long requestedStartUs=requestedStartMs*1000L;
        long requestedEndUs=requestedEndMs*1000L;
        long actualStartUs=findVideoSyncStart(context,source,requestedStartUs);

        MediaExtractor extractor=new MediaExtractor();
        MediaMuxer muxer=null;
        boolean muxerStarted=false;
        boolean wroteVideo=false;
        int samplesWritten=0;

        try{
            extractor.setDataSource(context,source,null);
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaMetadataRetriever mmr=new MediaMetadataRetriever();
            try{
                mmr.setDataSource(context,source);
                String rotation=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if(rotation!=null){
                    try{muxer.setOrientationHint(Integer.parseInt(rotation));}catch(NumberFormatException ignored){}
                }
            }finally{
                try{mmr.release();}catch(Exception ignored){}
            }

            Map<Integer,Integer> trackMap=new HashMap<>();
            Map<Integer,Boolean> videoTracks=new HashMap<>();
            int maxBuffer=4*1024*1024;

            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);
                String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime==null)continue;
                boolean isVideo=mime.startsWith("video/");
                boolean isAudio=mime.startsWith("audio/");
                if(!isVideo&&!isAudio)continue;

                extractor.selectTrack(i);
                int dst=muxer.addTrack(format);
                trackMap.put(i,dst);
                videoTracks.put(i,isVideo);

                if(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)){
                    int value=format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    if(value>0)maxBuffer=Math.max(maxBuffer,value);
                }
            }

            if(trackMap.isEmpty())throw new IllegalStateException("Nenhuma faixa compatível foi encontrada.");
            maxBuffer=Math.min(maxBuffer,32*1024*1024);

            ByteBuffer buffer=ByteBuffer.allocateDirect(maxBuffer);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();

            extractor.seekTo(actualStartUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            muxer.start();
            muxerStarted=true;

            long totalUs=Math.max(1,requestedEndUs-actualStartUs);

            while(true){
                long sampleTime=extractor.getSampleTime();
                if(sampleTime<0||sampleTime>requestedEndUs)break;

                int srcTrack=extractor.getSampleTrackIndex();
                Integer dstTrack=trackMap.get(srcTrack);
                if(dstTrack==null){
                    if(!extractor.advance())break;
                    continue;
                }

                if(sampleTime<actualStartUs){
                    if(!extractor.advance())break;
                    continue;
                }

                buffer.clear();
                int size=extractor.readSampleData(buffer,0);
                if(size<0)break;
                if(size>buffer.capacity())throw new IllegalStateException("Amostra de vídeo maior que o limite seguro.");

                info.offset=0;
                info.size=size;
                info.presentationTimeUs=Math.max(0,sampleTime-actualStartUs);
                info.flags=extractor.getSampleFlags();

                muxer.writeSampleData(dstTrack,buffer,info);
                samplesWritten++;
                if(Boolean.TRUE.equals(videoTracks.get(srcTrack)))wroteVideo=true;

                if(progress!=null){
                    long elapsed=Math.max(0,sampleTime-actualStartUs);
                    progress.onProgress((int)Math.min(99,elapsed*100L/totalUs));
                }

                if(!extractor.advance())break;
            }

            if(!wroteVideo||samplesWritten==0)throw new IllegalStateException("Nenhum quadro de vídeo foi gravado no clip.");
            if(progress!=null)progress.onProgress(100);

        }catch(Exception e){
            if(output.exists())output.delete();
            throw e;
        }finally{
            try{extractor.release();}catch(Exception ignored){}
            if(muxer!=null){
                if(muxerStarted){
                    try{muxer.stop();}catch(Exception ignored){}
                }
                try{muxer.release();}catch(Exception ignored){}
            }
        }

        if(!output.isFile()||output.length()<1024){
            if(output.exists())output.delete();
            throw new IllegalStateException("O arquivo de clip gerado ficou inválido.");
        }
        validateOutput(output);
        return output;
    }

    private static void validateOutput(File output)throws Exception{
        MediaExtractor check=new MediaExtractor();
        MediaMetadataRetriever mmr=new MediaMetadataRetriever();
        try{
            check.setDataSource(output.getAbsolutePath());
            boolean hasVideo=false;
            for(int i=0;i<check.getTrackCount();i++){
                MediaFormat f=check.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")){
                    hasVideo=true;
                    break;
                }
            }
            if(!hasVideo)throw new IllegalStateException("O clip final não contém uma faixa de vídeo válida.");

            mmr.setDataSource(output.getAbsolutePath());
            String duration=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs=0L;
            try{if(duration!=null)durationMs=Long.parseLong(duration);}catch(Exception ignored){}
            if(durationMs<=0)throw new IllegalStateException("A duração do clip final ficou inválida.");
        }catch(Exception e){
            if(output.exists())output.delete();
            throw e;
        }finally{
            try{check.release();}catch(Exception ignored){}
            try{mmr.release();}catch(Exception ignored){}
        }
    }

    private static long findVideoSyncStart(Context context,Uri source,long requestedStartUs)throws Exception{
        MediaExtractor probe=new MediaExtractor();
        try{
            probe.setDataSource(context,source,null);
            int videoTrack=-1;
            for(int i=0;i<probe.getTrackCount();i++){
                MediaFormat f=probe.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")){
                    videoTrack=i;
                    break;
                }
            }
            if(videoTrack<0)throw new IllegalStateException("O arquivo não contém faixa de vídeo.");
            probe.selectTrack(videoTrack);
            probe.seekTo(Math.max(0,requestedStartUs),MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long sync=probe.getSampleTime();
            return sync>=0?sync:Math.max(0,requestedStartUs);
        }finally{
            try{probe.release();}catch(Exception ignored){}
        }
    }
}
