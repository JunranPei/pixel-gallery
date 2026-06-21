package com.pixel.gallery

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class PixelGalleryApp : Application() {

    private var activeActivities = 0

    override fun onCreate() {
        super.onCreate()
        
        // [DEBUG] 启动时弹出 Toast 验证 APK 是否为最新编译版本，修复完成后删除
        android.os.Handler(mainLooper).postDelayed({
            android.widget.Toast.makeText(this, "🔧 BUILD_ID: 20260621-V9", android.widget.Toast.LENGTH_LONG).show()
        }, 1500)

        // OSMdroid Configuration
        Configuration.getInstance().userAgentValue = packageName

        // 监听全局前后台切换，动态伸缩 Glide 缓存池
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activeActivities == 0) {
                    try {
                        com.bumptech.glide.Glide.get(applicationContext)
                            .setMemoryCategory(com.bumptech.glide.MemoryCategory.NORMAL)
                    } catch (e: Exception) {}
                }
                activeActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                activeActivities--
                if (activeActivities == 0) {
                    try {
                        com.bumptech.glide.Glide.get(applicationContext)
                            .setMemoryCategory(com.bumptech.glide.MemoryCategory.LOW)
                    } catch (e: Exception) {}
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 仅在系统内存真正处于中度及以上吃紧状态时才彻底清空缓存
        if (level >= TRIM_MEMORY_MODERATE) {
            try {
                // 立刻清除 Glide 所有的内存图片缓存（Bitmap 物理内存占用的大头）
                com.bumptech.glide.Glide.get(this).clearMemory()
                // 建议 JVM 运行 GC 彻底回收被释放的大对象和物理内存
                System.gc()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
