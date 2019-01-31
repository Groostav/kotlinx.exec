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
    Write-Output "interrupted"
    exit 42
}

exit 3