package com.gallery.core.domain.usecase

import android.net.Uri
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.resolveFirstCoverUri
import com.gallery.core.domain.repository.MediaRepository
import com.gallery.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class GetAlbumsUseCase @Inject constructor(
    private val repository: MediaRepository,
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(filterEssential: Boolean = true): Flow<List<Album>> = combine(
        repository.getAlbums(),
        repository.getAlbumCovers(),
        settingsRepository.settings
    ) { albums, albumCovers, settings ->
        val filtered = if (filterEssential && settings.selectEssentialAlbums) {
            albums.filter { album ->
                album.isCustom || album.isSmartAlbum || settings.essentialAlbumNames.any { it.trim().equals(album.name.trim(), ignoreCase = true) }
            }
        } else {
            albums
        }
        filtered.map { album ->
            val customCoverStr = albumCovers["album_${album.id}"]
                ?: albumCovers["id_${album.id}"]
                ?: albumCovers["name_${album.name.trim().lowercase()}"]
            val customUri = customCoverStr?.let { Uri.parse(it) }

            val resolvedUri = if (customUri != null && album.items.any { it.uri == customUri || it.uri.toString() == customCoverStr }) {
                customUri
            } else {
                album.items.resolveFirstCoverUri()
            }

            if (resolvedUri != null && album.coverUri != resolvedUri) {
                album.copy(coverUri = resolvedUri)
            } else {
                album
            }
        }
    }
}
