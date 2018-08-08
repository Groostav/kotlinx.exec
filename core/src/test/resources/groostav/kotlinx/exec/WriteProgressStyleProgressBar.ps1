#Simple Progress Bar script
Param(
    [int]$SleepTime = 500
)


Write-Host "Important task 42"
foreach($index in 1..10){
    Write-Progress -Activity "ASDF" -PercentComplete ($index * 10)
    Sleep -m $SleepTime
}
Write-Progress -Activity "ASDF" -Completed

Write-Host "done Important Task 42!"