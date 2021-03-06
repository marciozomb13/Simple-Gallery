package com.simplemobiletools.gallery.activities

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.support.v7.widget.GridLayoutManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.bumptech.glide.Glide
import com.bumptech.glide.request.animation.GlideAnimation
import com.bumptech.glide.request.target.SimpleTarget
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.adapters.MediaAdapter
import com.simplemobiletools.gallery.asynctasks.GetMediaAsynctask
import com.simplemobiletools.gallery.dialogs.ChangeSortingDialog
import com.simplemobiletools.gallery.dialogs.ExcludeFolderDialog
import com.simplemobiletools.gallery.extensions.*
import com.simplemobiletools.gallery.helpers.*
import com.simplemobiletools.gallery.models.Medium
import com.simplemobiletools.gallery.views.MyScalableRecyclerView
import kotlinx.android.synthetic.main.activity_media.*
import java.io.File
import java.io.IOException

class MediaActivity : SimpleActivity(), MediaAdapter.MediaOperationsListener {
    private val TAG = MediaActivity::class.java.simpleName
    private val SAVE_MEDIA_CNT = 40

    private var mMedia = ArrayList<Medium>()
    private var mCurrAsyncTask: GetMediaAsynctask? = null

    private var mPath = ""
    private var mIsGetImageIntent = false
    private var mIsGetVideoIntent = false
    private var mIsGetAnyIntent = false
    private var mIsGettingMedia = false
    private var mShowAll = false
    private var mLoadedInitialPhotos = false
    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        intent.apply {
            mIsGetImageIntent = getBooleanExtra(GET_IMAGE_INTENT, false)
            mIsGetVideoIntent = getBooleanExtra(GET_VIDEO_INTENT, false)
            mIsGetAnyIntent = getBooleanExtra(GET_ANY_INTENT, false)
        }

        media_refresh_layout.setOnRefreshListener({ getMedia() })
        mPath = intent.getStringExtra(DIRECTORY)
        mStoredAnimateGifs = config.animateGifs
        mStoredCropThumbnails = config.cropThumbnails
        mShowAll = config.showAll
        if (mShowAll)
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        if (mShowAll && mStoredAnimateGifs != config.animateGifs) {
            mMedia.clear()
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            mMedia.clear()
        }
        tryloadGallery()
    }

    override fun onPause() {
        super.onPause()
        mCurrAsyncTask?.shouldStop = true
        mIsGettingMedia = false
        media_refresh_layout.isRefreshing = false
        mStoredAnimateGifs = config.animateGifs
        mStoredCropThumbnails = config.cropThumbnails
        MyScalableRecyclerView.mListener = null
    }

    private fun tryloadGallery() {
        if (hasWriteStoragePermission()) {
            val dirName = getHumanizedFilename(mPath)
            title = if (mShowAll) resources.getString(R.string.all_folders) else dirName
            getMedia()
            handleZooming()
        } else {
            finish()
        }
    }

    private fun initializeGallery() {
        if (isDirEmpty())
            return

        val adapter = MediaAdapter(this, mMedia, this) {
            itemClicked(it.path)
        }

        val currAdapter = media_grid.adapter
        if (currAdapter != null) {
            (currAdapter as MediaAdapter).updateMedia(mMedia)
        } else {
            media_grid.adapter = adapter
        }
        media_fastscroller.setViews(media_grid, media_refresh_layout)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_media, menu)

        val isFolderHidden = File(mPath).containsNoMedia()
        menu.apply {
            findItem(R.id.hide_folder).isVisible = !isFolderHidden && !mShowAll
            findItem(R.id.unhide_folder).isVisible = isFolderHidden && !mShowAll

            findItem(R.id.folder_view).isVisible = mShowAll
            findItem(R.id.open_camera).isVisible = mShowAll
            findItem(R.id.about).isVisible = mShowAll

            findItem(R.id.increase_column_count).isVisible = config.mediaColumnCnt < 10
            findItem(R.id.reduce_column_count).isVisible = config.mediaColumnCnt > 1
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> showSortingDialog()
            R.id.toggle_filename -> toggleFilenameVisibility()
            R.id.open_camera -> launchCamera()
            R.id.folder_view -> switchToFolderView()
            R.id.hide_folder -> tryHideFolder()
            R.id.unhide_folder -> unhideFolder()
            R.id.exclude_folder -> tryExcludeFolder()
            R.id.increase_column_count -> increaseColumnCount()
            R.id.reduce_column_count -> reduceColumnCount()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleFilenameVisibility() {
        config.displayFileNames = !config.displayFileNames
        if (media_grid.adapter != null)
            (media_grid.adapter as MediaAdapter).updateDisplayFilenames(config.displayFileNames)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, false, !config.showAll, mPath) {
            getMedia()
        }
    }

    private fun switchToFolderView() {
        config.showAll = false
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun tryHideFolder() {
        if (config.wasHideFolderTooltipShown) {
            hideFolder()
        } else {
            ConfirmationDialog(this, getString(R.string.hide_folder_description)) {
                config.wasHideFolderTooltipShown = true
                hideFolder()
            }
        }
    }

    private fun hideFolder() {
        addNoMedia(mPath) {
            runOnUiThread {
                if (!config.shouldShowHidden)
                    finish()
                else
                    invalidateOptionsMenu()
            }
        }
    }

    private fun unhideFolder() {
        removeNoMedia(mPath) {
            runOnUiThread {
                invalidateOptionsMenu()
            }
        }
    }

    private fun tryExcludeFolder() {
        ExcludeFolderDialog(this, arrayListOf(mPath)) {
            finish()
        }
    }

    private fun deleteDirectoryIfEmpty() {
        val file = File(mPath)
        if (!file.isDownloadsFolder() && file.isDirectory && file.listFiles()?.isEmpty() == true) {
            file.delete()
        }
    }

    private fun getMedia() {
        if (mIsGettingMedia)
            return

        mIsGettingMedia = true
        val token = object : TypeToken<List<Medium>>() {}.type
        val media = Gson().fromJson<ArrayList<Medium>>(config.loadFolderMedia(mPath), token) ?: ArrayList<Medium>(1)
        if (media.isNotEmpty() && !mLoadedInitialPhotos) {
            gotMedia(media)
        } else {
            media_refresh_layout.isRefreshing = true
        }

        mLoadedInitialPhotos = true
        mCurrAsyncTask = GetMediaAsynctask(applicationContext, mPath, mIsGetVideoIntent, mIsGetImageIntent, mShowAll) {
            gotMedia(it)
        }
        mCurrAsyncTask!!.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun isDirEmpty(): Boolean {
        return if (mMedia.size <= 0) {
            deleteDirectoryIfEmpty()
            finish()
            true
        } else
            false
    }

    private fun handleZooming() {
        val layoutManager = media_grid.layoutManager as GridLayoutManager
        layoutManager.spanCount = config.mediaColumnCnt
        MyScalableRecyclerView.mListener = object : MyScalableRecyclerView.ZoomListener {
            override fun zoomIn() {
                if (layoutManager.spanCount > 1) {
                    reduceColumnCount()
                    MediaAdapter.actMode?.finish()
                }
            }

            override fun zoomOut() {
                if (layoutManager.spanCount < 10) {
                    increaseColumnCount()
                    MediaAdapter.actMode?.finish()
                }
            }
        }
    }

    private fun increaseColumnCount() {
        config.mediaColumnCnt = ++(media_grid.layoutManager as GridLayoutManager).spanCount
        invalidateOptionsMenu()
    }

    private fun reduceColumnCount() {
        config.mediaColumnCnt = --(media_grid.layoutManager as GridLayoutManager).spanCount
        invalidateOptionsMenu()
    }

    override fun deleteFiles(files: ArrayList<File>) {
        val filtered = files.filter { it.exists() && it.isImageVideoGif() } as ArrayList
        deleteFiles(filtered) {
            if (!it) {
                runOnUiThread {
                    toast(R.string.unknown_error_occurred)
                }
            } else if (mMedia.isEmpty()) {
                finish()
            }
        }
    }

    private fun isSetWallpaperIntent() = intent.getBooleanExtra(SET_WALLPAPER_INTENT, false)

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE) {
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                mMedia.clear()
                refreshItems()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun itemClicked(path: String) {
        if (isSetWallpaperIntent()) {
            toast(R.string.setting_wallpaper)

            val wantedWidth = wallpaperDesiredMinimumWidth
            val wantedHeight = wallpaperDesiredMinimumHeight
            val ratio = wantedWidth.toFloat() / wantedHeight
            Glide.with(this)
                    .load(File(path))
                    .asBitmap()
                    .override((wantedWidth * ratio).toInt(), wantedHeight)
                    .fitCenter()
                    .into(object : SimpleTarget<Bitmap>() {
                        override fun onResourceReady(bitmap: Bitmap?, glideAnimation: GlideAnimation<in Bitmap>?) {
                            try {
                                WallpaperManager.getInstance(applicationContext).setBitmap(bitmap)
                                setResult(Activity.RESULT_OK)
                            } catch (e: IOException) {
                                Log.e(TAG, "item click $e")
                            }

                            finish()
                        }
                    })
        } else if (mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent) {
            Intent().apply {
                data = Uri.parse(path)
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else {
            val file = File(path)
            val isVideo = file.isVideoFast()
            if (isVideo) {
                openWith(file, false)
            } else {
                Intent(this, ViewPagerActivity::class.java).apply {
                    putExtra(MEDIUM, path)
                    putExtra(SHOW_ALL, mShowAll)
                    startActivity(this)
                }
            }
        }
    }

    private fun gotMedia(media: ArrayList<Medium>) {
        mIsGettingMedia = false
        media_refresh_layout.isRefreshing = false

        if (media.hashCode() == mMedia.hashCode())
            return

        mMedia = media
        initializeGallery()
        storeFolder()
    }

    private fun storeFolder() {
        val subList = mMedia.subList(0, Math.min(SAVE_MEDIA_CNT, mMedia.size))
        val json = Gson().toJson(subList)
        config.saveFolderMedia(mPath, json)
    }

    override fun refreshItems() {
        getMedia()
    }
}
