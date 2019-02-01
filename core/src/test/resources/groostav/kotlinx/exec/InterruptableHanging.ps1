Write-Verbose "starting..."
Try
{
    While($true)
    {
        Start-Sleep -Milliseconds 500
        Write-Verbose "looping..."
    }
    exit 2
}
Finally
{
    # on CTRL-C events powershell shuts down the output pipe,
    # so WriteOutput doesnt seem to go anywhere.
    Write-Host "interrupted"
    exit 42
}

exit 3