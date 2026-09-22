# ==============================================================================
# Campus Ride - Live Development Watcher & Instant Deployer
# Automatically detects code changes, compiles incrementally, and updates your phone!
# ==============================================================================

$ErrorActionPreference = "Continue"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$AdbPath = "C:\Users\Sundaram\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$AppPackage = "com.aistudio.campusride.xqlmzs"
$MainActivity = "com.example.MainActivity"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  Campus Ride: Live Auto-Deploy & Fast Iteration Watcher" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Cyan

# 1. Check ADB device connection
Write-Host "`n[1/3] Checking connected Android devices via ADB..." -ForegroundColor Cyan
if (Test-Path $AdbPath) {
    $devicesOutput = & $AdbPath devices | Out-String
    $deviceLines = ($devicesOutput -split "`r?`n") | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }
    
    if ($deviceLines.Count -eq 0) {
        Write-Host "WARNING: No phone or emulator detected by ADB yet!" -ForegroundColor Red
        Write-Host "Please ensure:" -ForegroundColor Yellow
        Write-Host "  1. Phone is plugged in via USB (or wireless debugging enabled)."
        Write-Host "  2. 'Developer Options' -> 'USB Debugging' is enabled on your phone."
        Write-Host "  3. Accept the 'Allow USB debugging?' prompt on your phone screen."
        Write-Host "`nThe watcher will still monitor files and auto-deploy as soon as connected.`n" -ForegroundColor Gray
    } else {
        Write-Host "SUCCESS: Device detected!" -ForegroundColor Green
        $deviceLines | ForEach-Object { Write-Host "  -> $_" -ForegroundColor Green }
    }
} else {
    Write-Host "WARNING: ADB not found at $AdbPath" -ForegroundColor Red
}

# 2. Watcher function
$WatchPath = Join-Path $PSScriptRoot "app\src\main"
Write-Host "`n[2/3] Setting up real-time file watcher on:" -ForegroundColor Cyan
Write-Host "  $WatchPath" -ForegroundColor White
Write-Host "`n[3/3] Ready! Whenever you save code in Android Studio / editor," -ForegroundColor Green
Write-Host "      it will automatically build & push directly to your phone." -ForegroundColor Green
Write-Host "      (Press Ctrl + C to stop watcher)`n" -ForegroundColor Gray

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $WatchPath
$watcher.IncludeSubdirectories = $true
$watcher.EnableRaisingEvents = $true
$watcher.Filter = "*.*"

$lastDeployTime = [DateTime]::MinValue
$deployLock = $false

function Trigger-Deploy {
    param([string]$ChangedFile)
    
    $now = [DateTime]::Now
    if (($now - $script:lastDeployTime).TotalSeconds -lt 2) {
        return # Debounce rapid multi-file save events
    }
    $script:lastDeployTime = $now
    
    if ($script:deployLock) { return }
    $script:deployLock = $true
    
    try {
        $fileName = Split-Path -Leaf $ChangedFile
        Write-Host "`n[$([DateTime]::Now.ToString('HH:mm:ss'))] Change detected in: $fileName" -ForegroundColor Magenta
        Write-Host "-> Building and pushing to device..." -ForegroundColor Cyan
        
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        
        # Run incremental installDebug
        $gradleProcess = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "app:installDebug", "--daemon", "--parallel", "--build-cache", "-q" -NoNewWindow -PassThru -Wait
        
        $sw.Stop()
        $elapsedSec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        
        if ($gradleProcess.ExitCode -eq 0) {
            # Restart activity without full reinstallation
            if (Test-Path $AdbPath) {
                & $AdbPath shell am start -n "$AppPackage/$MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > $null 2>&1
            }
            Write-Host "[SUCCESS] Updated on phone in ${elapsedSec}s! Active and ready." -ForegroundColor Green
        } else {
            Write-Host "[ERROR] Build failed. Check the error in your editor." -ForegroundColor Red
        }
    } finally {
        $script:deployLock = $false
    }
}

$action = {
    $path = $Event.SourceEventArgs.FullPath
    $ext = [System.IO.Path]::GetExtension($path).ToLower()
    if ($ext -in @(".kt", ".xml", ".properties", ".gradle", ".kts", ".png", ".webp")) {
        Trigger-Deploy -ChangedFile $path
    }
}

Register-ObjectEvent $watcher "Changed" -Action $action > $null
Register-ObjectEvent $watcher "Created" -Action $action > $null

try {
    while ($true) {
        Start-Sleep -Milliseconds 500
    }
} finally {
    Unregister-Event -SourceIdentifier $watcher.ToString() -ErrorAction SilentlyContinue
    $watcher.Dispose()
    Write-Host "`nWatcher stopped." -ForegroundColor Yellow
}
