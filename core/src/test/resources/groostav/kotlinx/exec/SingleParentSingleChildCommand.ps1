[CmdletBinding()]
Param(
    [Switch] $WaitForExit
)

$pinfo = New-Object System.Diagnostics.ProcessStartInfo
$pinfo.FileName = "ping"
$pinfo.RedirectStandardError = $true
$pinfo.RedirectStandardOutput = $true
$pinfo.UseShellExecute = $false
$pinfo.CreateNoWindow = $true
$pinfo.Arguments = "-n","2000","localhost"
$p = New-Object System.Diagnostics.Process
$p.StartInfo = $pinfo
$p.Start() | Out-Null

echo "parentPID=$PID"
echo "childPID=$($p.Id)"

If($WaitForExit)
{
    $p.WaitForExit()

    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    Write-Host "stdout: $stdout"
    Write-Host "stderr: $stderr"
    Write-Host "exit code: " + $p.ExitCode
}
