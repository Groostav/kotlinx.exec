#Simple (inline) Progress Bar script
Param(
    [int]$SleepTime = 500
)

$scriptPath = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
Import-Module "$scriptPath/psInlineProgress.psm1"

#Simple Progress Bar script

Write-Output "started Important task 42"
foreach($index in 1..10){
    Write-InlineProgress -UseWriteOutput -Activity "Important Task 42" -PercentComplete ($index * 10)
    Sleep -m $SleepTime
}
Write-Progress -Activity "ASDF" -Completed

Write-Output "done Important Task 42!"