package com.pixel.gallery.integration
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
internal class AlbumOrderUiChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.cacheDir, "album-order-qa").apply { mkdirs() }
    private var down = 0L
    private var heldCount = 0
    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        val original = settings.albumGrouping.first()
        val originalSort = settings.albumSortOrder.first()
        val enabled = settings.groupingEnabled.first()
        val token = UUID.randomUUID().toString().take(6)
        val names = (0..5).map { "AAOrder_${token}_$it" }
        val paths = names.map { "/storage/emulated/0/Pictures/$it" }
        val gid = "order-ui-$token"
        val groupName = "AAOrderGroup_$token"
        val owned = names.map { name ->
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,"order.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/$name/")
                put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff668855.toInt()) }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }; bitmap.recycle()
            context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            uri
        }
        var activity: MainActivity? = null
        val results = mutableListOf<String>()
        try {
            settings.setGroupingEnabled(true)
            settings.setAlbumSortOrder("CUSTOM")
            settings.updateAlbumGrouping { state ->
                state.copy(groups=listOf(AlbumGroup(gid,groupName,paths.take(3)))+state.groups, collapsedIds=state.collapsedIds + state.groups.map { it.id })
                    .withOrder("groups:albums") { it.copy(pinned=listOf("group:$gid")+it.pinned) }
                    .withOrder(gid) { AlbumOrderState(rule=AlbumSortOrder.CUSTOM,customStarted=true,custom=paths.take(3).map { "album:$it" }) }
                    .withOrder("albums") { it.copy(customStarted=true,custom=paths.drop(3).map { "album:$it" }+it.custom) }
            }
            val screen=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=screen
            instrumentation.runOnMainSync {
                val photos=ViewModelProvider(screen)[PhotosViewModel::class.java]
                screen.setContent { PixelGalleryTheme(dynamicColor=false,darkTheme=false) { MainScaffold(initialHomeTab=1,photosViewModel=photos) } }
                photos.refresh()
            }
            settle(1500)
            if(find("Background support")!=null)click("Cancel")
            await(names[0]);await(names[1])
            longPress(names[0]);await("1 selected")
            val before=settings.albumGrouping.first().orderState(gid).custom
            drag(names[0],names[1])
            val after=settings.albumGrouping.first().orderState(gid).custom
            check(after.indexOf("album:${paths[0]}")>after.indexOf("album:${paths[1]}")) { "Group custom drag failed: $before -> $after" }
            results += "group custom direct drag and persisted order PASS"
            click("Pin");await("Unpin");click("Clear selection")
            longPress(names[1]);click("Pin");click("Clear selection")
            longPress(names[0]);drag(names[0],names[1])
            check(settings.albumGrouping.first().orderState(gid).pinned.take(2)==listOf("album:${paths[1]}","album:${paths[0]}")) { "group pin drag failed" }
            check(find("Pinned")==null) { "thumb pin marker remains" }
            snapshot("group-pin-reordered");results += "group pin drag and no thumb badge PASS"
            click("Clear selection")
            longPress(groupName)
            check(find("Sort group albums")==null) { "Sort remains in top bar" }
            click("More");click("Sort group albums");await("Sort Group Albums By")
            snapshot("group-sort-menu")
            click("Clear selection");settle(300)
            check(find("Sort Group Albums By")==null) { "Group dialog outlives selection" }
            results += "overflow sort and deselection dismiss PASS"
            // Collapse only fixture group; keep existing group contents offscreen while locating fixture albums.
            click(groupName)
            repeat(12) { if(find(names[3])==null) swipeUp() }
            await(names[3]);await(names[4]);longPress(names[3]);drag(names[3],names[4])
            val custom=settings.albumGrouping.first().orderState("albums").custom
            check(custom.indexOf("album:${paths[3]}")>custom.indexOf("album:${paths[4]}")) { "Ungrouped custom drag failed" }
            results += "ungrouped custom drag PASS"
            click("Pin");click("Clear selection")
            // Newly pinned item moves before the ordinary queue; pin the other fixture via its visible label.
            longPress(names[4]);click("Pin");click("Clear selection")
            repeat(12) { if(find(names[3])==null) swipeDown() }
            await(names[3]);await(names[4]);longPress(names[3]);drag(names[3],names[4])
            val pins=settings.albumGrouping.first().orderState("albums").pinned
            check(pins.indexOf("album:${paths[3]}")>pins.indexOf("album:${paths[4]}"))
            results += "ungrouped pin drag PASS"
            val retained=settings.albumGrouping.first()
            settings.setAlbumSortOrder("NAME_DESC");settle(400);settings.setAlbumSortOrder("CUSTOM");settle(400)
            check(settings.albumGrouping.first()==retained)
            results += "sort toggle preserves custom and pins PASS"
            snapshot("finished")
            File(output,"result.txt").writeText(results.joinToString("\n"))
            results.joinToString("; ")
        } finally {
            File(output,"partial.txt").writeText(results.joinToString("\n"))
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            settle(300)
            owned.forEach { context.contentResolver.delete(it,null,null) }
            settings.updateAlbumGrouping { original }
            settings.setAlbumSortOrder(originalSort);settings.setGroupingEnabled(enabled)
        }
    }
    private fun drag(from:String,to:String) {
        val a=bounds(from);val b=bounds(to)
        event(MotionEvent.ACTION_DOWN,a.centerX().toFloat(),a.centerY().toFloat())
        repeat(24) { index ->
            val f=(index+1)/24f
            event(MotionEvent.ACTION_MOVE,a.centerX()+(b.centerX()-a.centerX())*f,a.centerY()+(b.centerY()-a.centerY())*f)
            SystemClock.sleep(20)
        }
        event(MotionEvent.ACTION_UP,b.centerX().toFloat(),b.centerY().toFloat());settle(600)
    }
    private fun swipeUp() { swipe(1500f,650f) }
    private fun swipeDown() { swipe(650f,1500f) }
    private fun swipe(start:Float,end:Float) {
        event(MotionEvent.ACTION_DOWN,1000f,start)
        repeat(20) { event(MotionEvent.ACTION_MOVE,1000f,start+(end-start)*(it+1)/20);SystemClock.sleep(15) }
        event(MotionEvent.ACTION_UP,1000f,end);settle(450)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(name: String) = nodes().firstOrNull { it.text?.toString() == name || it.contentDescription?.toString() == name }
    private fun bounds(name: String): Rect {
        await(name)
        return Rect().also(find(name)!!::getBoundsInScreen)
    }
    private fun await(name: String) {
        repeat(60) { if (find(name) != null) return; settle(150) }
        snapshot("failed"); error("Missing $name: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun click(name: String) = press(name, false)
    private fun longPress(name: String) = press(name, true)
    private fun press(name: String, long: Boolean) {
        await(name)
        var node = find(name)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        val rect = Rect().also(node::getBoundsInScreen)
        event(MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
        if (long) { SystemClock.sleep(700); snapshot("held-${++heldCount}-${name.take(24)}") }
        event(MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat())
        settle(450)
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(400) }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
