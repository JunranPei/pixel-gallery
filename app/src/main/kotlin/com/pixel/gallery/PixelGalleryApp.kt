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
        
        // OSMdroid Configuration
        Configuration.getInstance().userAgentValue = packageName

        // 监听全局前后台切换，动态伸缩 Glide 缓存池
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activeActivities == 0) {
                    // 应用切回前台：恢复正常的图片缓存池大小，提升滑动流畅度
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
                    // 应用切到后台：将 Glide 缓存池上限直接砍掉 50%（释放历史无用缩略图缓存）
                    // 关键：Glide 绝对不会清理当前正在详情页显示的 active 图片，因此切回时大图绝不发糊、不重载
                    try {
                        com.bumptech.glide.Glide.get(applicationContext)
                            .setMemoryCategory(com.bumptech.glide.MemoryCategory.LOW)
                        // 触发系统级的轻量 GC，把刚才释放的历史缓存内存彻底还给系统，降低后台被杀率
                        System.gc()
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
        // 当应用的 UI 已经完全不可见（切到后台），或者系统处于中度及以上内存吃紧状态时
        if (level == TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_MODERATE) {
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
