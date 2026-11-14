package com.pixel.gallery.integration;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

final class ExactFitChecks {
    static String run(Context context) throws Exception {
        Class<?> type=Class.forName("io.github.indexedjpeg.DcJpegPreview",true,context.getClassLoader());
        Object decoder=type.getField("INSTANCE").get(null);
        Class<?>[] params={android.content.ContentResolver.class,Uri.class,int.class,int.class,AtomicBoolean.class};
        Method original=type.getMethod("decodeFit",params),exact=type.getMethod("decodeFitExact",params);
        JSONArray reports=new JSONArray();
        int[][] sources={{1001,775},{17,19},{1,1},{2,3},{33,65}};
        int[][] targets={{321,333},{1,1},{1,200},{200,1},{1272,2772}};
        for(int[] size:sources) {
            File file=new File(context.getFilesDir(),"exact-fit-check-"+UUID.randomUUID()+".jpg");
            Bitmap pixels=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
            int[] input=new int[size[0]*size[1]];
            for(int y=0;y<size[1];y++) for(int x=0;x<size[0];x++)
                input[y*size[0]+x]=0xff000000|(((x*13+y*7)&255)<<16)|(((x*5+y*3)&255)<<8)|((x*3+y*11)&255);
            pixels.setPixels(input,0,size[0],0,0,size[0],size[1]);
            try(FileOutputStream out=new FileOutputStream(file)){if(!pixels.compress(Bitmap.CompressFormat.JPEG,95,out))throw new AssertionError("JPEG write");}
            pixels.recycle();
            try {
                for(int[] target:targets) {
                    Bitmap coarse=(Bitmap)original.invoke(decoder,context.getContentResolver(),Uri.fromFile(file),target[0],target[1],new AtomicBoolean());
                    Bitmap actual=(Bitmap)exact.invoke(decoder,context.getContentResolver(),Uri.fromFile(file),target[0],target[1],new AtomicBoolean());
                    if(coarse==null||actual==null)throw new AssertionError("Unexpected fallback");
                    float factor=Math.min(1f,Math.min((float)target[0]/coarse.getWidth(),(float)target[1]/coarse.getHeight()));
                    int w=Math.max(1,Math.round(coarse.getWidth()*factor)),h=Math.max(1,Math.round(coarse.getHeight()*factor));
                    Bitmap reference=Bitmap.createScaledBitmap(coarse,w,h,true);
                    if(actual.getWidth()!=w||actual.getHeight()!=h||actual.getAllocationByteCount()>reference.getAllocationByteCount())
                        throw new AssertionError("Size or allocation grew");
                    int[] a=new int[w*h],b=new int[w*h];actual.getPixels(a,0,w,0,0,w,h);reference.getPixels(b,0,w,0,0,w,h);
                    long error=0;int max=0;
                    for(int i=0;i<a.length;i++) {
                        if((a[i]>>>24)!=255)throw new AssertionError("Alpha changed");
                        for(int shift:new int[]{0,8,16}) {int d=Math.abs(((a[i]>>>shift)&255)-((b[i]>>>shift)&255));error+=d;max=Math.max(max,d);}
                    }
                    double mean=error/(3.0*a.length);
                    // Tiny outputs also compare the legacy DC shortcut with libjpeg scanlines.
                    // Their integer colour rounding can differ by a few levels in one pixel.
                    if(mean>(a.length<=4?4:2)||max>4)throw new AssertionError("Pixel mismatch source="+
                        size[0]+"x"+size[1]+" target="+target[0]+"x"+target[1]+" mean="+mean+" max="+max);
                    reports.put(new JSONObject().put("source",size[0]+"x"+size[1]).put("target",target[0]+"x"+target[1])
                        .put("actual",w+"x"+h).put("bytes",actual.getAllocationByteCount()).put("meanError",mean).put("maxError",max));
                    if(reference!=coarse)reference.recycle();coarse.recycle();actual.recycle();
                }
                if(exact.invoke(decoder,context.getContentResolver(),Uri.fromFile(file),321,333,new AtomicBoolean(true))!=null)
                    throw new AssertionError("Cancelled request decoded");
            } finally {file.delete();}
        }
        File report=new File(context.getExternalFilesDir(null),"exact-fit-checks.json");
        try(java.io.FileWriter out=new java.io.FileWriter(report)){out.write(reports.toString(2));}
        JSONArray bench=new JSONArray();
        for(int round=0;round<3;round++) for(String id:new String[]{"836065","836063","836062"}) {
            for(int order=0;order<2;order++) {
                boolean fused=((round+order)&1)==1;
                long before=android.os.Debug.getNativeHeapAllocatedSize();
                java.util.concurrent.atomic.AtomicLong peak=new java.util.concurrent.atomic.AtomicLong(before);
                AtomicBoolean sampling=new AtomicBoolean(true);
                Thread sampler=new Thread(() -> {
                    while(sampling.get()) {
                        peak.accumulateAndGet(android.os.Debug.getNativeHeapAllocatedSize(),Math::max);
                        android.os.SystemClock.sleep(1);
                    }
                },"exact-fit-memory-sampler");
                sampler.start();
                Bitmap coarse=null,output=null;
                long started=android.os.SystemClock.elapsedRealtimeNanos();
                try {
                    coarse=(Bitmap)(fused?exact:original).invoke(decoder,context.getContentResolver(),
                        Uri.parse("content://media/external/images/media/"+id),1272,2772,new AtomicBoolean());
                    if(coarse==null)throw new AssertionError("Real JPEG fallback");
                    if(fused)output=coarse;
                    else {
                        float scale=Math.min(1272f/coarse.getWidth(),2772f/coarse.getHeight());
                        output=Bitmap.createScaledBitmap(coarse,Math.round(coarse.getWidth()*scale),Math.round(coarse.getHeight()*scale),true);
                    }
                    long elapsed=android.os.SystemClock.elapsedRealtimeNanos()-started;
                    peak.accumulateAndGet(android.os.Debug.getNativeHeapAllocatedSize(),Math::max);
                    if(output.getWidth()!=1272||output.getHeight()!=1696||output.getAllocationByteCount()!=8629248)
                        throw new AssertionError("Real output dimensions changed");
                    bench.put(new JSONObject().put("id",id).put("round",round).put("fused",fused)
                        .put("durationMs",elapsed/1e6).put("outputBytes",output.getAllocationByteCount())
                        .put("sampledNativeHeapIncreaseBytes",peak.get()-before));
                } finally {
                    sampling.set(false);sampler.join();
                    if(output!=null&&output!=coarse)output.recycle();if(coarse!=null)coarse.recycle();
                }
                android.os.SystemClock.sleep(100);
            }
        }
        File benchFile=new File(context.getExternalFilesDir(null),"exact-fit-benchmark.json");
        try(java.io.FileWriter out=new java.io.FileWriter(benchFile)){out.write(bench.toString(2));}
        return "Exact-fit checks passed: "+reports.length()+" cases; cancellation checked.\n"+reports;
    }
}
