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
    exit 42
}

exit 3