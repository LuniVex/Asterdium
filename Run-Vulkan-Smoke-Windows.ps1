$ErrorActionPreference = 'Stop'

# Reuse the pinned Gradle distribution from Asterdium 0.0.1's Windows build.
$gradle = Join-Path $env:LOCALAPPDATA 'Asterdium\tools\gradle-8.10.2\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) {
    throw 'Gradle not found. Run Build-Asterdium-Windows.cmd once to install the verified build tools, then retry.'
}

$smokeProject = Join-Path $PSScriptRoot 'VulkanSmoke'
Write-Host 'Starting Asterdium 0.0.2 standalone Vulkan window...'
Write-Host 'This is NOT Minecraft and will not modify any Fabric instance.'
Write-Host 'Close the test window or press Escape to exit.'

& $gradle --no-daemon --console plain --project-dir $smokeProject run
if ($LASTEXITCODE -ne 0) {
    throw "Vulkan smoke test exited with code $LASTEXITCODE"
}

Write-Host 'Asterdium Vulkan smoke test closed successfully.'
