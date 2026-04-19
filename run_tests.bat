@echo off
echo Compiling HTTP Server and Integration Tests...
javac -d out src/*.java test/*.java

if %ERRORLEVEL% equ 0 (
    echo Compilation successful. Running tests...
    java -cp out ServerIntegrationTest
) else (
    echo Compilation failed.
    exit /b 1
)
