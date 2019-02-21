


$block = { param($millis)
    Start-Sleep -Milliseconds $millis
}

$deadline = $(Get-Date) + $(New-TimeSpan -Seconds 45)
$warmedUp = $false

While($(Get-Date) -lt $deadline)
{
    $result = 1 .. 10 | ForEach { 
        $millis = $(Get-Random -Minimum 1 -Maximum 400)
        Start-Job -Name "delay-$_(t=$millis)" -ScriptBlock $block -ArgumentList $millis
    }

    $result | ForEach { 
        Wait-Job $_ # could pipe this to null, but it gives us a good running thing. 
    }

    If(-not $warmedUp)
    {
        $warmedUp = $true
        Write-Output "warmed-up"
    }
}

#Write-Output "finished without termination"

