$ErrorActionPreference = 'Stop'
$version = '8.10.2'
$expectedSha256 = '31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'

# Keep build tools away from Minecraft files and the LuniVex launcher checkout.
$toolRoot = Join-Path $env:LOCALAPPDATA 'Asterdium\tools'
$distributionRoot = Join-Path $toolRoot "gradle-$version"
$gradleExecutable = Join-Path $distributionRoot 'bin\gradle.bat'
$archive = Join-Path $toolRoot "gradle-$version-bin.zip"

if (!(Test-Path $gradleExecutable)) {
    New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null

    if (!(Test-Path $archive)) {
        Write-Host "Downloading Gradle $version from the official distribution service..."
        Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $archive
    }

    $actualSha256 = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Gradle archive failed SHA-256 verification. Remove $archive and retry."
    }

    Write-Host 'Verified official Gradle archive. Extracting...'
    Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot -Force
}

if (!(Test-Path $gradleExecutable)) {
    throw "Gradle installation did not create $gradleExecutable"
}

Write-Host 'Running CPU mesher tests and creating Asterdium development JAR...'
& $gradleExecutable --no-daemon --console plain build
if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit code $LASTEXITCODE)." }

Write-Host 'Asterdium development build completed.'
