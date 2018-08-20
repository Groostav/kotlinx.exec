$prefix = "worker1      |"

Write-Output "$prefix worker-1 with PID=$PID"

Try
{
    While($true)
    {
        Write-Output "$prefix squeezing limes"
        Sleep -m 800
        Write-Output "$prefix provisioning mint"
        Sleep -m 400
        Write-Output "$prefix sourcing white rum"
        Sleep -m 500
        Write-Output "$prefix successfully acquired mojito"
        Sleep -m 600
    }
}
Finally
{
    Write-Output "$prefix exiting"
}