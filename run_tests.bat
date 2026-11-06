@echo off
rem Compiles the server and the tests, then runs the whole test suite. Needs JDK 21+.
cd /d "%~dp0"
echo Compiling server and tests...
if exist out rmdir /s /q out
javac -d out src\*.java test\*.java
if %ERRORLEVEL% neq 0 (
    echo Compilation failed.
    exit /b 1
)
echo Running tests...
java -cp out TestRunner %*
exit /b %ERRORLEVEL%
