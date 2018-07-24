Write-Host "Serious script(tm)(C)(R)"

Write-Host "Input the user name"

$User = Read-Host
while ($true)
{
    Write-Host "Input your server name, or 'quit' to exit"
    $Server = Read-Host
    if($Server -eq "quit") { break }

    $ServerDetails = Resolve-DnsName $Server

    if($ServerDetails -eq $null)
    {
        Write-Host "you're in luck $User, $Server isnt taken!"
    }
    else 
    {
        Write-Host "Sorry $User, $Server is already at $($ServerDetails[0].IPAddress)"
    }
}

Write-Host "Have a nice day!"