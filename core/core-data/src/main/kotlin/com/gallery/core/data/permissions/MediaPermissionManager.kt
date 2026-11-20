package com.gallery.core.data.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPermissionManager @Inject constructor() {

    companion object {
        fun canModifyMediaSilently(context: Context): Boolean {
            return when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context) -> true
                else -> false
            }
        }

        fun createManageAllFilesAccessIntent(context: Context): Intent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                if (appIntent.resolveActivity(context.packageManager) != null) {
                    return appIntent
                }
                val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                if (generalIntent.resolveActivity(context.packageManager) != null) {
                    return generalIntent
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mediaIntent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                if (mediaIntent.resolveActivity(context.packageManager) != null) {
                    return mediaIntent
                }
            }
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun canModifyMediaSilently(context: Context): Boolean = Companion.canModifyMediaSilently(context)

    fun createManageAllFilesAccessIntent(context: Context): Intent = Companion.createManageAllFilesAccessIntent(context)

    fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    fun hasFullAccess(grantResults: Map<String, Boolean>): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            grantResults[Manifest.permission.READ_MEDIA_IMAGES] == true &&
                    grantResults[Manifest.permission.READ_MEDIA_VIDEO] == true
        } else {
            grantResults.values.all { it }
        }
    }
}
