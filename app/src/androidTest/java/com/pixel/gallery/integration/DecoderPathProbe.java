package com.pixel.gallery.integration;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.FutureTarget;
import com.pixel.gallery.glide.NativeJpegFit;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compare real uncached Glide decoder paths under one foreground UID and viewport. */
final class DecoderPathProbe {
    static String run(Instrumentation instrumentation, String ids) throws Exception {
        if(ids==null)throw new IllegalArgumentException("Explicit source IDs required");
        Context context=instrumentation.getTargetContext();
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=instrumentation.startActivitySync(launch);
        instrumentation.runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        int saved=Process.getThreadPriority(Process.myTid());
        JSONArray rows=new JSONArray();
        try {
            SystemClock.sleep(4000);
            Class<?> type=Class.forName("io.github.indexedjpeg.DcJpegPreview",true,context.getClassLoader());
            Object decoder=type.getField("INSTANCE").get(null);
            Method original=type.getMethod("decodeFit",android.content.ContentResolver.class,Uri.class,int.class,int.class,AtomicBoolean.class);
            Process.setThreadPriority(9);
            for(int round=0;round<3;round++)for(String id:ids.split(","))for(int order=0;order<3;order++) {
                int mode=(round+order)%3;
                String name=mode==0?"original-native-plus-scale":mode==1?"glide-exact-native":"glide-standard";
                Uri uri=Uri.parse("content://media/external/images/media/"+id);
                // Keep all paths' pools empty and exclude trim calls from timed work.
                instrumentation.runOnMainSync(() -> Glide.get(context).clearMemory());
                long before=Debug.getNativeHeapAllocatedSize();
                AtomicLong peak=new AtomicLong(before);AtomicBoolean sampling=new AtomicBoolean(true);
                Thread sampler=new Thread(() -> {while(sampling.get()) {peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),Math::max);SystemClock.sleep(1);}},"decoder-path-memory");
                sampler.start();
                FutureTarget<Bitmap> target=null;Bitmap coarse=null,output=null;
                long start=SystemClock.elapsedRealtimeNanos();
                try {
                    if(mode==0) {
                        coarse=(Bitmap)original.invoke(decoder,context.getContentResolver(),uri,1272,2772,new AtomicBoolean());
                        if(coarse==null)throw new AssertionError("Original native fallback: "+id);
                        float fit=Math.min(1f,Math.min(1272f/coarse.getWidth(),2772f/coarse.getHeight()));
                        output=Bitmap.createScaledBitmap(coarse,Math.round(coarse.getWidth()*fit),Math.round(coarse.getHeight()*fit),true);
                    } else {
                        Object model=mode==1?new NativeJpegFit(uri,0,0):uri;
                        target=Glide.with(context).asBitmap().load(model)
                            .format(DecodeFormat.PREFER_ARGB_8888).downsample(DownsampleStrategy.FIT_CENTER)
                            .fitCenter().diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                            .priority(Priority.IMMEDIATE).submit(1272,2772);
                        output=target.get();
                    }
                    long elapsed=SystemClock.elapsedRealtimeNanos()-start;
                    peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),Math::max);
                    JSONObject row=new JSONObject().put("id",id).put("round",round).put("mode",name)
                        .put("startBootNs",start).put("durationMs",elapsed/1e6)
                        .put("width",output.getWidth()).put("height",output.getHeight())
                        .put("config",String.valueOf(output.getConfig()))
                        .put("outputBytes",output.getAllocationByteCount())
                        .put("sampledNativeHeapIncreaseBytes",peak.get()-before);
                    rows.put(row);Log.i("DecoderPathProbe",row.toString());
                } finally {
                    sampling.set(false);sampler.join();
                    if(target!=null)Glide.with(context).clear(target);
                    else {
                        if(output!=null&&output!=coarse)output.recycle();
                        if(coarse!=null)coarse.recycle();
                    }
                }
                SystemClock.sleep(150);
            }
            File out=new File(context.getExternalFilesDir(null),"decoder-path-probe.json");
            try(java.io.FileWriter writer=new java.io.FileWriter(out)){writer.write(rows.toString(2));}
            return "Decoder paths completed: "+rows.length()+"\n"+out.getAbsolutePath();
        } finally {
            Process.setThreadPriority(saved);
            instrumentation.runOnMainSync(() -> activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
    }
}
