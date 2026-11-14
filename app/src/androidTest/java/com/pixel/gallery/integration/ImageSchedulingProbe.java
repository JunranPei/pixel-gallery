package com.pixel.gallery.integration;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only source decoding under the diagnostic application's real UID. */
public final class ImageSchedulingProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        int saved=Process.getThreadPriority(Process.myTid());
        PerformanceHintManager.Session session=null;
        try {
            if (!getTargetContext().getPackageName().equals("com.pixel.gallery.loadtrace.nocache"))
                throw new IllegalStateException("Diagnostic package required");
            if (arguments!=null && "decoder-ab".equals(arguments.getString("suite"))) {
                result.putString("stream",DecoderPathProbe.run(this,arguments.getString("ids")));
                finish(Activity.RESULT_OK,result);return;
            }
            if (arguments!=null && "exact-fit".equals(arguments.getString("suite"))) {
                result.putString("stream",ExactFitChecks.run(getTargetContext()));
                finish(Activity.RESULT_OK,result);return;
            }
            if (arguments==null || arguments.getString("ids")==null)
                throw new IllegalArgumentException("Explicit authorized MediaStore ids required");
            Intent launch=getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(launch);
            runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            SystemClock.sleep(3000);
            Class<?> decoder=Class.forName("io.github.indexedjpeg.DcJpegPreview",true,getTargetContext().getClassLoader());
            Object instance=decoder.getField("INSTANCE").get(null);
            Method decode=decoder.getMethod("decodeFit",android.content.ContentResolver.class,Uri.class,int.class,int.class,AtomicBoolean.class);
            PerformanceHintManager manager=getTargetContext().getSystemService(PerformanceHintManager.class);
            JSONArray rows=new JSONArray();
            for (int round=0;round<4;round++) {
                for (String id:arguments.getString("ids").split(",")) {
                    // Rotate order to distribute first-read and thermal effects.
                    for (int index=0;index<4;index++) {
                        int mode=(index+round)%4;
                        int priority=mode==0?9:mode==2?-2:0;
                        Process.setThreadPriority(priority);
                        if (mode==3 && manager!=null)
                            session=manager.createHintSession(new int[]{Process.myTid()},100_000_000L);
                        long cpu=Debug.threadCpuTimeNanos(),clock=SystemClock.elapsedRealtimeNanos();
                        Bitmap bitmap=(Bitmap)decode.invoke(instance,getTargetContext().getContentResolver(),
                            Uri.parse("content://media/external/images/media/"+id),1272,2772,new AtomicBoolean(false));
                        long elapsed=SystemClock.elapsedRealtimeNanos()-clock,used=Debug.threadCpuTimeNanos()-cpu;
                        if (bitmap==null)throw new IllegalStateException("Native path unsupported for "+id);
                        JSONObject row=new JSONObject().put("round",round).put("id",id).put("mode",mode)
                            .put("tid",Process.myTid()).put("startBootNs",clock).put("endBootNs",clock+elapsed)
                            .put("priority",Process.getThreadPriority(Process.myTid()))
                            .put("hintSession",session!=null).put("wallMs",elapsed/1e6).put("cpuMs",used/1e6)
                            .put("width",bitmap.getWidth()).put("height",bitmap.getHeight());
                        bitmap.recycle();
                        if (session!=null) {session.reportActualWorkDuration(elapsed);session.close();session=null;}
                        Process.setThreadPriority(saved);
                        rows.put(row);Log.i("ImageSchedulingProbe",row.toString());
                        SystemClock.sleep(350);
                    }
                }
            }
            File out=new File(getTargetContext().getExternalFilesDir(null),"image-scheduling-probe.json");
            try(java.io.FileWriter writer=new java.io.FileWriter(out)){writer.write(rows.toString(2));}
            result.putString("stream",out.getAbsolutePath()+"\n"+rows.toString());
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream",Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);
        } finally {
            if(session!=null)session.close();
            Process.setThreadPriority(saved);
        }
    }
}
