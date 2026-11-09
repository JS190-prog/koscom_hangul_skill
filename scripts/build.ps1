# src/*.java를 Java 8 호환으로 컴파일해 scripts/koscom-hwp.jar를 다시 만든다(JDK 필요).
# 템플릿 재생성: java -cp "lib\hwplib-1.1.9.jar;scripts\koscom-hwp.jar" TemplateBuilder templates\source\보고서_템플릿.hwp templates\source\투자계획서_템플릿.hwp templates\source\기안문_템플릿.hwp templates
# (원본 양식을 고쳤을 때만. selftest 11 이 재생성 결과가 templates/ 와 같은지 검사한다)
$skill = Split-Path -Parent $PSScriptRoot
$tmp = Join-Path $env:TEMP "koscom_hwp_build"
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory $tmp | Out-Null
javac --release 8 -encoding UTF-8 -cp "$skill\lib\hwplib-1.1.9.jar" -d $tmp (Get-ChildItem "$skill\src\*.java").FullName
if ($LASTEXITCODE -ne 0) { exit 1 }
Push-Location $tmp
jar cf "$skill\scripts\koscom-hwp.jar" *.class
Pop-Location
"built $skill\scripts\koscom-hwp.jar"
