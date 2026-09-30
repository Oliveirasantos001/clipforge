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

    public static File cut(Context context, Uri source, long requestedStartMs, long requestedEndMs, File output, ProgressListener progress) throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;
        try{
            extractor.setDataSource(context,source,null);muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaMetadataRetriever mmr=new MediaMetadataRetriever();
            try{mmr.setDataSource(context,source);String rotation=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if(rotation!=null)try{muxer.setOrientationHint(Integer.parseInt(rotation));}catch(NumberFormatException ignored){}}
            finally{try{mmr.release();}catch(Exception ignored){}}
            Map<Integer,Integer> trackMap=new HashMap<>();int maxBuffer=1024*1024;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&(mime.startsWith("video/")||mime.startsWith("audio/"))){
                    extractor.selectTrack(i);trackMap.put(i,muxer.addTrack(format));
                    if(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))maxBuffer=Math.max(maxBuffer,format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            if(trackMap.isEmpty())throw new IllegalStateException("Nenhuma faixa de áudio/vídeo compatível foi encontrada.");
            maxBuffer=Math.min(maxBuffer,16*1024*1024);ByteBuffer buffer=ByteBuffer.allocateDirect(maxBuffer);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long startUs=Math.max(0,requestedStartMs*1000L),endUs=Math.max(startUs+1,requestedEndMs*1000L);
            extractor.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);long baseUs=extractor.getSampleTime();if(baseUs<0)baseUs=startUs;long totalUs=Math.max(1,endUs-baseUs);
            muxer.start();
            while(true){
                long sampleTime=extractor.getSampleTime();if(sampleTime<0||sampleTime>endUs)break;
                int src=extractor.getSampleTrackIndex();Integer dst=trackMap.get(src);if(dst==null){if(!extractor.advance())break;continue;}
                buffer.clear();int size=extractor.readSampleData(buffer,0);if(size<0)break;
                info.offset=0;info.size=size;info.presentationTimeUs=Math.max(0,sampleTime-baseUs);info.flags=extractor.getSampleFlags();muxer.writeSampleData(dst,buffer,info);
                progress.onProgress((int)Math.min(99,Math.max(0,((sampleTime-baseUs)*100L)/totalUs)));if(!extractor.advance())break;
            }
            progress.onProgress(100);return output;
        } catch(Exception e){if(output.exists())output.delete();throw e;}
        finally{try{extractor.release();}catch(Exception ignored){}if(muxer!=null){try{muxer.stop();}catch(Exception ignored){}try{muxer.release();}catch(Exception ignored){}}}
    }
}
