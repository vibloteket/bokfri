param(
    [Parameter(Mandatory = $true)]
    [string]$Msi,

    [Parameter(Mandatory = $true)]
    [string]$CliName,

    [Parameter(Mandatory = $true)]
    [string]$AppName,

    [Parameter(Mandatory = $true)]
    [string]$AppSubdir
)

$ErrorActionPreference = "Stop"

$msiPath = (Resolve-Path $Msi).Path
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$testRoot = Join-Path $env:RUNNER_TEMP ("bokfri-msi-" + [Guid]::NewGuid().ToString("N"))
$installRoot = Join-Path $env:ProgramFiles $AppName
$installLog = Join-Path $testRoot "install.log"
$uninstallLog = Join-Path $testRoot "uninstall.log"
$userData = Join-Path $env:LOCALAPPDATA $AppSubdir
$marker = Join-Path $userData "msi-uninstall-must-preserve-data.txt"
$installed = $false
$failure = $null

New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Invoke-Msi {
    param(
        [string[]]$Arguments,
        [string]$Operation
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "msiexec.exe"
    $startInfo.UseShellExecute = $false
    foreach ($argument in $Arguments) {
        $startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::Start($startInfo)
    $process.WaitForExit()
    if ($process.ExitCode -notin @(0, 3010)) {
        throw "$Operation failed with msiexec exit code $($process.ExitCode)"
    }
}

try {
    Invoke-Msi -Operation "MSI installation" -Arguments @(
        "/i", $msiPath,
        "/qn", "/norestart",
        "/l*v", $installLog
    )
    $installed = $true

    $cli = Join-Path $installRoot "$CliName.exe"
    if (-not (Test-Path -LiteralPath $cli -PathType Leaf)) {
        throw "Installed CLI launcher was not found at $cli"
    }

    & java (Join-Path $workspace "scripts/CliSmokeTest.java") $cli
    if ($LASTEXITCODE -ne 0) {
        throw "CLI black-box test failed with exit code $LASTEXITCODE"
    }

    New-Item -ItemType Directory -Force -Path $userData | Out-Null
    Set-Content -LiteralPath $marker -Value "Bokfri user data must survive MSI uninstall." -Encoding UTF8
}
catch {
    $failure = $_
}
finally {
    if ($installed) {
        try {
            Invoke-Msi -Operation "MSI uninstall" -Arguments @(
                "/x", $msiPath,
                "/qn", "/norestart",
                "/l*v", $uninstallLog
            )
        }
        catch {
            if ($null -eq $failure) {
                $failure = $_
            } else {
                Write-Error $_
            }
        }
    }
}

if ($null -ne $failure) {
    if (Test-Path $installLog) {
        Write-Host "--- MSI install log (tail) ---"
        Get-Content $installLog -Tail 120
    }
    if (Test-Path $uninstallLog) {
        Write-Host "--- MSI uninstall log (tail) ---"
        Get-Content $uninstallLog -Tail 120
    }
    throw $failure
}

$cliAfterUninstall = Join-Path $installRoot "$CliName.exe"
if (Test-Path -LiteralPath $cliAfterUninstall) {
    throw "MSI uninstall left the CLI launcher behind: $cliAfterUninstall"
}
if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
    throw "MSI uninstall removed Bokfri user data: $marker"
}

Remove-Item -LiteralPath $marker -Force
if ((Test-Path $userData) -and -not (Get-ChildItem -LiteralPath $userData -Force)) {
    Remove-Item -LiteralPath $userData -Force
}
Remove-Item -LiteralPath $testRoot -Recurse -Force

Write-Host "Bokfri MSI install/use/uninstall test passed: $([IO.Path]::GetFileName($msiPath))"
