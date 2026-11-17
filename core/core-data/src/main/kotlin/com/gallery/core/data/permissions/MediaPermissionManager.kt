package com.gallery.core.data.permissions

import android.Manifest
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPermissionManager @Inject constructor() {

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
