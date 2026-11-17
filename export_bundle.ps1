# Export Gallery App Source Code Bundle
$ErrorActionPreference = "Stop"

$rootDir = (Get-Location).Path
$outputFileName = "gallery_app_source_bundle.md"
$outputPath = Join-Path $rootDir $outputFileName

Write-Host "Scanning project at: $rootDir"

function Get-FileSortKey($relPath) {
    $norm = $relPath.Replace('\', '/')
    if ($norm -notlike '*/*') { return "00_$norm" }
    if ($norm -like 'gradle/*') { return "01_$norm" }
    if ($norm -like 'app/*') { return "02_$norm" }
    if ($norm -like 'core/*') { return "03_$norm" }
    if ($norm -like 'feature/*') { return "04_$norm" }
    return "05_$norm"
}

# Collect all relevant files
$allFiles = Get-ChildItem -Path $rootDir -Recurse -File | Where-Object {
    $fullName = $_.FullName
    $relPath = $fullName.Substring($rootDir.Length + 1)
    $parts = $relPath.Split([System.IO.Path]::DirectorySeparatorChar)

    # Exclude build, git, ide, wrapper, and generator scripts/bundles
    if ($parts -contains '.git' -or 
        $parts -contains '.gradle' -or 
        $parts -contains '.idea' -or 
        $parts -contains 'build' -or 
        ($parts -contains 'gradle' -and $parts -contains 'wrapper') -or
        $_.Name -eq $outputFileName -or
        $_.Name -eq 'export_bundle.ps1' -or
        $_.Name -eq 'export_bundle.py') {
        return $false
    }

    # Inclusions
    if ($_.Extension -eq '.kt' -or 
        $_.Extension -eq '.kts' -or 
        $_.Name -eq 'libs.versions.toml' -or 
        $_.Name -eq 'gradle.properties' -or 
        $_.Name -eq 'AndroidManifest.xml') {
        return $true
    }

    return $false
}

# Sort files cleanly: root config -> gradle -> app -> core -> feature
$sortedFiles = $allFiles | Sort-Object {
    $rel = $_.FullName.Substring($rootDir.Length + 1).Replace('\', '/')
    Get-FileSortKey $rel
}

Write-Host "Found $($sortedFiles.Count) source files to bundle."

# Standard triple backticks fence without PowerShell escaping artifacts
$fence = [string]::new([char]96, 3)

# Build the Markdown bundle using StringBuilder for speed & memory efficiency
$sb = [System.Text.StringBuilder]::new()

[void]$sb.AppendLine("# Gallery App Android Source Code Bundle")
[void]$sb.AppendLine()
[void]$sb.AppendLine("> Comprehensive source code bundle containing all Kotlin source files, Gradle Kotlin scripts, version catalogs, properties, and manifests across all modules of the Gallery project.")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## File Index")
[void]$sb.AppendLine()

foreach ($file in $sortedFiles) {
    $relPath = $file.FullName.Substring($rootDir.Length + 1).Replace('\', '/')
    $anchor = "file-" + ($relPath.ToLower() -replace '[^a-z0-9_-]', '-')
    [void]$sb.AppendLine("- [$relPath](#$anchor)")
}

[void]$sb.AppendLine()
[void]$sb.AppendLine("---")
[void]$sb.AppendLine()

foreach ($file in $sortedFiles) {
    $relPath = $file.FullName.Substring($rootDir.Length + 1).Replace('\', '/')
    $extension = $file.Extension.ToLower()
    $name = $file.Name.ToLower()

    $codeFenceLang = switch ($true) {
        ($extension -eq '.kt' -or $extension -eq '.kts') { 'kotlin' }
        ($extension -eq '.xml' -or $name -eq 'androidmanifest.xml') { 'xml' }
        ($extension -eq '.toml' -or $name -eq 'libs.versions.toml') { 'toml' }
        ($name -eq 'gradle.properties' -or $extension -eq '.properties') { 'properties' }
        default { 'text' }
    }

    $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)

    [void]$sb.AppendLine("## File: $relPath")
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("$fence$codeFenceLang")
    [void]$sb.AppendLine($content)
    [void]$sb.AppendLine($fence)
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("---")
    [void]$sb.AppendLine()
}

# Write out with UTF-8 without BOM
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($outputPath, $sb.ToString(), $utf8NoBom)

# Verification & Inspection
$bundleInfo = Get-Item $outputPath
$bundleSizeKB = [math]::Round($bundleInfo.Length / 1024, 2)
$bundleText = [System.IO.File]::ReadAllText($outputPath, [System.Text.Encoding]::UTF8)

$hasMainActivity = $bundleText.Contains("class MainActivity : ComponentActivity()") -or $bundleText.Contains("MainActivity.kt")
$hasManifest = $bundleText.Contains("<manifest xmlns:android") -and $bundleText.Contains("<application")
$isNonEmpty = $bundleInfo.Length -gt 0

Write-Host "=========================================================="
Write-Host "=== Gallery App Source Code Bundle Export Complete ==="
Write-Host "=========================================================="
Write-Host "Output File: $outputPath"
Write-Host "Total Bundled File Count: $($sortedFiles.Count)"
Write-Host "Final File Size: $bundleSizeKB KB ($($bundleInfo.Length) bytes)"
Write-Host ""
Write-Host "Verification Checklist:"
Write-Host "  - Non-empty bundle: $(if ($isNonEmpty) { '[PASS]' } else { '[FAIL]' })"
Write-Host "  - MainActivity.kt implementation present: $(if ($hasMainActivity) { '[PASS]' } else { '[FAIL]' })"
Write-Host "  - AndroidManifest.xml implementation present: $(if ($hasManifest) { '[PASS]' } else { '[FAIL]' })"

if (-not ($isNonEmpty -and $hasMainActivity -and $hasManifest)) {
    Write-Error "Bundle verification failed!"
    exit 1
}

Write-Host ""
Write-Host "Export completed and verified successfully."
