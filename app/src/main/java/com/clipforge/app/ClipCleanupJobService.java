package com.clipforge.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClipCleanupJobService extends JobService {
    public static final long TTL_MS=30L*60L*1000L;
    private static final int JOB_ID=0x43464D;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

    public static File clipsDir(Context context){
        File d=new File(context.getNoBackupFilesDir(),"clips");
        if(!d.exists())d.mkdirs();
        return d;
    }

    public static int cleanupNow(Context context){
        long cutoff=System.currentTimeMillis()-TTL_MS;
        int removed=cleanupDir(clipsDir(context),cutoff);
        File legacy=new File(context.getFilesDir(),"clips");
        if(!legacy.equals(clipsDir(context)))removed+=cleanupDir(legacy,cutoff);
        return removed;
    }

    private static int cleanupDir(File dir,long cutoff){
        File[] files=dir.listFiles();
        if(files==null)return 0;
        int removed=0;
        for(File f:files){
            if(f.isFile()&&f.lastModified()<=cutoff&&f.delete())removed++;
        }
        return removed;
    }

    public static void scheduleNext(Context context){
        File[] files=clipsDir(context).listFiles();
        if(files==null||files.length==0){
            JobScheduler js=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(js!=null)js.cancel(JOB_ID);
            return;
        }

        long now=System.currentTimeMillis();
        long earliest=Long.MAX_VALUE;
        for(File f:files){
            if(f.isFile())earliest=Math.min(earliest,f.lastModified()+TTL_MS);
        }
        if(earliest==Long.MAX_VALUE)return;

        long delay=Math.max(0,earliest-now);
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(context,ClipCleanupJobService.class))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay+60_000L)
            .setPersisted(true)
            .build();
        JobScheduler js=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js!=null)js.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params){
        worker.execute(()->{
            cleanupNow(this);
            scheduleNext(this);
            jobFinished(params,false);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params){return true;}

    @Override public void onDestroy(){
        worker.shutdownNow();
        super.onDestroy();
    }
}
