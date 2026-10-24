package com.neilturner.aerialviews.services.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.prefs.ProjectivyAmazonPrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyApplePrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyComm1Prefs
import com.neilturner.aerialviews.models.prefs.ProjectivyComm2Prefs
import com.neilturner.aerialviews.models.prefs.ProjectivyLocalMediaPrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyPrefs
import com.neilturner.aerialviews.providers.AmazonMediaProvider
import com.neilturner.aerialviews.providers.AppleMediaProvider
import com.neilturner.aerialviews.providers.Comm1MediaProvider
import com.neilturner.aerialviews.providers.Comm2MediaProvider
import com.neilturner.aerialviews.providers.LocalMediaProvider
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.ProviderFetchResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Read-only media catalogue consumed by tv-vibehack-firetv.
 *
 * The provider deliberately exposes only media URIs and labels, and only to the launcher package.
 * Playback and media access remain owned by AerialViews' existing providers.
 */
class LauncherWallpaperProvider : ContentProvider() {
    private val localMedia = ConcurrentHashMap<String, Uri>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceLauncherCaller()

        val cursor = MatrixCursor(arrayOf(BaseColumns._ID, COLUMN_URI, COLUMN_TYPE, COLUMN_TITLE))
        localMedia.clear()
        val enabledProviders = getEnabledProviders()
        val media =
            runBlocking {
                enabledProviders.filter { it.enabled }.flatMap { provider ->
                    try {
                        when (val result = provider.fetch()) {
                            is ProviderFetchResult.Success -> result.media
                            is ProviderFetchResult.Error -> emptyList()
                        }
                    } catch (exception: Exception) {
                        Timber.w(exception, "Launcher wallpaper provider failed")
                        emptyList()
                    }
                }
            }.let { items -> if (ProjectivyPrefs.shuffleVideos) items.shuffled() else items }

        media.forEachIndexed { index, item ->
            cursor.addRow(
                arrayOf(
                    index,
                    playbackUri(item.uri),
                    if (item.type == AerialMediaType.VIDEO) TYPE_VIDEO else TYPE_IMAGE,
                    item.metadata.shortDescription,
                ),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.com.neilturner.aerialviews.launcher-wallpaper"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceLauncherCaller()
        if (mode != "r") throw FileNotFoundException("AerialViews media is read-only")
        val token = uri.lastPathSegment ?: throw FileNotFoundException("Missing local media token")
        val source = localMedia[token] ?: throw FileNotFoundException("Local media item expired")
        val path = source.path ?: throw FileNotFoundException("Local media path unavailable")
        return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun playbackUri(source: Uri): String {
        if (source.scheme != "file") return source.toString()
        val token = UUID.randomUUID().toString()
        localMedia[token] = source
        return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("local").appendPath(token).build().toString()
    }

    private fun enforceLauncherCaller() {
        val uid = Binder.getCallingUid()
        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        if (uid != android.os.Process.myUid() && packages.none { it == LAUNCHER_PACKAGE }) {
            throw SecurityException("Only tv-vibehack-firetv may access AerialViews launcher media")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only provider")

    private fun getEnabledProviders(): List<MediaProvider> =
        listOf(
            AppleMediaProvider(requireNotNull(context).applicationContext, ProjectivyApplePrefs),
            Comm1MediaProvider(requireNotNull(context).applicationContext, ProjectivyComm1Prefs),
            Comm2MediaProvider(requireNotNull(context).applicationContext, ProjectivyComm2Prefs),
            AmazonMediaProvider(requireNotNull(context).applicationContext, ProjectivyAmazonPrefs),
            LocalMediaProvider(requireNotNull(context).applicationContext, ProjectivyLocalMediaPrefs),
        )

    companion object {
        const val AUTHORITY = "com.neilturner.aerialviews.launcher.wallpapers"
        const val COLUMN_URI = "uri"
        const val COLUMN_TYPE = "type"
        const val COLUMN_TITLE = "title"
        const val TYPE_VIDEO = "VIDEO"
        const val TYPE_IMAGE = "IMAGE"
        const val LAUNCHER_PACKAGE = "com.gathin.tvhome"
    }
}
