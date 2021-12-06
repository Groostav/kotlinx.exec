
Param(
    [DateTime] $deadline = $(Get-Date)
)


Do
{
    Write-Error "Performing first reading!"

    cat "$PSScriptRoot\iliad.txt"

    Write-Error "Hows that?"

    Start-Sleep -Seconds 3
}
While($(Get-Date) -lt $deadline)

