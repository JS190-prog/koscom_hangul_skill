param(
  [Parameter(Mandatory = $true)][string]$Json,   # report.json 경로
  [Parameter(Mandatory = $true)][string]$Out     # 만들 .hwp 경로
)
# report.json → 코스콤 서식 .hwp. 표준출력: WARN … / OK <경로> / ERROR …(종료 코드 1)
# 그림(figure)의 상대 경로는 현재 폴더 기준이다.
$skill = Split-Path -Parent $PSScriptRoot
$cp = "$skill\lib\hwplib-1.1.9.jar;$skill\scripts\koscom-hwp.jar"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
java "-Dfile.encoding=UTF-8" -cp $cp Renderer $Json $Out "$skill\templates"
exit $LASTEXITCODE
