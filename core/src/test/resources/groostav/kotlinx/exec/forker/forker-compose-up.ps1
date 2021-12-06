

$parent = Resolve-Path $(Get-Item $PSScriptRoot)

Write-Output "parent is starting in $parent PID=$PID"

Start-Process -NoNewWindow "powershell.exe" "-File $parent/Worker1.ps1 -ExecutionPolicy Bypass"
Start-Process -NoNewWindow "powershell.exe" "-File $parent/Worker2.ps1 -ExecutionPolicy Bypass"

While($true)
{
    Write-Output "Parent is looping..."
    Sleep -m 5000
}