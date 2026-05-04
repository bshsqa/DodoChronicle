# Encoding Policy

이 저장소의 텍스트 파일은 UTF-8과 LF 줄바꿈을 사용합니다.

## Windows PowerShell 주의사항

Windows PowerShell 5.1의 `Get-Content`는 BOM 없는 UTF-8 파일을 시스템 코드페이지로 잘못 읽을 수 있습니다. 한국어가 포함된 파일을 다음처럼 읽고 다시 저장하면 파일 내용이 깨질 수 있습니다.

```powershell
Get-Content path\file.kt
Set-Content path\file.kt
```

한국어가 포함된 파일을 수정할 때는 우선 `apply_patch`를 사용합니다. PowerShell로 꼭 수정해야 한다면 읽기와 쓰기 모두 UTF-8을 명시합니다.

```powershell
$lines = Get-Content -Path path\file.kt -Encoding UTF8
Set-Content -Path path\file.kt -Value $lines -Encoding UTF8
```

PowerShell 7 이상에서는 UTF-8 처리가 더 안전하지만, 그래도 저장소 파일을 다시 저장하는 작업은 최소 범위로 수행합니다.
