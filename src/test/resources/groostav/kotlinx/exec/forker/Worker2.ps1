$prefix = "nginx-proxy  |"

Write-Output "$prefix starting"

Try
{
    Write-Output "$prefix starting proxy"
    Sleep -m 800
    Write-Output "$prefix HTTP GET /acme/challenge 200 OK"
    Write-Output "$prefix HTTP GET /acme/challenge/abcd1234 200 OK"
    Sleep -m 100
    Write-Output "$prefix got Lets encrypt certificate"

    While($true)
    {
        Write-Output "$prefix $prefix HTTP GET /imporant/customers 200 OK"
        Sleep -m 600
    }
}
Finally
{
    Write-Output "$prefix exiting"
}