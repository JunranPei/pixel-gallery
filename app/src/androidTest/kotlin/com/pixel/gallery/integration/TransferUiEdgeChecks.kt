package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.model.*
import com.pixel.gallery.services.MetadataService
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.transfer.TransferDestinationScreen
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.QuickSortViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Production destination screen, ViewModels and repository; isolated database and owned real media. */
internal class TransferUiEdgeChecks(private val instrumentation: Instrumentation) {
    private val context=instrumentation.targetContext
    private val output=File(context.cacheDir,"transfer-ui-edge-qa").apply { mkdirs() }
    fun exercise():String=runBlocking {
        val token=UUID.randomUUID().toString()
        val relative="Pictures/TransferUi_$token/"
        val targetName="Transfer UI target ${token.take(8)}"
        val isolated=object:ContextWrapper(context) {
            override fun getFilesDir()=File(output,"$token/files").apply { mkdirs() }
            override fun getCacheDir()=File(output,"$token/cache").apply { mkdirs() }
            override fun getApplicationContext():Context=this
        }
        val db=Room.inMemoryDatabaseBuilder(isolated,GalleryDatabase::class.java).addCallback(LABEL_DATABASE_CALLBACK).build()
        val settings=SettingsRepository(isolated);val repo=MediaRepository(isolated,db.mediaDao(),settings)
        val owned=mutableListOf<Uri>();val resolver=context.contentResolver
        suspend fun fixture(folder:String,name:String,color:Int):MediaEntry {
            val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.RELATIVE_PATH,relative+folder+"/")
                put(MediaStore.MediaColumns.MIME_TYPE,"image/png");put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!;owned+=uri
            resolver.openOutputStream(uri)!!.use { out -> Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(color);check(compress(Bitmap.CompressFormat.PNG,100,out));recycle() } }
            resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            repo.syncWhenMediaMutationsIdle(force=true)
            return db.mediaDao().getEntriesByIds(listOf(uri.lastPathSegment!!.toLong())).single()
        }
        fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        val source=fixture("Source","same.png",0xff336699.toInt())
        val old=fixture(targetName,"same.png",0xffbb2244.toInt())
        val missing=fixture("Source","missing.png",0xff887733.toInt())
        val partial=fixture("Source","partial.png",0xff994422.toInt())
        val sourceHash=hash(File(source.path));val oldHash=hash(File(old.path));val targetDir=File(old.path).parentFile!!
        val label=LabelRepository(db,db.labelDao()).createLabel("UI Move")
        db.labelDao().addMemberships(listOf(com.pixel.gallery.data.local.entity.MediaLabelEntry(label,source.contentId)))
        val entries=mutableStateOf(listOf(source));val shown=mutableStateOf(true);val epoch=mutableIntStateOf(0)
        val finished=mutableListOf<TransferSummary>();val store=ViewModelStore()
        lateinit var vm:PhotosViewModel;lateinit var qs:QuickSortViewModel
        instrumentation.runOnMainSync {
            vm=PhotosViewModel(repo,settings,MetadataService(isolated));qs=QuickSortViewModel(QuickSortQueueRepository(isolated,repo))
            store.put("photos",vm);store.put("quick",qs);vm.setResumed(true)
        }
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { PixelGalleryTheme(darkTheme=false,dynamicColor=false) {
                Surface(Modifier.fillMaxSize()) {
                    if(shown.value) key(epoch.intValue) { TransferDestinationScreen(entries.value,
                        onBack={shown.value=false},onFinished={finished+=it;shown.value=false;vm.clearTransferState()},viewModel=vm,quickSortViewModel=qs) }
                    else Text("Transfer test complete")
                }
            } } }
            fun selectTarget() {
                await("Search albums")
                val edit=nodes().first { it.className?.toString()=="android.widget.EditText" }
                check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,targetName) }))
                click(targetName)
            }
            fun reopen(list:List<MediaEntry>) { instrumentation.runOnMainSync { vm.clearTransferState();entries.value=list;epoch.intValue++;shown.value=true };selectTarget() }
            selectTarget();click("Copy");await("Copy 1 items?");snapshot("copy-confirmation");click("Cancel")
            check(targetDir.listFiles()!!.size==1 && hash(File(source.path))==sourceHash)
            click("Copy");click("Copy");await("Items with the same name already exist");snapshot("copy-conflict")
            click("Continue");await("Transfer test complete")
            check(finished.last().succeeded==1 && finished.last().mode==TransferMode.COPY)
            check(hash(File(targetDir,"same (1).png"))==sourceHash && hash(File(source.path))==sourceHash && hash(File(old.path))==oldHash)
            reopen(listOf(source));click("Move");click("Move");await("Items with the same name already exist");click("Skip");click("Continue")
            await("Transfer test complete");check(finished.last().skipped==1 && File(source.path).exists())
            reopen(listOf(source));click("Move");click("Move");await("Items with the same name already exist");click("Continue")
            await("Transfer test complete");check(finished.last().succeeded==1 && !File(source.path).exists())
            check(hash(File(targetDir,"same (2).png"))==sourceHash && hash(File(old.path))==oldHash)
            val moved=db.mediaDao().getAllEntriesPaged().single { it.path==File(targetDir,"same (2).png").path }
            check(db.labelDao().getMemberships().any { it.contentId==moved.contentId && it.labelId==label })
            reopen(listOf(missing));resolver.delete(Uri.parse(missing.uri),null,null)
            click("Copy");click("Copy");await("Copy incomplete");snapshot("missing-source-failure")
            check(shown.value && finished.size==3 && targetDir.listFiles()!!.size==3)
            click("Got it");await("Select album")
            instrumentation.runOnMainSync { check(vm.transferUiState.value.summary==null && vm.transferUiState.value.error==null) }
            reopen(listOf(missing,partial));click("Copy");click("Copy");await("Copy incomplete")
            check(shown.value && finished.size==3)
            check(vm.transferUiState.value.summary?.let { it.succeeded==1 && it.failed==1 }==true)
            check(hash(File(targetDir,"partial.png"))==hash(File(partial.path)));snapshot("partial-copy-result")
            click("Got it");await("Transfer test complete");check(finished.size==4 && finished.last().succeeded==1 && finished.last().failed==1)
            return@runBlocking "PASS production TransferDestinationScreen + ViewModels + repository: cancel leaves files unchanged; COPY Keep both preserves source and old target; MOVE Skip leaves source; MOVE Keep both removes only source and preserves label; missing source shows failure and stays on destination page after acknowledgment; actual partial copy waits for acknowledgment with one success and one failure. Isolated database; real file SHA-256 checked. Screenshots: $output"
        } finally {
            instrumentation.runOnMainSync { store.clear();activity.finish() }
            owned.forEach { runCatching { resolver.delete(it,null,null) } }
            val prefix=File(android.os.Environment.getExternalStorageDirectory(),relative).canonicalPath+"/"
            resolver.query(MediaStore.Files.getContentUri("external"),arrayOf(MediaStore.MediaColumns._ID),MediaStore.MediaColumns.DATA+" LIKE ?",arrayOf(prefix+"%"),null)?.use { c ->
                val ids=buildList { while(c.moveToNext())add(c.getLong(0)) };ids.forEach { resolver.delete(Uri.withAppendedPath(MediaStore.Files.getContentUri("external"),it.toString()),null,null) }
            }
        }
    }
    private fun nodes():List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache();val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo?) { if(node!=null) { result+=node;for(i in 0 until node.childCount)visit(node.getChild(i)) } }
        visit(instrumentation.uiAutomation.rootInActiveWindow);return result
    }
    private fun find(text:String)=nodes().lastOrNull { it.text?.toString()==text || it.contentDescription?.toString()==text }
    private fun await(text:String) { repeat(150) { if(find(text)!=null)return;settle(100) };snapshot("failed");error("Missing $text: ${nodes().map { "${it.text}:${it.contentDescription}" }}") }
    private fun click(text:String) {
        await(text);val node=find(text)!!;val rect=Rect().also(node::getBoundsInScreen);val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0).apply {
            source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(instrumentation.uiAutomation.injectInputEvent(this,true));recycle()
        }
        settle(400)
    }
    private fun settle(ms:Long) {SystemClock.sleep(ms);instrumentation.waitForIdleSync()}
    private fun snapshot(name:String) {settle(150);instrumentation.uiAutomation.takeScreenshot().apply { File(output,"$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle() } }
}
