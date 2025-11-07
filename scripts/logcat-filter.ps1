# SimpleXray Logcat Filter Script
# PowerShell script to filter logcat output with time format
# Usage: .\logcat-filter.ps1 [options]

param(
    [string]$Package = "com.simplexray.an",
    [string]$Device = "",
    [string]$Tag = "",
    [string]$Level = "",
    [string]$Grep = "",
    [switch]$Clear = $false,
    [switch]$Help = $false
)

if ($Help) {
    Write-Host @"
SimpleXray Logcat Filter Script
================================

Usage:
  .\logcat-filter.ps1 [options]

Options:
  -Package <name>    Package name to filter (default: com.simplexray.an)
  -Device <serial>   Device serial number (optional)
  -Tag <tag>         Filter by log tag (e.g., TProxyService, Xray)
  -Level <level>     Filter by log level (V, D, I, W, E, F)
  -Grep <pattern>    Additional grep pattern to filter lines
  -Clear             Clear logcat buffer before starting
  -Help              Show this help message

Examples:
  .\logcat-filter.ps1
  .\logcat-filter.ps1 -Tag "TProxyService" -Level "E"
  .\logcat-filter.ps1 -Grep "VPN|Xray" -Clear
  .\logcat-filter.ps1 -Device "emulator-5554" -Level "W"
"@
    exit 0
}

# Check if adb is available
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Host "ERROR: 'adb' not found in PATH. Please install Android Platform Tools." -ForegroundColor Red
    exit 1
}

# Build adb command
$adbCmd = "adb"
if ($Device) {
    $adbCmd += " -s $Device"
}

# Check device connection
Write-Host "[+] Checking device connection..." -ForegroundColor Cyan
$devices = & adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Host "ERROR: No device connected or authorized. Run 'adb devices' to check." -ForegroundColor Red
    exit 1
}

# Get PID for the package
Write-Host "[+] Finding PID for package: $Package" -ForegroundColor Cyan
$pidOutput = & adb shell "pidof $Package" 2>&1
$pid = $pidOutput.Trim()

if (-not $pid -or $pid -match "^\s*$") {
    Write-Host "WARNING: Package $Package is not running. Starting logcat without PID filter..." -ForegroundColor Yellow
    $usePid = $false
} else {
    Write-Host "[+] Found PID: $pid" -ForegroundColor Green
    $usePid = $true
}

# Clear logcat if requested
if ($Clear) {
    Write-Host "[+] Clearing logcat buffer..." -ForegroundColor Cyan
    & adb logcat -c 2>&1 | Out-Null
}

# Build logcat command
$logcatCmd = "logcat -v time"
if ($usePid) {
    $logcatCmd += " --pid $pid"
}
if ($Tag) {
    $logcatCmd += " $Tag`:$Level"
} elseif ($Level) {
    $logcatCmd += " *:$Level"
}

Write-Host "[+] Starting logcat filter..." -ForegroundColor Cyan
Write-Host "[+] Package: $Package" -ForegroundColor Cyan
if ($Tag) { Write-Host "[+] Tag filter: $Tag" -ForegroundColor Cyan }
if ($Level) { Write-Host "[+] Level filter: $Level" -ForegroundColor Cyan }
if ($Grep) { Write-Host "[+] Grep pattern: $Grep" -ForegroundColor Cyan }
Write-Host "`n[Press Ctrl+C to stop]`n" -ForegroundColor Yellow

# Start logcat process
$processInfo = New-Object System.Diagnostics.ProcessStartInfo
$processInfo.FileName = "adb"
$processInfo.Arguments = if ($Device) { "-s $Device shell $logcatCmd" } else { "shell $logcatCmd" }
$processInfo.UseShellExecute = $false
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$processInfo.CreateNoWindow = $true

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $processInfo

# Handle output with optional grep filtering
$process.add_OutputDataReceived({
    param($sender, $e)
    if ($e.Data) {
        $line = $e.Data
        if ($Grep) {
            if ($line -match $Grep) {
                Write-Host $line
            }
        } else {
            Write-Host $line
        }
    }
})

$process.add_ErrorDataReceived({
    param($sender, $e)
    if ($e.Data) {
        Write-Host $e.Data -ForegroundColor Red
    }
})

try {
    $process.Start() | Out-Null
    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()
    
    # Wait for process to exit or user interrupt
    while (-not $process.HasExited) {
        Start-Sleep -Milliseconds 100
    }
} catch {
    Write-Host "ERROR: Failed to start logcat: $_" -ForegroundColor Red
    exit 1
} finally {
    if (-not $process.HasExited) {
        $process.Kill()
    }
    $process.Dispose()
}



