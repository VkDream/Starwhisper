param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [string]$GradleHome = (Join-Path $PSScriptRoot '..\tools\android\gradle-8.13'),
    [string[]]$Tasks = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleFixture', ':app:assembleFixtureAndroidTest')
)
$ErrorActionPreference = 'Stop'
$gradleExecutable = Join-Path $GradleHome 'bin\gradle.bat'
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JavaHome must point to the verified JDK.' }
if (!(Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36\android.jar'))) { throw 'AndroidSdk must contain the verified Android 36 platform.' }
if (!(Test-Path -LiteralPath $gradleExecutable)) { throw 'GradleHome must point to the verified Gradle 8.13 distribution.' }
$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    & $gradleExecutable --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
}
