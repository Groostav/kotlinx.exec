Write-Verbose "starting..."
Try
{
    While($true)
    {
        Start-Sleep -Milliseconds 500
        Write-Verbose "looping..."
    }
    Exit 2
}
Finally
{
    # on CTRL-C events powershell shuts down the output pipe,
    # so WriteOutput actuall seems to fail and raise its own exception.
    Write-Host "interrupted"
    Exit 42
}

Exit 3