package com.gallery.core.data.updater

/**
 * Configuration for the self-hosted in-app update checker.
 *
 * Configure either GitHub Releases API or a custom JSON manifest URL below:
 *
 * 1. GitHub Releases API (Default):
 *    - Set [GITHUB_OWNER] to your GitHub username / organization (e.g., "octocat").
 *    - Set [GITHUB_REPO] to your GitHub repository name (e.g., "Gallery").
 *    - Attach your signed release APK (e.g. `gallery-release.apk`) to your GitHub Releases.
 *
 * 2. Custom JSON Manifest:
 *    - Set [CUSTOM_MANIFEST_URL] to your URL (e.g., "https://example.com/gallery/update.json").
 *    - Expected JSON structure:
 *      {
 *        "versionCode": 2,
 *        "versionName": "1.1.0",
 *        "apkUrl": "https://example.com/gallery/gallery-v1.1.0.apk",
 *        "releaseNotes": "• Feature improvements\n• Bug fixes",
 *        "fileSizeBytes": 15420000,
 *        "publishedAt": "2026-11-17T00:00:00Z"
 *      }
 */
object UpdateConfig {
    /**
     * GitHub repository owner/organization (e.g., "YOUR_GITHUB_USERNAME").
     */
    const val GITHUB_OWNER: String = "saha-cmd"

    /**
     * GitHub repository name (e.g., "Gallery").
     */
    const val GITHUB_REPO: String = "Gallery"

    /**
     * Custom update manifest JSON URL.
     * When non-empty, the updater fetches from this URL instead of GitHub Releases.
     */
    const val CUSTOM_MANIFEST_URL: String = ""
}
