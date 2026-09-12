[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @(':app:assembleDebug')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
# Process-local discovery: no registry or user environment writes.
$candidates = @(
    $env:JAVA_HOME,
    $(if ($env:ANDROID_STUDIO_HOME) { Join-Path $env:ANDROID_STUDIO_HOME 'jbr' }),
    $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr' }),
    $(if ($env:ProgramFiles) { Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' })
)
if (Test-Path 'HKLM:\SOFTWARE\Android Studio') {
    $studioPath = (Get-ItemProperty 'HKLM:\SOFTWARE\Android Studio' -ErrorAction SilentlyContinue).Path
    if ($studioPath) { $candidates += Join-Path $studioPath 'jbr' }
}
$javaHome = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $javaHome) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
}
if (-not $javaHome) {
    throw 'No JDK found. Install Android Studio or set JAVA_HOME to a JDK 17 or newer.'
}
try {
    $env:JAVA_HOME = $javaHome
    $env:PATH = "$(Join-Path $javaHome 'bin');$previousPath"
    Write-Host "Using JDK: $javaHome"
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') @GradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
