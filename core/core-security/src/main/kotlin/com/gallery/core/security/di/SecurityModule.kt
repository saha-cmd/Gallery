package com.gallery.core.security.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    // VaultCryptoManager and BiometricAuthManager use @Inject constructor with @Singleton.
}
