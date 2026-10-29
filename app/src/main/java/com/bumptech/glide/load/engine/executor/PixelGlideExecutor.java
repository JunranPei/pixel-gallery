package com.bumptech.glide.load.engine.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Narrow package-access bridge for the pinned Glide 4.16 executor adapter. */
public final class PixelGlideExecutor {
  private PixelGlideExecutor() {}

  public static GlideExecutor wrap(ExecutorService executor) {
    return new GlideExecutor(executor);
  }

  public static ThreadPoolExecutor pool(int count, String name, boolean disk) {
    AtomicInteger sequence = new AtomicInteger();
    return new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS,
        new PriorityBlockingQueue<Runnable>(), task -> new Thread(() -> {
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND
              + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
          if (disk) android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
              .detectNetwork().penaltyDeath().build());
          try { task.run(); }
          catch (Throwable error) { GlideExecutor.UncaughtThrowableStrategy.DEFAULT.handle(error); }
        }, "glide-" + name + "-thread-" + sequence.getAndIncrement()));
  }
}
