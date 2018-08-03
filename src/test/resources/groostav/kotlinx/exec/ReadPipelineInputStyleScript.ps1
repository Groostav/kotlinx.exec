Write-Output("Go ahead and write things to input...")

# powershell provides '$input' as the default value for the stdin.
# it can be a stream of objects or rows in a table, or simply a sequence of strings.
foreach($nextInput in $input){
    Write-Output("processing $nextInput!")
    Write-Output("Go ahead and write things to input...")
}
Write-Output("done!")